package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.workflow.scheduling._
import spray.json._
import java.nio.channels.FileChannel
import java.nio.file.{Files,Path,StandardOpenOption}
import java.util.UUID
import scala.util.control.NonFatal
import scala.concurrent.duration._

/** Test-only controller. Relays authenticated original-child receipts, not a production guardian provider. */
object GuardianProbeSupervisor {
  import GuardianProbeWire.{write => _,_}
  import CdcJournalWindowSupport._
  def main(args:Array[String]):Unit={
    require(args.length==5);val Array(schema,privateDirectory,caseDirectory,mode,scenario)=args
    val dir=Path.of(caseDirectory);val anchor=Path.of(privateDirectory);val controller=UUID.randomUUID().toString
    val channel=FileChannel.open(anchor.resolve("supervisor.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)
    val lock=channel.tryLock()
    if(lock==null){emit("LOCK_BLOCKED");channel.close();return}
    val db=new GuardianProbeStore(schema,"probe-supervisor")
    try {
      val epoch=db.epoch(controller);val client=new Client(anchor,dir.resolve(mode+"-wire.jsonl"),controller,epoch)
      client.request("BIND");emit("CONTROLLER_BOUND","epoch"->JsNumber(epoch),"controller"->JsString(controller))
      if(mode=="A") {
        val boot=UUID.randomUUID().toString;db.prepare(boot,epoch)
        Files.writeString(dir.resolve("old-boot"),boot);emit("PREPARED","boot"->JsString(boot))
        if(scenario=="S2_PREPARED")awaitCondition("test prepared intent gate",30.seconds)(Files.exists(dir.resolve("prepared.release")))
        val launched=client.request("LAUNCH",JsObject("boot"->JsString(boot),"beforeFork"->JsBoolean(scenario=="S2_FORK_PENDING"),
          "holdReply"->JsBoolean(scenario=="S3_ACK_LOST"),"preBootstrap"->JsBoolean(scenario=="S4_PRE_BOOTSTRAP")))
        db.phase(boot,"launched");emit("SUPERVISOR_READY","identity"->launched.fields("identity"))
        awaitCondition("test controller exit gate",240.seconds)(Files.exists(dir.resolve("A.stop")))
      } else {
        val inventory=client.request("INVENTORY");write(dir,"reconstructed.json",inventory)
        val entries=db.launches.elements.map(_.asJsObject).filter(_.fields("phase")!=JsString("retired"))
        entries.foreach { entry=>
          val boot=text(entry,"boot")
              require(text(entry,"guardian")==client.guardian,"guardian incarnation changed")
          inventory.fields.get(boot) match {
            case None=>
              client.request("CANCEL",JsObject("boot"->JsString(boot)));db.phase(boot,"retired")
              emit("UNSTARTED_CLOSED","boot"->JsString(boot))
            case Some(value)=>
              val record=value.asJsObject;require(text(record,"status")!="UNKNOWN","guardian process capability is unavailable")
              val identity=record.fields("identity").convertTo[SnapshotOwnerIdentity]
              require(identity.bootId==boot,"reconstructed launch mismatch")
              db.ha.owner(boot).foreach(o=>require(o.identity==identity,"SQL owner identity mismatch"))
              val operation=db.ha.owner(boot).map(_=>db.ha.beginRevocation(boot,UUID.randomUUID().toString)).getOrElse(UUID.randomUUID().toString)
              val result=client.request("STOP",JsObject("identity"->identity.toJson,"operation"->JsString(operation)))
              require(result.fields("receipt")!=JsNull,"guardian exit is unconfirmed")
              val receipt=result.fields("receipt").convertTo[SnapshotIsolationReceipt]
              require(receipt.identity==identity && receipt.operationId==operation,"receipt binding mismatch")
              write(dir,"exit-receipt.json",receipt.toJson)
              if(db.ha.owner(boot).isEmpty) {
                require(db.ha.recordFailedStart(receipt),"startup identity changed; refuse laboratory shortcut")
              } else {
                require(db.ha.confirmIsolation(receipt),"production isolation recording rejected")
                write(dir,"after-proof-recorded.json",db.capture)
                val observer=new MySqlOwnerDrainObserver(db.ha,_=>java.sql.DriverManager.getConnection(db.settings.url(schema)+"&connectionAttributes=pekko_owner:observer",db.settings.user,db.settings.password))
                awaitCondition("all original worker resources drained",30.seconds) {
                  db.ha.instances(boot).foreach { i=>
                    val observation=observer.observe(boot,i)
                    append(dir.resolve("observations.jsonl"),observation.evidence.parseJson)
                    db.ha.recordObservation(boot,observation,100)
                  }
                  db.ha.owner(boot).exists(_.phase=="db_drained")
                }
                write(dir,"drained-before-quota-return.json",db.capture)
                db.ha.releaseDrained(boot,16)
                require(!db.ha.hasOutstandingQuota(boot),"old quota still charged")
                write(dir,"after-quota-return.json",db.capture)
              }
              client.request("TICK");db.phase(boot,"retired")
          }
        }
        val boot=UUID.randomUUID().toString;db.prepare(boot,epoch)
        val fresh=client.request("LAUNCH",JsObject("boot"->JsString(boot)))
        db.phase(boot,"launched");emit("RECOVERY_WORKER_CREATED","identity"->fresh.fields("identity"))
        // All workers use the same real reader gate. This only releases the laboratory timing gate.
        awaitCondition("replacement reader reached the real source gate",45.seconds) {
          val f=dir.resolve(boot+".worker.log");Files.exists(f) && Files.readString(f).contains("\"kind\":\"CUTPOINT\"")
        }
        Files.writeString(dir.resolve(boot+".commands"),"RELEASE\n",StandardOpenOption.APPEND)
        awaitCondition("original execution completed",60.seconds)(db.store.get("table-00").exists(_.state=="completed"))
        write(dir,"supervisor-final.json",db.capture);emit("RECOVERY_COMPLETED")
      }
    }catch{case NonFatal(e)=>
      write(dir,mode+"-pause.json",JsObject("safePause"->JsBoolean(true),"errorClass"->JsString(e.getClass.getName),
        "message"->JsString(Option(e.getMessage).getOrElse("").replace(db.settings.password,"<redacted>")),"state"->db.capture))
      emit("SAFE_PAUSE","error"->JsString(e.getClass.getSimpleName))
    } finally {db.close();lock.release();channel.close()}
  }
}
