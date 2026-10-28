package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.connectors.mysql.source.capture.MySQLCaptureStore
import cn.xuyinyin.magic.tags.ExternalIntegration
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import java.io.File
import java.net.{URI, URLClassLoader}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import java.time.Duration
import java.util.UUID
import scala.concurrent.duration._
import spray.json._

/** The HTTP ingress, scheduler and production kernel all run inside a separate real worker. */
class CdcPlatformMatrixSpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
  private val variants = Vector("P01-dynamic-seven", "P02-tail-capacity", "P03-queued-cancel",
    "P04-initializing-cancel", "P05-streaming-cancel", "P06-controlled-restart", "P07-unknown-owner", "P07-live-owner", "P07-stale-ready", "P07-crash-held-l", "P08-api-projection")

  "the resident CDC platform with real MySQL, Actor Journal and Sink" should {
    for (variant <- variants) {
      s"preserve durable control, bounded execution and field evidence for $variant" taggedAs ExternalIntegration in {
        if (!sys.env.get("RUN_CDC_PLATFORM_MATRIX").contains("1")) cancel("exclusive dual-MySQL platform fixture required")
        require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty), "exclusive slot lease required")
        val source = Settings.environment(); val state = source.copy(port = sys.env("CAPTURE_STATE_PORT").toInt)
        require(!Set(3306, 13365, 13366).contains(source.port) && !Set(3306, 13365, 13366).contains(state.port))
        def identity(settings: Settings) = withConnection(settings, "")(query(_, "SELECT @@server_uuid AS uuid,@@port AS port,@@datadir AS datadir,VERSION() AS version"))
        val sourceIdentity = identity(source); val stateIdentity = identity(state)
        val lineage = sourceIdentity.elements.head.asJsObject.fields("uuid").convertTo[String]
        val stateLineage = stateIdentity.elements.head.asJsObject.fields("uuid").convertTo[String]
        require(lineage == sys.env("CAPTURE_EXPECT_SOURCE_UUID") && stateLineage == sys.env("CAPTURE_EXPECT_STATE_UUID") && lineage != stateLineage)
        val suffix = UUID.randomUUID().toString.replace("-", "").take(12)
        val schema = "pekko_test_cdc_window_" + suffix; val captureId = "platform_" + suffix
        val sourceUser = "cs_" + suffix; val stateUser = "ds_" + suffix
        val dir = Path.of(sys.env.getOrElse("CDC_PLATFORM_EVIDENCE", "target/cdc-platform-matrix"), variant + "-" + suffix).toAbsolutePath
        Files.createDirectories(dir)
        write(dir, "environment.json", JsObject("case" -> JsString(variant), "schema" -> JsString(schema), "captureId" -> JsString(captureId),
          "source" -> sourceIdentity, "state" -> stateIdentity, "sourceCaptureAccount" -> JsString(sourceUser), "stateSdkAccount" -> JsString(stateUser),
          "sourceManifestSha256" -> JsString(sys.env.getOrElse("BOOTSTRAP_SOURCE_MANIFEST_SHA256", "unfrozen")), "targetBaseline" -> JsString("exclusive empty; no copied baseline")))
        def fixtureStep[A](name: String)(f: => A): A = try f catch { case e: Throwable =>
          write(dir, "fixture-failure.json", JsObject("stage" -> JsString(name), "errorClass" -> JsString(e.getClass.getSimpleName),
            "message" -> JsString(Option(e.getMessage).getOrElse("").replace(source.password, "[REDACTED]"))))
          throw e
        }
        fixtureStep("create-source-schema")(initialize(source, schema)); fixtureStep("create-state-schema")(initialize(state, schema))
        def sql(settings: Settings, value: String): Unit = withConnection(settings, schema)(executeSql(_, value))
        withConnection(state, schema) { c =>
          val input = getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-sync-cdc-group-ledger-schema.sql")
          val ddl = try scala.io.Source.fromInputStream(input).mkString finally input.close()
          ddl.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c, _))
        }
        val names = if (variant.startsWith("P02")) Vector("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k") else Vector("a", "b", "c", "d", "e", "f", "g")
        names.foreach { table => sql(source, s"CREATE TABLE $table LIKE source_rows"); sql(source, s"INSERT INTO $table SELECT * FROM source_rows"); sql(state, s"CREATE TABLE target_$table LIKE source_rows") }
        def account(settings: Settings, user: String, sourceRole: Boolean): Unit = withConnection(settings, "") { c =>
          val secret = settings.password.replace("\\", "\\\\").replace("'", "\\'")
          executeSql(c, s"CREATE USER '$user'@'%' IDENTIFIED BY '$secret'")
          executeSql(c, s"GRANT ALL PRIVILEGES ON `$schema`.* TO '$user'@'%'")
          if (sourceRole) executeSql(c, s"GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO '$user'@'%'")
        }
        fixtureStep("create-source-account")(account(source, sourceUser, sourceRole = true))
        fixtureStep("create-state-account")(account(state, stateUser, sourceRole = false))
        val proxyEvents=new java.util.concurrent.ConcurrentLinkedQueue[JsObject]()
        val heldProxy=if(variant=="P07-crash-held-l")Some(new MySQLCommitResponseProxy(state.host,state.port,
          (k,v)=>proxyEvents.add(JsObject(v.fields+("kind"->JsString(k))))))else None
        val store = new MySQLCaptureStore(() => java.sql.DriverManager.getConnection(state.url(schema), state.user, state.password), captureId, lineage, 128L * 1024 * 1024)
        def start(label: String, holdStart: Boolean): Child = {
          val loaders = Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(x => Option(x.getParent))).takeWhile(_.nonEmpty).flatten
            .collect { case l: URLClassLoader => l.getURLs.iterator }.flatten.filter(_.getProtocol == "file").map(x => Path.of(x.toURI).toString)
          val cp = (loaders ++ System.getProperty("java.class.path", "").split(File.pathSeparator).iterator).filter(_.nonEmpty).toVector.distinct.mkString(File.pathSeparator)
          val builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString, "-Xmx768m", "-Duser.timezone=UTC", "-cp", cp,
            CdcPlatformMatrixWorker.getClass.getName.stripSuffix("$"), schema, captureId, sourceUser, stateUser, lineage, stateLineage, holdStart.toString)
          builder.redirectErrorStream(true)
          if(label=="original")heldProxy.foreach(p=>builder.environment().put("CAPTURE_TEST_CONTROL_PROXY_PORT",p.port.toString))
          new Child(builder.start(), dir.resolve(label + ".log"))
        }
        var port = 0
        def call(method: String, path: String, body: Option[JsValue] = None): (Int, JsValue) = {
          val builder = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/api/v1/$path")).timeout(Duration.ofSeconds(25)).header("Content-Type", "application/json")
          val request = if (method == "GET") builder.GET().build() else builder.POST(HttpRequest.BodyPublishers.ofString(body.fold("")(_.compactPrint))).build()
          val response = client.send(request, HttpResponse.BodyHandlers.ofString())
          response.statusCode() -> response.body().parseJson
        }
        def submit(table: String, request: String = "", generation: String = "g1"): JsObject = {
          val key = if (request.nonEmpty) request else table
          val (status, json) = call("POST", "cdc-tasks", Some(JsObject("requestId" -> JsString(key), "captureId" -> JsString(captureId),
            "table" -> JsString(table), "generation" -> JsString(generation), "targetTable" -> JsString("target_" + table))))
          status shouldBe 202; json.asJsObject.fields("task").asJsObject
        }
        def get(id: String): JsObject = { val (status, value) = call("GET", "cdc-tasks/" + id); status shouldBe 200; value.asJsObject }
        def phase(id: String) = get(id).fields("phase").convertTo[String]
        def ready(id: String): Unit = awaitCondition("platform durable STREAMING " + id, 180.seconds)(phase(id) == "STREAMING")
        def cancelTask(id: String): JsObject = { val (status, value) = call("POST", "cdc-tasks/" + id + "/cancel"); status shouldBe 202; value.asJsObject }
        def mutate(table: String, label: String): Unit = sql(source, s"UPDATE $table SET label='$label',revision=revision+1,amount=amount+0.1250,note='',updated_at='2026-10-27 04:05:06.123456' WHERE id=1")
        def comparison(table: String): JsObject = {
          val left = withConnection(source, schema)(query(_, s"SELECT * FROM $table ORDER BY id")); val right = withConnection(state, schema)(query(_, s"SELECT * FROM target_$table ORDER BY id"))
          val a = left.elements.map(_.asJsObject).map(r => r.fields("id") -> r).toMap; val b = right.elements.map(_.asJsObject).map(r => r.fields("id") -> r).toMap
          val differences = (a.keySet intersect b.keySet).toVector.flatMap(k => (a(k).fields.keySet ++ b(k).fields.keySet).toVector.collect {
            case f if a(k).fields.get(f) != b(k).fields.get(f) => JsObject("id" -> k, "field" -> JsString(f), "source" -> a(k).fields.getOrElse(f, JsNull), "target" -> b(k).fields.getOrElse(f, JsNull)) })
          JsObject("source" -> left, "target" -> right, "fieldsCompared" -> JsNumber(a.values.map(_.fields.size).sum), "equal" -> JsBoolean(left == right),
            "missing" -> JsArray((a.keySet -- b.keySet).toVector), "extra" -> JsArray((b.keySet -- a.keySet).toVector), "differences" -> JsArray(differences))
        }
        def equal(table: String) = comparison(table).fields("equal") == JsBoolean(true)
        val resultKind = variant.take(3) match {
          case "P01" | "P02" | "P06" => "CONVERGED"
          case "P03" => "PREEXEC_CANCELED"
          case "P04" | "P05" => "CANCELED_WITH_K"
          case "P07" => if(Set("P07-live-owner","P07-stale-ready")(variant)) "OWNER_REJECTED_OTHER_CONTINUES" else "SAFE_PAUSED"
          case _ => "CONTROL_PLANE_ONLY"
        }
        def save(label: String, worker: Child): Unit = write(dir, label + ".json", JsObject("case" -> JsString(variant), "point" -> JsString(label),
          "resultKind" -> (if (label == "final") JsString(resultKind) else JsNull), "worker" -> worker.state(),
          "comparisons" -> JsObject(names.map(t => t -> comparison(t)).toMap)))
        def idle(): Boolean = Vector(source, state).forall(s => withConnection(s, "")(c =>
          query(c, "SELECT ID FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND USER NOT IN ('event_scheduler','system user')").elements.isEmpty &&
            query(c, "SELECT trx_id FROM information_schema.INNODB_TRX").elements.isEmpty))
        def stop(child: Child): Unit = {
          child.send("STOP")
          awaitCondition("worker bounded stop and eventual physical confirmation", 180.seconds)(!child.alive)
          child.exit() shouldBe 0
        }
        var worker: Child = null; var recovered: Child = null
        try {
          worker = start("original", variant.startsWith("P03") || variant.startsWith("P08"))
          port = worker.awaitEvent("PROCESS_READY").fields("httpPort").convertTo[Int]
          variant.take(3) match {
            case "P01" =>
              val first = names.take(5).map(t => t -> submit(t).fields("taskId").convertTo[String]).toMap
              first.values.foreach(ready)
              val added = names.drop(5).map(t => t -> submit(t).fields("taskId").convertTo[String]).toMap
              names.foreach(t => mutate(t, "dynamic-seven")); (first ++ added).values.foreach(ready)
              awaitCondition("seven platform tables equal", 180.seconds)(names.forall(equal))
            case "P02" =>
              val first = names.take(10).map(t => submit(t).fields("taskId").convertTo[String]); first.foreach(ready)
              val last = submit("k").fields("taskId").convertTo[String]; ready(last)
              names.foreach(t => mutate(t, "eleven-tails")); awaitCondition("eleven tails remain live", 180.seconds)(names.forall(equal))
            case "P03" =>
              val accepted = submit("a"); val id = accepted.fields("taskId").convertTo[String]
              accepted.fields("phase") shouldBe JsString("QUEUED")
              cancelTask(id).fields("phase") shouldBe JsString("CANCELED")
              val canceled = get(id)
              canceled.fields("initializationEvidence") shouldBe JsNull
              canceled.fields("cancellationEvidence") shouldBe JsString("PREEXEC_CANCEL")
              canceled.fields("executionId") shouldBe JsNull
              canceled.fields("journalRevision") shouldBe JsNumber(0)
              worker.send("RELEASE_START"); worker.awaitEvent("START_RELEASED")
              awaitCondition("capture after canceled queue")(call("GET", "cdc-captures")._2.asJsObject.fields("items").asInstanceOf[JsArray].elements.head.asJsObject.fields("readiness") == JsString("ADMISSION_READY"))
              store.subscription(id) shouldBe None
            case "P04" =>
              worker.send("ARM READ_OPEN"); worker.awaitEvent("ARMED")
              val id = submit("a").fields("taskId").convertTo[String]; worker.awaitEvent("CUTPOINT"); save("initialization-held", worker)
              cancelTask(id).fields("desiredState") shouldBe JsString("CANCELED")
              worker.send("RELEASE"); worker.awaitEvent("GATE_RELEASED")
              awaitCondition("initialization cancellation drained", 180.seconds)(phase(id) == "CANCELED")
              store.subscription(id).get.k.nonEmpty shouldBe true
            case "P05" =>
              val a = submit("a").fields("taskId").convertTo[String]; val b = submit("b").fields("taskId").convertTo[String]; ready(a); ready(b)
              cancelTask(a); awaitCondition("streaming cancellation drained", 180.seconds)(phase(a) == "CANCELED")
              val old = withConnection(state, schema)(query(_, "SELECT * FROM target_a ORDER BY id"))
              mutate("a", "after-K-not-delivered"); mutate("b", "other-table-continues")
              awaitCondition("other table continues after K", 180.seconds)(equal("b")); ready(b)
              withConnection(state, schema)(query(_, "SELECT * FROM target_a ORDER BY id")) shouldBe old
            case "P06" =>
              val id = submit("a").fields("taskId").convertTo[String]; ready(id); save("before-controlled-stop", worker)
              stop(worker)
              awaitCondition("first worker actual sessions and transactions end", 180.seconds)(idle())
              mutate("a", "between-clean-processes")
              recovered = start("recovered", holdStart = false); port = recovered.awaitEvent("PROCESS_READY").fields("httpPort").convertTo[Int]
              ready(id); awaitCondition("controlled restart fields equal", 180.seconds)(equal("a"))
            case "P07" =>
              if(variant=="P07-crash-held-l"){
                val id=submit("a").fields("taskId").convertTo[String];ready(id)
                awaitCondition("initialized baseline equal",180.seconds)(equal("a"));save("before-held-L",worker)
                worker.send("ARM RAW_CALLBACK");worker.awaitEvent("ARMED")
                mutate("a","held-l-after-crash");worker.awaitEvent("CUTPOINT")
                heldProxy.get.arm("LATE_ROLLBACK",JsObject("stage"->JsString("CAPTURE_L_COMMIT")))
                worker.send("RELEASE");worker.awaitEvent("GATE_RELEASED")
                awaitCondition("actual proxied transaction held",60.seconds)(proxyEvents.toArray.toVector.exists(_.asInstanceOf[JsObject].fields("kind")==JsString("PROXY_TX_HELD")))
                val heldEvent=proxyEvents.toArray.toVector.map(_.asInstanceOf[JsObject]).find(_.fields("kind")==JsString("PROXY_TX_HELD")).get
                val connection=heldEvent.fields("connectionId").convertTo[Long]
                def heldRows=withConnection(state,schema)(query(_,s"SELECT t.trx_id,t.trx_state,t.trx_mysql_thread_id,p.USER FROM information_schema.INNODB_TRX t JOIN information_schema.PROCESSLIST p ON p.ID=t.trx_mysql_thread_id WHERE t.trx_mysql_thread_id=$connection"))
                awaitCondition("actual InnoDB transaction visible")(heldRows.elements.nonEmpty)
                val account=heldRows.elements.head.asJsObject.fields("USER").convertTo[String]
                write(dir,"held-l-before-crash.json",JsObject("held"->heldRows,"event"->heldEvent,"expectedAccount"->JsString(stateUser),"actualAccount"->JsString(account),"worker"->worker.state(),"comparison"->comparison("a")))
                account shouldBe stateUser
                worker.send("CRASH");worker.exit() shouldBe 86
                heldRows.elements should not be empty
                recovered=start("unproven-owner",holdStart=false);port=recovered.awaitEvent("PROCESS_READY").fields("httpPort").convertTo[Int]
                val paused=recovered.state();paused.fields("platform").asJsObject.fields("ownerState") shouldBe JsString("UNKNOWN")
                paused.fields("engineCreations") shouldBe JsNumber(0)
                write(dir,"held-l-after-crash.json",JsObject("held"->heldRows,"newWorker"->paused,"comparison"->comparison("a")))
                heldProxy.get.resolveHeldTransaction();awaitCondition("old L transaction rollback",60.seconds)(heldRows.elements.isEmpty)
                write(dir,"held-l-resolved.json",JsObject("held"->heldRows,"newWorker"->recovered.state(),"comparison"->comparison("a"),
                  "proxyEvents"->JsArray(proxyEvents.toArray.toVector.map(_.asInstanceOf[JsObject]))))
              }else {
              worker.send("ARM BASELINE_T"); worker.awaitEvent("ARMED")
              val id=submit("a").fields("taskId").convertTo[String]; worker.awaitEvent("CUTPOINT"); save("active-grant-before-crash", worker)
              val oldPort=port
              val live=Set("P07-live-owner","P07-stale-ready")(variant)
              if(!live){
                worker.send("CRASH"); worker.exit() shouldBe 86
                awaitCondition("crashed worker actual sessions and transactions end", 180.seconds)(idle())
              }
              recovered = start("unproven-owner", holdStart = false); port = recovered.awaitEvent("PROCESS_READY").fields("httpPort").convertTo[Int]
              val status = call("GET", "cdc-status")._2.asJsObject
              status.fields("ownerState") shouldBe JsString("UNKNOWN")
              status.fields("reason") shouldBe JsString("OWNER_RELEASE_UNPROVEN")
              val paused = recovered.state().fields
              paused("engineCreations") shouldBe JsNumber(0)
              paused("grants").asInstanceOf[JsArray].elements.exists(r => Set("ACTIVE", "UNKNOWN").contains(r.asJsObject.fields("status").convertTo[String])) shouldBe true
              if(live){
                worker.alive shouldBe true
                write(dir,"live-owner-refusal.json",JsObject("newStatus"->status,"newWorker"->JsObject(paused),
                  "originalWorkerAlive"->JsBoolean(worker.alive),"originalWorker"->worker.state()))
                if(variant=="P07-stale-ready"){
                  val newPort=port
                  worker.send("RELEASE");worker.awaitEvent("GATE_RELEASED");port=oldPort;ready(id)
                  val oldReady=worker.state();port=newPort
                  val newAfter=call("GET","cdc-status")._2.asJsObject
                  val next=recovered.state()
                  write(dir,"late-old-ready.json",JsObject("oldWorker"->oldReady,"newStatus"->newAfter,"newWorker"->next))
                  newAfter.fields("ownerState") shouldBe JsString("UNKNOWN")
                  next.fields("engineCreations") shouldBe JsNumber(0)
                }
                stop(recovered);recovered=null;port=oldPort
                if(variant=="P07-live-owner"){worker.send("RELEASE");worker.awaitEvent("GATE_RELEASED")}
                ready(id);mutate("a","live-owner-still-progresses")
                awaitCondition("original owner continues to Sink",180.seconds)(equal("a"))
              }
              }
            case "P08" =>
              val rows = names.map(t => submit(t)); val ids = rows.map(_.fields("taskId").convertTo[String]).sorted
              val repeated = submit("a"); repeated.fields("taskId") shouldBe rows.head.fields("taskId")
              var after: Option[String] = None; var actual = Vector.empty[String]; var done = false
              while (!done) {
                val page = call("GET", "cdc-tasks?pageSize=2" + after.fold("")(a => "&after=" + a))._2.asJsObject.fields
                actual ++= page("items").asInstanceOf[JsArray].elements.map(_.asJsObject.fields("taskId").convertTo[String])
                after = page("nextAfter") match { case JsString(value) => Some(value); case JsNull => None; case _ => fail("invalid keyset cursor") }
                done = after.isEmpty
              }
              actual shouldBe ids
              ids.foreach { id => val row = get(id); row.fields("phase") shouldBe JsString("QUEUED"); row.fields("runtimeHealth") should not be JsString("HEALTHY") }
              worker.send("RELEASE_START"); worker.awaitEvent("START_RELEASED")
          }
          val current = Option(recovered).getOrElse(worker)
          save("final", current)
          stop(current)
          awaitCondition("all platform sessions and transactions physically released", 180.seconds)(idle())
          write(dir, "release.json", JsObject("workerExited" -> JsBoolean(true), "sourceAndStateIdle" -> JsBoolean(true)))
        } catch { case e: Throwable =>
          val current = Option(recovered).orElse(Option(worker))
          current.filter(_.alive).foreach(c => try save("failure-state", c) catch { case _: Throwable => () })
          write(dir, "failure.json", JsObject("errorClass" -> JsString(e.getClass.getSimpleName), "message" -> JsString(Option(e.getMessage).getOrElse("").replace(source.password, "[REDACTED]"))))
          throw e
        } finally { heldProxy.foreach(_.close()); if (recovered != null) recovered.kill(); if (worker != null) worker.kill() }
      }
    }
  }
}
