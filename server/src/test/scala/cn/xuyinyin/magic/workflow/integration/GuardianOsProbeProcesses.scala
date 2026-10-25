package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.workflow.scheduling._
import spray.json._
import java.nio.file.{Files,Path,StandardOpenOption}
import java.sql.DriverManager
import scala.concurrent.{Await,ExecutionContext,Future}
import scala.concurrent.duration._
import scala.util.control.NonFatal

/** Each instance is launched in a unique test-owned systemd cgroup. No replacement adopts a PID. */
object GuardianOsProbeGuardian {
  import CdcJournalWindowSupport._
  def main(args:Array[String]):Unit={
    require(args.length==7)
    val schema=args(0);val directory=args(1);val boot=args(2);val guardian=args(3);val scenario=args(4);val mode=args(5);val gate=args(6)
    val dir=Path.of(directory)
    def mark(name:String):Unit={Files.writeString(dir.resolve(guardian+"-"+name+".json"),JsObject("pid"->JsNumber(ProcessHandle.current().pid()),
      "guardian"->JsString(guardian),"cgroup"->JsString(Files.readString(Path.of("/proc/self/cgroup")))).prettyPrint);emit(name)}
    def waitFor(name:String):Unit=awaitCondition(name,180.seconds)(Files.exists(dir.resolve(name)))
    if(mode=="prestart"){mark("PRESTART_WAIT");waitFor("prestart.release");return}
    mark("GUARDIAN_"+guardian)
    waitFor("launch-"+guardian)
    val db=new GuardianProbeStore(schema,"os-probe-guardian")
    try {
      val c=db.ds.getConnection
      try require(query(c,s"SELECT phase FROM guardian_os_tickets WHERE boot='$boot'").elements.head.asJsObject.fields("phase")==JsString("prepared"),"closed laboratory ticket")finally c.close()
      if(mode!="recover" && scenario=="G5_BEFORE_FORK"){mark("BEFORE_FORK");waitFor("fork.release")}
      Files.writeString(dir.resolve(boot+".commands"),"")
      val mapped=if(mode=="recover")"NONE" else scenario match {
        case "G10_SOURCE"=>"KILL_DB_SOURCE_WAIT"
        case "G10_TARGET"=>"KILL_DB_TARGET_WAIT"
        case "G10_JOURNAL"=>"KILL_DB_JOURNAL_WAIT"
        case "G10_SNAPSHOT"=>"KILL_DB_SNAPSHOT_WAIT"
        case _=>"KILL_READ"
      }
      val builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx384m","-Duser.timezone=UTC",
        "-cp",System.getProperty("java.class.path"),SupervisorRecoveryWorker.getClass.getName.stripSuffix("$"),
        schema,boot,dir.toString,mapped,if(mode=="recover")"recover" else "fresh",gate,"false")
      builder.redirectErrorStream(true);builder.redirectOutput(dir.resolve(boot+".worker.log").toFile)
      if(scenario.startsWith("G10_")) {
        builder.environment.put("SUPERVISOR_TEST_PROXY_PORT",Files.readString(dir.resolve("proxy-port")).trim)
        builder.environment.put("SUPERVISOR_TEST_PROXY_ROLE",if(scenario=="G10_SOURCE")"source" else if(scenario=="G10_TARGET")"target" else "journal")
      }
      val manager=new ManagedJvmIsolation("takeover-experiment",1)
      val child=manager.launch(boot,builder)
      mark("AFTER_FORK")
      if(mode!="recover" && (scenario=="G6_FORK_UNRECORDED" || scenario=="G7_EXECUTED_ACK_LOST"))waitFor("identity.release")
      val connection=db.ds.getConnection
      try executeSql(connection,s"UPDATE guardian_os_tickets SET phase='launched',pid=${child.identity.pid},start_identity='${child.identity.startIdentity}' WHERE boot='$boot' AND phase='prepared'")finally connection.close()
      waitFor("guardian-exit")
    }finally db.close()
  }
}

/** Laboratory supervisor and relay, outside the old guardian cgroup. Production data/recovery implementations are reused. */
object GuardianOsProbeSupervisor {
  import CdcJournalWindowSupport._
  def main(args:Array[String]):Unit={
    require(args.length==2)
    val dir=Path.of(args(0));val pinnedKey=args(1)
    val input=Files.readString(dir.resolve("case.json")).parseJson.asJsObject
    def str(k:String)=input.fields(k).convertTo[String]
    val schema=str("schema");val oldBoot=str("oldBoot");val newBoot=str("newBoot");val scenario=str("scenario");val gate=str("gate")
    val s=Settings.environment()
    val proxy=if(scenario.startsWith("G10_"))Some(new SupervisorResidualProxy(s.host,s.port))else None
    proxy.foreach(p=>Files.writeString(dir.resolve("proxy-port"),p.port.toString))
    val role=if(scenario=="G10_SOURCE")"source" else if(scenario=="G10_TARGET")"target" else "journal"
    OwnerIsolationProbeSupport.provision(s,schema,gate)
    withConnection(s,schema){c=>
      val stream=getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-snapshot-ownership-schema.sql")
      val sql=try scala.io.Source.fromInputStream(stream).mkString finally stream.close()
      sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c,_))
      executeSql(c,"CREATE TABLE guardian_os_tickets (boot VARCHAR(128) PRIMARY KEY,guardian VARCHAR(128),phase VARCHAR(24),pid BIGINT,start_identity VARCHAR(128),proof LONGTEXT) ENGINE=InnoDB")
      executeSql(c,"CREATE TABLE guardian_os_control (id INT PRIMARY KEY,epoch BIGINT,phase VARCHAR(24)) ENGINE=InnoDB")
      executeSql(c,"INSERT INTO guardian_os_control VALUES (1,1,'active')")
      executeSql(c,s"INSERT INTO guardian_os_tickets VALUES ('$oldBoot','old','prepared',NULL,NULL,NULL)")
      executeSql(c,"CREATE TABLE guardian_os_journal_writes (sequence_number BIGINT,owner_id VARCHAR(128),permit_id VARCHAR(64),generation BIGINT,admission_sequence BIGINT) ENGINE=InnoDB")
      executeSql(c,"""CREATE TRIGGER guardian_os_journal_write AFTER INSERT ON event_journal FOR EACH ROW BEGIN IF NEW.persistence_id='workflow-table-00' THEN
        INSERT INTO guardian_os_journal_writes SELECT NEW.sequence_number,
        (SELECT ATTR_VALUE FROM performance_schema.session_connect_attrs WHERE PROCESSLIST_ID=CONNECTION_ID() AND ATTR_NAME='pekko_owner'),
        (SELECT permit_id FROM pekko_snapshot_writer_permissions WHERE workflow_id='table-00'),
        (SELECT generation FROM pekko_snapshot_writer_permissions WHERE workflow_id='table-00'),
        (SELECT queue_sequence FROM pekko_table_admission_lock WHERE id=1); END IF; END""")
      if(scenario=="G10_SNAPSHOT")executeSql(c,s"CREATE TRIGGER guardian_os_snapshot_wait BEFORE INSERT ON snapshot FOR EACH ROW BEGIN IF NEW.persistence_id='workflow-table-00' AND (SELECT id FROM owner_probe_journal_gate WHERE id=1)=1 THEN SET @snapshot_wait=GET_LOCK('$gate',120); DO RELEASE_LOCK('$gate'); END IF; END")
    }
    val db=new GuardianProbeStore(schema,"os-probe-supervisor")
    val holder=DriverManager.getConnection(s.url(schema)+"&connectionAttributes=pekko_owner:harness,pekko_role:observer",s.user,s.password)
    if(proxy.nonEmpty)require(query(holder,s"SELECT GET_LOCK('$gate',0) AS acquired").elements.head.asJsObject.fields("acquired")==JsString("1"))
    implicit val ec:ExecutionContext=ExecutionContext.global
    val execution=Await.result(new TableSchedulingRuntime(db.store,"os-probe-supervisor").submit(SupervisorRecoveryTest.workflow(s,schema,proxy.map(_.port),role),"takeover-one"),15.seconds).executionId.get
    def capture:JsObject=withConnection(s,schema){c=>JsObject("data"->OwnerIsolationProbeSupport.capture(s,schema),
      "owners"->query(c,"SELECT * FROM pekko_snapshot_owners ORDER BY owner_id"),
      "permissions"->query(c,"SELECT * FROM pekko_snapshot_writer_permissions ORDER BY workflow_id"),
      "instances"->query(c,"SELECT * FROM pekko_snapshot_owner_instances ORDER BY owner_id,instance_id"),
      "sessions"->query(c,"SELECT * FROM pekko_snapshot_owner_sessions ORDER BY owner_id,instance_id,connection_id"),
      "audit"->query(c,"SELECT * FROM pekko_snapshot_ownership_audit ORDER BY event_sequence"),
      "tickets"->query(c,"SELECT * FROM guardian_os_tickets ORDER BY boot"),"host"->query(c,"SELECT * FROM guardian_os_control"),
      "journalWrites"->query(c,"SELECT * FROM guardian_os_journal_writes ORDER BY sequence_number"),
      "database"->query(c,s"SELECT p.ID,p.STATE,p.INFO,a.ATTR_VALUE AS owner,r.ATTR_VALUE AS role,t.trx_id,t.trx_state FROM information_schema.processlist p LEFT JOIN performance_schema.session_connect_attrs a ON a.PROCESSLIST_ID=p.ID AND a.ATTR_NAME='pekko_owner' LEFT JOIN performance_schema.session_connect_attrs r ON r.PROCESSLIST_ID=p.ID AND r.ATTR_NAME='pekko_role' LEFT JOIN information_schema.innodb_trx t ON t.trx_mysql_thread_id=p.ID WHERE p.DB='$schema' AND p.ID<>CONNECTION_ID() ORDER BY p.ID"))}
    def mark(k:String):Unit={Files.writeString(dir.resolve(k),"observed");emit(k)}
    def snapshot(name:String):Unit={require(name.matches("[A-Za-z0-9-]+"));write(dir,name+".json",capture);mark(name+"-ready")}
    def admit():Unit=withConnection(s,schema){c=>
      c.setAutoCommit(false)
      try {
        query(c,"SELECT queue_sequence FROM pekko_table_admission_lock WHERE id=1 FOR UPDATE")
        require(query(c,"SELECT boot FROM guardian_os_tickets WHERE phase<>'retired'").elements.isEmpty,"old worker quota remains charged")
        executeSql(c,s"INSERT INTO guardian_os_tickets VALUES ('$newBoot','new','prepared',NULL,NULL,NULL)");c.commit()
      }catch{case e:Exception=>c.rollback();throw e}
    }
    @volatile var recovery=Option.empty[Future[Unit]]
    def recover():Unit=try {
      // A replacement JVM's fresh manager demonstrably cannot recover the old Java Process object.
      val original=db.ha.owner(oldBoot).map(_.identity)
      original.foreach(id=>require(new ManagedJvmIsolation("takeover-experiment",1).confirm(id,"not-a-proof",false).isEmpty))
      write(dir,"lost-process-capability.json",JsObject("replacementSupervisorPid"->JsNumber(ProcessHandle.current().pid()),
        "registeredOldOwner"->JsBoolean(original.nonEmpty),"freshManagerHasOriginalProcess"->JsBoolean(false)))
      val binding=Files.readString(dir.resolve("binding.json")).parseJson.asJsObject
      val raw=Files.readString(dir.resolve("proof.json"));val pin=pinnedKey
      GuardianOsProbeProof.record(db.store,oldBoot,raw,pin,binding,"isolate-"+oldBoot)
      snapshot("after-proof")
      val observer=new MySqlOwnerDrainObserver(db.ha,key=>{
        val port=key.split(":").last.toInt
        require(port==s.port || proxy.exists(_.port==port),"unregistered observer endpoint")
        DriverManager.getConnection(s.copy(port=port).url(schema)+"&connectionAttributes=pekko_owner:observer,pekko_role:observer",s.user,s.password)
      })
      if(db.ha.owner(oldBoot).nonEmpty)awaitCondition("original database manifest drained",75.seconds){
        db.ha.instances(oldBoot).foreach { instance=>
          val observation=observer.observe(oldBoot,instance)
          Files.writeString(dir.resolve("observations.jsonl"),JsObject("complete"->JsBoolean(observation.complete),"empty"->JsBoolean(observation.empty),"evidence"->observation.evidence.parseJson).compactPrint+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND)
          db.ha.recordObservation(oldBoot,observation,250)
          if(!observation.empty)mark("DRAIN_WAIT")
        }
        db.ha.owner(oldBoot).exists(_.phase=="db_drained")
      }
      snapshot("drained-before-quota")
      db.ha.releaseDrained(oldBoot,16);require(!db.ha.hasOutstandingQuota(oldBoot))
      withConnection(s,schema)(executeSql(_,s"UPDATE guardian_os_tickets SET phase='retired' WHERE boot='$oldBoot'"))
      snapshot("after-quota");admit();Files.writeString(dir.resolve("launch-new"),"authorized after old retirement")
      awaitCondition("same execution completed",90.seconds)(db.store.get("table-00").exists(_.state=="completed"))
      require(db.store.get("table-00").get.executionId.contains(execution))
      val end=capture;require(end.fields("data").asJsObject.fields("comparison").asJsObject.fields("equal")==JsBoolean(true))
      write(dir,"final.json",end);mark("CONVERGED")
    }catch{case NonFatal(e)=>
      val rejected=try {admit();false}catch{case _:IllegalArgumentException=>true}
      write(dir,"pause.json",JsObject("errorClass"->JsString(e.getClass.getName),"message"->JsString(Option(e.getMessage).getOrElse("").replace(s.password,"<redacted>")),"admissionRejected"->JsBoolean(rejected),"state"->capture))
      mark("SAFE_PAUSE")
    }
    mark("SUPERVISOR_READY")
    try scala.io.Source.stdin.getLines().takeWhile(_!="QUIT").foreach {
      case command if command.startsWith("STATE ")=>snapshot(command.stripPrefix("STATE "))
      case "SEAL"=>withConnection(s,schema){c=>executeSql(c,"UPDATE guardian_os_control SET epoch=2,phase='recovering' WHERE id=1");executeSql(c,s"UPDATE guardian_os_tickets SET phase='sealed' WHERE boot='$oldBoot'")};mark("SEALED")
      case "ARM_JOURNAL"=>executeSql(holder,"UPDATE owner_probe_journal_gate SET armed=true WHERE id=1");Files.writeString(dir.resolve(oldBoot+".commands"),"RELEASE\n",StandardOpenOption.APPEND)
      case "RELEASE_RELAY"=>executeSql(holder,s"DO RELEASE_LOCK('$gate')");proxy.foreach(_.releaseHeld());mark("RELAY_RELEASED")
      case "RECOVER"=>require(recovery.isEmpty);recovery=Some(Future(recover()))
      case other=>throw new IllegalArgumentException("unknown probe command "+other)
    }finally {
      recovery.foreach(f=>Await.ready(f,100.seconds));proxy.foreach(_.close());holder.close();db.close()
    }
  }
}
