package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.connectors.mysql.source._
import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.workflow.cdc._
import cn.xuyinyin.magic.tags.ExternalIntegration
import com.typesafe.config.ConfigFactory
import org.apache.kafka.connect.data.Struct
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import java.nio.file.{Files,Path}
import java.io.File
import java.net.URLClassLoader
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.{ConcurrentLinkedQueue,CountDownLatch,TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean,AtomicReference}
import scala.collection.mutable
import scala.concurrent.{Await,Future}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import spray.json._

/** L-resource ownership only. No task grant, Journal execution or target application is created. */
class CaptureLogResourceOwnershipSpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  "the production Kernel's capture L connections" should {
    "remain in the observed account domain while a real append transaction outlives the Reader" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_CAPTURE_ADMISSION_MATRIX").contains("1"))cancel("exclusive dual-MySQL slot required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty))
      val source=Settings.environment();val state=source.copy(port=sys.env("CAPTURE_STATE_PORT").toInt)
      require(!Set(3306,13365,13366).contains(source.port) && !Set(3306,13365,13366).contains(state.port))
      def identity(s:Settings)=withConnection(s,"")(query(_,"SELECT @@server_uuid AS uuid,@@port AS port,@@datadir AS datadir"))
      val sourceIdentity=identity(source);val stateIdentity=identity(state)
      val lineage=sourceIdentity.elements.head.asJsObject.fields("uuid").convertTo[String]
      val stateLineage=stateIdentity.elements.head.asJsObject.fields("uuid").convertTo[String]
      require(lineage==sys.env("CAPTURE_EXPECT_SOURCE_UUID") && stateLineage==sys.env("CAPTURE_EXPECT_STATE_UUID"))
      val suffix=UUID.randomUUID().toString.replace("-","").take(12);val schema="pekko_test_cdc_window_"+suffix;val id="l_owner_"+suffix
      val sourceUser="ls_"+suffix;val sdkUser="ld_"+suffix
      val out=Path.of(sys.env("CAPTURE_ADMISSION_EVIDENCE")).resolve(id);Files.createDirectories(out)
      initialize(source,schema);initialize(state,schema)
      withConnection(source,schema){c=>executeSql(c,"CREATE TABLE a LIKE source_rows");executeSql(c,"INSERT INTO a SELECT * FROM source_rows WHERE id<=4")}
      def account(s:Settings,user:String,isSource:Boolean):Unit=withConnection(s,""){c=>
        val secret=s.password.replace("\\","\\\\").replace("'","\\'")
        executeSql(c,s"CREATE USER '$user'@'%' IDENTIFIED BY '$secret'")
        executeSql(c,s"GRANT ALL PRIVILEGES ON `$schema`.* TO '$user'@'%'")
        if(isSource)executeSql(c,s"GRANT SELECT,RELOAD,SHOW DATABASES,REPLICATION SLAVE,REPLICATION CLIENT ON *.* TO '$user'@'%'")
      }
      account(source,sourceUser,true);account(state,sdkUser,false)
      val events=new ConcurrentLinkedQueue[JsObject]();val held=new CountDownLatch(1)
      val heldId=new AtomicReference[Option[Long]](None)
      def observe(kind:String,value:JsObject):Unit={
        events.add(JsObject(value.fields+("kind"->JsString(kind))))
        if(kind=="PROXY_TX_HELD"){
          heldId.set(value.fields("connectionId") match {case JsNumber(n)=>Some(n.toLong);case _=>None});held.countDown()
        }
      }
      val proxy=new MySQLCommitResponseProxy(state.host,state.port,observe)
      val armOnRow=new AtomicBoolean(false);val armed=new AtomicBoolean(false)
      val factory=new DebeziumEngineFactory {
        def create(properties:java.util.Properties):DebeziumEngineAccess={
          val original=DebeziumEngineFactory.real.create(properties)
          new DebeziumEngineAccess {
            def start(consumer:DebeziumBatchConsumer):Future[Done]=original.start(new DebeziumBatchConsumer {
              def handleBatch(records:Vector[org.apache.kafka.connect.source.SourceRecord],commit:CdcBatchCommitHandle):Unit={
                val update=records.exists {r=>r.value() match {
                  case v:Struct if v.schema().field("op")!=null=>v.getString("op")=="u" && r.topic()==id+"."+schema+".a"
                  case _=>false
                }}
                if(armOnRow.get() && update && armed.compareAndSet(false,true)){
                  observe("L_ARMED_ON_REAL_ROW",JsObject("records"->JsNumber(records.size),"rawOffsetText"->JsString(records.last.sourceOffset().toString)))
                  proxy.arm("LATE_ROLLBACK",JsObject("stage"->JsString("CAPTURE_L_GROUP_COMMIT"),"captureId"->JsString(id)))
                }
                consumer.handleBatch(records,commit)
              }
            })
            def close():Unit=original.close()
            override def localTerminationConfirmed:Boolean=original.localTerminationConfirmed
          }
        }
      }
      val control=MySQLCdcStateConfig(state.copy(port=proxy.port).url(schema),state.user,state.password,"capture_offsets","capture_history",0)
      val sdk=control.copy(jdbcUrl=state.url(schema),username=sdkUser)
      val sourceConfig=MySQLCdcSourceConfig(id,source.host,source.port,schema,"pekko_capture_control",sourceUser,source.password,
        (id.hashCode.toLong & 0x7fffffffL)+100000,128,100)
      val settings=CdcPlatformSettings(Vector(CdcCaptureSettings(id,sourceConfig,sdk,control,lineage,stateLineage,
        sourceObserverUsername=Some(source.user),sourceObserverPassword=Some(source.password))),CdcPlatformLimits(snapshotSteps=1,tailSteps=1))
      val config=ConfigFactory.parseString("pekko.actor.provider=local\npekko.loglevel=WARNING\npekko.stdout-loglevel=WARNING")
        .withFallback(ConfigFactory.load("application-test"))
      val kit=ActorTestKit("capture-log-owner-"+suffix,config)
      val kernel=new CdcBootstrapKernel(settings,kit.system,observe,factory)
      val platform=new CdcTaskStore(()=>DriverManager.getConnection(state.url(schema),state.user,state.password),settings.limits)
      platform.initialize();val owner="probe_"+suffix;platform.beginOwner(owner,System.currentTimeMillis()) shouldBe true
      val inspector=new MySQLCaptureStore(()=>DriverManager.getConnection(state.url(schema),state.user,state.password),id,lineage,128L*1024*1024)
      def release(e:CaptureReleaseEvidence):JsObject=JsObject("attempt"->JsString(e.runtimeAttemptId),"operation"->JsString(e.operationId),
        "local"->JsString(e.localTermination),"server"->JsString(e.serverRelease),"ownerReleased"->JsBoolean(e.ownerReleased),
        "reusable"->JsBoolean(e.reusable),"reason"->e.reasonCode.map(JsString(_)).getOrElse(JsNull),"observedAt"->JsNumber(e.observedAt))
      def releases:JsArray=JsArray(kernel.captureReleaseEvidence.map {case(capture,e)=>JsObject("captureId"->JsString(capture),"evidence"->release(e))})
      def stateSnapshot():JsObject={
        val offset=MySQLCdcSourceNode.connectorStateTables(sdk,id)._1
        JsObject("sourceSessions"->withConnection(source,schema)(query(_,"SELECT ID,USER,HOST,DB,COMMAND FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID()")),
          "stateSessions"->withConnection(state,schema)(query(_,"SELECT ID,USER,HOST,DB,COMMAND FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID()")),
          "stateTransactions"->withConnection(state,schema)(query(_,"SELECT t.trx_id,t.trx_state,t.trx_mysql_thread_id,p.USER FROM information_schema.INNODB_TRX t LEFT JOIN information_schema.PROCESSLIST p ON p.ID=t.trx_mysql_thread_id")),
          "captureOwner"->withConnection(state,schema)(query(_,s"SELECT IS_USED_LOCK('pekko_capture_$id') AS connection_id")),
          "platformOwner"->JsString(platform.ownerStatus),"activeOrUnknownTaskGrants"->JsNumber(platform.grants.size),
          "L"->inspector.evidence,"D"->withConnection(state,schema)(query(_,s"SELECT offset_key,offset_val FROM `$offset`")),
          "J"->withConnection(state,schema)(query(_,"SELECT persistence_id,sequence_number,event_ser_manifest FROM event_journal ORDER BY ordering")),
          "targetRows"->withConnection(state,schema)(query(_,"SELECT * FROM target_rows ORDER BY id")),"release"->releases)
      }
      // Test-only reflection obtains the existing real Reader; it does not replace factories, records or ACKs.
      def reader():SharedMySQLCapture={
        val field=kernel.getClass.getDeclaredFields.find(_.getName.endsWith("capturesById")).get;field.setAccessible(true)
        val capture=field.get(kernel).asInstanceOf[mutable.Map[String,AnyRef]](id)
        val getter=capture.getClass.getDeclaredMethods.find(_.getName=="reader").get;getter.setAccessible(true)
        getter.invoke(capture).asInstanceOf[SharedMySQLCapture]
      }
      var resolved=false;var finalReusable=false
      try {
        kernel.start()
        awaitCondition("production admission",90.seconds)(kernel.captures.head.readiness=="ADMISSION_READY")
        val actual=reader();actual.subscribe("l_probe","a","g1")
        awaitCondition("B bound")(inspector.subscription("l_probe").exists(_.b.nonEmpty))
        write(out,"environment.json",JsObject("source"->sourceIdentity,"state"->stateIdentity,"schema"->JsString(schema),"captureId"->JsString(id),
          "sourceAccount"->JsString(sourceUser),"sdkStateAccount"->JsString(sdkUser),"controlAccount"->JsString(state.user),
          "controlUrlPort"->JsNumber(proxy.port),"sdkStatePort"->JsNumber(state.port),"scope"->JsString("Kernel Reader/L ownership; no task execution, target reconciliation or Journal application")))
        write(out,"before.json",stateSnapshot())
        armOnRow.set(true)
        withConnection(source,schema)(executeSql(_,"UPDATE a SET label='held-L-owner',revision=revision+1 WHERE id=1"))
        held.await(20,TimeUnit.SECONDS) shouldBe true
        val connection=heldId.get().getOrElse(fail("real proxy did not record its backend connection"))
        def heldRows:JsArray=withConnection(state,schema)(query(_,s"SELECT t.trx_id,t.trx_state,t.trx_mysql_thread_id,p.USER,p.COMMAND FROM information_schema.INNODB_TRX t JOIN information_schema.PROCESSLIST p ON p.ID=t.trx_mysql_thread_id WHERE t.trx_mysql_thread_id=$connection"))
        awaitCondition("real held append transaction")(heldRows.elements.nonEmpty)
        val accountName=heldRows.elements.head.asJsObject.fields("USER").convertTo[String]
        write(out,"L-transaction-held.json",JsObject("state"->stateSnapshot(),"connectionId"->JsNumber(connection),"actualAccount"->JsString(accountName),
          "expectedAccount"->JsString(sdkUser),"accountWithinObservedDomain"->JsBoolean(accountName==sdkUser),"events"->JsArray(events.iterator().asScala.toVector)))
        kernel.closeAndVerify()
        val samples=Vector.newBuilder[JsValue]
        awaitCondition("local Reader and all SDK peers ended while L remains held",180.seconds){
          kernel.closeAndVerify()
          val snapshot=stateSnapshot();samples+=snapshot
          val sourceQuiet=snapshot.fields("sourceSessions").asInstanceOf[JsArray].elements.forall(_.asJsObject.fields("USER")!=JsString(sourceUser))
          val ownerId=snapshot.fields("captureOwner").asInstanceOf[JsArray].elements.head.asJsObject.fields("connection_id")
          val excluded=Set(JsString(connection.toString),ownerId)
          val stateQuiet=snapshot.fields("stateSessions").asInstanceOf[JsArray].elements.forall {s=>val f=s.asJsObject.fields;f("USER")!=JsString(sdkUser) || excluded(f("ID"))}
          heldRows.elements.nonEmpty && sourceQuiet && stateQuiet && kernel.captureReleaseEvidence.headOption.exists(_._2.localTermination=="CONFIRMED")
        }
        val reusableBeforeResolve=kernel.closeAndVerify()
        val ownerReleasedBeforeResolve=platform.endOwner(owner,reusableBeforeResolve,System.currentTimeMillis())
        write(out,"local-ended-L-still-held.json",JsObject("state"->stateSnapshot(),"connectionId"->JsNumber(connection),
          "reusableBeforeResolve"->JsBoolean(reusableBeforeResolve),"platformOwnerReleasedBeforeResolve"->JsBoolean(ownerReleasedBeforeResolve),
          "heldTransaction"->heldRows,"samples"->JsArray(samples.result())))
        // A fresh JVM attempts the same platform while the prior real L transaction and owner are unresolved.
        val loaders=Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(x=>Option(x.getParent))).takeWhile(_.nonEmpty).flatten
          .collect {case l:URLClassLoader=>l.getURLs.iterator}.flatten.filter(_.getProtocol=="file").map(x=>Path.of(x.toURI).toString)
        val cp=(loaders++System.getProperty("java.class.path","").split(File.pathSeparator).iterator).filter(_.nonEmpty).toVector.distinct.mkString(File.pathSeparator)
        val builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx768m","-Duser.timezone=UTC","-cp",cp,
          CdcPlatformMatrixWorker.getClass.getName.stripSuffix("$"),schema,id,sourceUser,sdkUser,lineage,stateLineage,"false")
        builder.redirectErrorStream(true)
        val next=new Child(builder.start(),out.resolve("unresolved-new-jvm.log"))
        val other=DriverManager.getConnection(source.url(schema),source.user,source.password)
        try {
          next.awaitEvent("PROCESS_READY")
          val fresh=next.state()
          fresh.fields("engineCreations") shouldBe JsNumber(0)
          fresh.fields("platform").asJsObject.fields("ownerState") shouldBe JsString("UNKNOWN")
          heldRows.elements should not be empty
          query(other,"SELECT 1 AS alive").elements should not be empty
          write(out,"unresolved-new-jvm.json",JsObject("freshWorker"->fresh,"oldHeldTransaction"->heldRows,
            "priorOwner"->JsString(platform.ownerStatus),"otherSessionAlive"->JsBoolean(true),"state"->stateSnapshot()))
          next.send("STOP");next.exit() shouldBe 0
        }finally {other.close();next.kill()}
        proxy.resolveHeldTransaction();resolved=true
        awaitCondition("actual old L transaction rollback")(heldRows.elements.isEmpty)
        awaitCondition("same close operation reusable after old transaction ends",180.seconds){finalReusable=kernel.closeAndVerify();finalReusable}
        platform.endOwner(owner,finalReusable,System.currentTimeMillis()) shouldBe true
        val finalState=stateSnapshot()
        write(out,"result.json",JsObject("status"->JsString(if(accountName==sdkUser && !reusableBeforeResolve && !ownerReleasedBeforeResolve)"PASS"else"FAIL"),
          "actualLAccount"->JsString(accountName),"expectedLAccount"->JsString(sdkUser),"reusableBeforeResolve"->JsBoolean(reusableBeforeResolve),
          "ownerReleasedBeforeResolve"->JsBoolean(ownerReleasedBeforeResolve),"final"->finalState,"events"->JsArray(events.iterator().asScala.toVector),
          "scope"->JsString("L resource ownership only; no target/Actor Journal recovery guarantee")))
        accountName shouldBe sdkUser
        reusableBeforeResolve shouldBe false
        ownerReleasedBeforeResolve shouldBe false
        finalState.fields("activeOrUnknownTaskGrants") shouldBe JsNumber(0)
        finalState.fields("J").asInstanceOf[JsArray].elements shouldBe empty
        finalState.fields("targetRows").asInstanceOf[JsArray].elements shouldBe empty
      }finally {
        if(!resolved)proxy.resolveHeldTransaction()
        if(!finalReusable)try {
          val deadline=180.seconds.fromNow
          while(!kernel.closeAndVerify() && deadline.hasTimeLeft())Thread.sleep(250)
        }catch {case _:Throwable=>()}
        proxy.close();kit.shutdownTestKit()
      }
    }
  }
}
