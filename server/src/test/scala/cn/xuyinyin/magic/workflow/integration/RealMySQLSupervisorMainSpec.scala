package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.workflow.scheduling._
import com.typesafe.config.{ConfigFactory,ConfigRenderOptions}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import spray.json._
import java.nio.file.{Files,Path}
import java.nio.file.attribute.PosixFilePermissions
import java.net.{ServerSocket,URLClassLoader}
import java.util.{Base64,UUID}
import scala.jdk.CollectionConverters._
import scala.concurrent.{Await,ExecutionContext}
import scala.concurrent.duration._

/** Actual production main classes and fixed PekkoServer worker, without replacing their launch factory. */
class RealMySQLSupervisorMainSpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  import SnapshotGuardianWire._
  "the installed production entry points" should {
    "complete a real snapshot and restart its supervisor without rewriting completed rows" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_MYSQL_SUPERVISOR_RECOVERY").contains("1"))cancel("external_blocked: disposable MySQL required")
      val s=Settings.environment();val suffix=UUID.randomUUID().toString.replace("-","").take(12)
      val schema="pekko_test_cdc_window_"+suffix
      val dir=Path.of("target","supervisor-main","startup-"+suffix).toAbsolutePath;Files.createDirectories(dir)
      val anchor=Path.of("/tmp","sm-"+suffix);Files.createDirectory(anchor,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
      val keys=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();val key=Base64.getEncoder.encodeToString(keys.getPublic.getEncoded)
      val secret=new Array[Byte](32);new java.security.SecureRandom().nextBytes(secret)
      Files.write(anchor.resolve("auth.key"),secret);Files.writeString(anchor.resolve("signing.key"),Base64.getEncoder.encodeToString(keys.getPrivate.getEncoded))
      val socket=new ServerSocket(0);val port=try socket.getLocalPort finally socket.close()
      val name="supervisor-main-"+suffix
      val shared=Map[String,AnyRef](
        "pekko.workflow.snapshot-ha.deployment-id"->"takeover-experiment","pekko.workflow.snapshot-ha.enabled"->java.lang.Boolean.TRUE,"pekko.workflow.snapshot-ha.instances"->java.util.Collections.emptyList[AnyRef](),
        "pekko.workflow.table-scheduling.enabled"->java.lang.Boolean.TRUE,"pekko.workflow.table-scheduling.jdbc.url"->s.url(schema).takeWhile(_!='?'),"pekko.workflow.table-scheduling.jdbc.username"->s.user,"pekko.workflow.table-scheduling.jdbc.password"->s.password,
        "pekko.workflow.table-scheduling.max-active-tables"->Int.box(2),"pekko.workflow.table-scheduling.max-active-tables-per-node"->Int.box(1),"pekko.workflow.table-scheduling.max-connections-per-instance"->Int.box(4),
        "pekko.workflow.table-scheduling.max-concurrent-starts"->Int.box(1),"pekko.workflow.table-scheduling.max-page-reservation"->"24 KiB","pekko.workflow.table-scheduling.candidate-page"->Int.box(16),
        "pekko.workflow.event-sourcing.snapshot-every"->Int.box(1000),"pekko-persistence-jdbc.shared-databases.slick.profile"->"slick.jdbc.MySQLProfile$",
        "pekko-persistence-jdbc.shared-databases.slick.db.driver"->"com.mysql.cj.jdbc.Driver","pekko-persistence-jdbc.shared-databases.slick.db.url"->s.url(schema),"pekko-persistence-jdbc.shared-databases.slick.db.user"->s.user,"pekko-persistence-jdbc.shared-databases.slick.db.password"->s.password)
      val worker=ConfigFactory.parseMap((shared++Map[String,AnyRef](
        "pekko.pekko-sys"->name,"pekko.remote.artery.canonical.hostname"->"127.0.0.1","pekko.remote.artery.canonical.port"->Int.box(port),
        "pekko.cluster.seed-nodes"->Vector(s"pekko://$name@127.0.0.1:$port").asJava,"pekko.cluster.roles"->Vector("coordinator","worker").asJava,
        "pekko.cluster.downing-provider-class"->"","pekko.cluster.auto-down-unreachable-after"->"off","pekko.workflow.cluster-bootstrap.enabled"->java.lang.Boolean.FALSE)).asJava)
        .withFallback(ConfigFactory.load("application-multinode-test")).withFallback(ConfigFactory.load()).resolve()
      Files.writeString(anchor.resolve("worker.conf"),worker.root.render(ConfigRenderOptions.concise()))
      val cfg=ConfigFactory.parseMap((shared++Map[String,AnyRef](
        "pekko.workflow.snapshot-supervisor.host-id"->suffix,"pekko.workflow.snapshot-supervisor.guardian-boot"->UUID.randomUUID().toString,"pekko.workflow.snapshot-supervisor.public-key"->key,
        "pekko.workflow.snapshot-supervisor.max-workers"->Int.box(1),"pekko.workflow.snapshot-supervisor.socket"->anchor.resolve("rpc.sock").toString,
        "pekko.workflow.snapshot-supervisor.auth-key-file"->anchor.resolve("auth.key").toString,"pekko.workflow.snapshot-supervisor.private-key-file"->anchor.resolve("signing.key").toString,
        "pekko.workflow.snapshot-supervisor.guardian-lock"->anchor.resolve("guardian.lock").toString,"pekko.workflow.snapshot-supervisor.supervisor-lock"->anchor.resolve("supervisor.lock").toString,
        "pekko.workflow.snapshot-supervisor.worker-config-file"->anchor.resolve("worker.conf").toString,"pekko.workflow.snapshot-supervisor.worker-xmx-mib"->Int.box(512))).asJava)
      val config=anchor.resolve("supervisor.conf");Files.writeString(config,cfg.root.render(ConfigRenderOptions.concise()))
      Vector("auth.key","signing.key","supervisor.conf","worker.conf").foreach(n=>Files.setPosixFilePermissions(anchor.resolve(n),PosixFilePermissions.fromString("rw-------")))
      OwnerIsolationProbeSupport.provision(s,schema,"owner-probe-"+suffix)
      withConnection(s,schema){c=>Vector("db/mysql/pekko-snapshot-ownership-schema.sql","db/mysql/pekko-snapshot-supervisor-schema.sql").foreach { path=>
        val in=getClass.getClassLoader.getResourceAsStream(path);val sql=try scala.io.Source.fromInputStream(in).mkString finally in.close();sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c,_))
      }}
      val settings=new SnapshotSupervisorSettings(config);implicit val ec:ExecutionContext=ExecutionContext.global
      val execution=Await.result(new TableSchedulingRuntime(settings.tableStore,"harness").submit(OwnerIsolationProbeSupport.workflow(s,schema),"takeover-one"),10.seconds).executionId.get
      val urls=Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(l=>Option(l.getParent))).takeWhile(_.nonEmpty).flatten.collect{case l:URLClassLoader=>l.getURLs.iterator}.flatten.filter(_.getProtocol=="file").map(u=>Path.of(u.toURI).toString)
      val cp=(urls++System.getProperty("java.class.path").split(java.io.File.pathSeparator)).toVector.distinct.mkString(java.io.File.pathSeparator)
      val children=scala.collection.mutable.ArrayBuffer.empty[(String,Child)]
      def start(role:String,main:String):Child={
        val p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx256m","-Duser.timezone=UTC","-cp",cp,main,config.toString).redirectErrorStream(true).start()
        val c=new Child(p,dir.resolve(role+".log"));children+=role->c;write(dir,role+"-process.json",OwnerIsolationProbeSupport.process(c));c
      }
      var guardian:Child=null;var success=false
      try {
        Files.writeString(anchor.resolve("worker.conf"),worker.withValue("pekko.workflow.snapshot-ha.enabled",com.typesafe.config.ConfigValueFactory.fromAnyRef(false)).root.render(ConfigRenderOptions.concise()))
        val invalid=start("invalid-guardian",SnapshotGuardianMain.getClass.getName.stripSuffix("$"))
        val invalidExit=invalid.exit();invalidExit should not be 0
        write(dir,"invalid-profile-rejected.json",JsObject("exitCode"->JsNumber(invalidExit),"beforeFork"->JsBoolean(true)))
        withConnection(s,schema)(query(_,"SELECT COUNT(*) AS launches FROM pekko_snapshot_worker_launches")).elements.head.asJsObject.fields("launches") shouldBe JsString("0")
        Files.writeString(anchor.resolve("worker.conf"),worker.root.render(ConfigRenderOptions.concise()))
        guardian=start("guardian",SnapshotGuardianMain.getClass.getName.stripSuffix("$"))
        awaitCondition("real guardian RPC socket",20.seconds){Files.exists(settings.socket)}
        val a=start("A",SnapshotSupervisorMain.getClass.getName.stripSuffix("$"))
        awaitCondition("fixed production PekkoServer completed the original snapshot",70.seconds){settings.tableStore.get("table-00").exists(_.state=="completed")}
        val old=settings.metadata.pending.head.bootId;write(dir,"before-restart.json",SupervisorRecoveryTest.capture(settings,schema));a.kill()
        val b=start("B",SnapshotSupervisorMain.getClass.getName.stripSuffix("$"))
        awaitCondition("production supervisor replacement retired old worker and started successor",40.seconds){settings.metadata.launch(old).exists(_.phase=="retired") && settings.metadata.pending.exists(_.bootId!=old)}
        val after=SupervisorRecoveryTest.capture(settings,schema);write(dir,"final.json",after)
        val data=after.fields("data").asJsObject;data.fields("comparison").asJsObject.fields("equal") shouldBe JsBoolean(true)
        data.fields("T").asInstanceOf[JsArray].elements.size shouldBe 5;data.fields("audit").asInstanceOf[JsArray].elements.size shouldBe 10
        settings.tableStore.get("table-00").get.executionId shouldBe Some(execution)
        write(dir,"result.json",JsObject("scenario"->JsString("ACTUAL_MAIN_STARTUP_RESTART"),"schema"->JsString(schema),"outcome"->JsString("CONVERGED"),"D"->JsString("N/A"),"originalExecution"->JsString(execution),"fixedProductionWorkerMain"->JsString("cn.xuyinyin.magic.PekkoServer")))
        success=true
      }catch{case e:Throwable=>write(dir,"failure.json",JsObject("errorClass"->JsString(e.getClass.getName),"state"->SupervisorRecoveryTest.capture(settings,schema)));throw e}
      finally {
        try {
          children.filterNot(_._1=="guardian").foreach(_._2.kill())
          if(guardian!=null && guardian.alive && settings.metadata.current.nonEmpty) {
            val lock=settings.lock("supervisor")
            try {
              val control=settings.metadata.claimController(UUID.randomUUID().toString);val client=new SnapshotGuardianWire.Client(settings.socket,secret,control,frame=>SupervisorRecoveryTest.append(dir.resolve("cleanup-wire.jsonl"),frame));client.request("BIND")
              settings.metadata.pending.foreach { t=>
                if(t.identity.nonEmpty) {
                  val op=settings.metadata.operation(control,t.bootId);val ha=settings.tableStore.ownership.get
                  ha.owner(t.bootId).foreach(_=>ha.beginRevocation(t.bootId,op))
                  val raw=text(client.request("STOP",JsObject("boot"->JsString(t.bootId),"identity"->t.identity.get.toJson,"operation"->JsString(op))),"proof")
                  val receipt=verifyReceipt(raw,settings.binding,t.identity.get,op)
                  settings.metadata.recordProof(control,t.bootId,raw){if(ha.owner(t.bootId).isEmpty)require(ha.recordFailedStart(receipt))else require(ha.confirmIsolation(receipt))}
                  val observer=settings.observer
                  awaitCondition("production main teardown old database drain",20.seconds){
                    ha.instances(t.bootId).foreach(i=>ha.recordObservation(t.bootId,observer.observe(t.bootId,i),100));ha.owner(t.bootId).exists(o=>Set("db_drained","startup_failed").contains(o.phase))
                  }
                  ha.releaseDrained(t.bootId,100);settings.metadata.retire(control,t.bootId);client.request("RETIRE",JsObject("boot"->JsString(t.bootId)))
                }else client.request("CANCEL",JsObject("boot"->JsString(t.bootId)))
              }
            }finally lock.close()
          }
        }finally {try {if(guardian!=null)guardian.kill()}finally settings.close()}
        write(dir,"cleanup.json",JsObject("success"->JsBoolean(success),"processes"->JsArray(children.toVector.map{case(role,c)=>JsObject("role"->JsString(role),"alive"->JsBoolean(c.alive),"pid"->JsNumber(c.pid))})))
      }
    }
  }
}
