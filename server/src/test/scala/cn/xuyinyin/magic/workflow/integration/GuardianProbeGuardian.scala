package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.workflow.scheduling._
import spray.json._
import spray.json.DefaultJsonProtocol._
import java.net.{StandardProtocolFamily,UnixDomainSocketAddress}
import java.nio.channels.ServerSocketChannel
import java.nio.file.{Files,Path}
import java.security.KeyPairGenerator
import java.util.{Base64,UUID}
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.control.NonFatal

/** Laboratory guardian. It alone creates and retains worker Process objects; no PID-based adoption. */
object GuardianProbeGuardian {
  import GuardianProbeWire._
  import CdcJournalWindowSupport.{emit,awaitCondition}
  def main(args:Array[String]):Unit={
    require(args.length==3);val Array(schema,privateDirectory,caseDirectory)=args
    val anchor=Path.of(privateDirectory);val dir=Path.of(caseDirectory)
    val db=new GuardianProbeStore(schema,"probe-guardian")
    val observer=new MySqlOwnerDrainObserver(db.ha,_=>java.sql.DriverManager.getConnection(db.settings.url(schema)+"&connectionAttributes=pekko_owner:observer",db.settings.user,db.settings.password))
    var manager=new ManagedJvmIsolation("takeover-experiment",1)
    var coordinator=new SnapshotTakeoverCoordinator(db.ha,manager,observer.observe,100)
    val guardian=UUID.randomUUID().toString;val keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val secret=Files.readAllBytes(anchor.resolve("auth.key"))
    val identities=mutable.Map.empty[String,SnapshotOwnerIdentity]
    val closed=mutable.Set.empty[String];val receipts=mutable.Map.empty[(String,String),SnapshotIsolationReceipt]
    val nonces=mutable.Set.empty[String];var epoch=0L;var controller="";var running=true
    val server=ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    server.bind(UnixDomainSocketAddress.of(anchor.resolve("rpc.sock")))
    Files.writeString(anchor.resolve("pin.json"),JsObject("guardian"->JsString(guardian),"publicKey"->JsString(Base64.getEncoder.encodeToString(keys.getPublic.getEncoded)),
      "pid"->JsNumber(ProcessHandle.current().pid()),"start"->JsString(ProcessHandle.current().info().startInstant().orElseThrow().toString)).prettyPrint)
    emit("GUARDIAN_READY","guardian"->JsString(guardian))
    def inventory:JsObject=JsObject(identities.toVector.map { case(boot,id)=>
      val child=manager.managed.find(_.identity==id)
      boot->JsObject("identity"->id.toJson,"status"->JsString(child.fold("UNKNOWN")(c=>if(c.process.isAlive)"ALIVE" else "EXITED")))
    }.toMap)
    def handle(body:JsObject):JsObject={
      val method=text(body,"method");val requested=number(body,"epoch");val who=text(body,"controller");val a=body.fields("args").asJsObject
      if(method=="BIND") {
        require(requested>epoch || (requested==epoch && who==controller),"stale controller")
        epoch=requested;controller=who;return JsObject("epoch"->JsNumber(epoch))
      }
      require(requested==epoch && who==controller,"stale controller")
      method match {
        case "INVENTORY"=>inventory
        case "CANCEL"=>
          val boot=text(a,"boot");require(!identities.contains(boot),"a launched worker cannot be cancelled as unstarted")
          closed+=boot;JsObject("closedWithoutLaunch"->JsBoolean(true))
        case "LAUNCH"=>
          val boot=text(a,"boot");require(!closed(boot),"launch is tombstoned")
          if(!identities.contains(boot)) {
            require(db.launches.elements.exists(r=>r.asJsObject.fields("boot")==JsString(boot) &&
              r.asJsObject.fields("phase")==JsString("prepared") && r.asJsObject.fields("guardian")==JsString(guardian)),"no matching durable launch intent")
            if(a.fields.get("beforeFork").contains(JsBoolean(true))) {
              emit("BEFORE_FORK","boot"->JsString(boot));awaitCondition("test before-fork gate",30.seconds)(Files.exists(dir.resolve("fork.release")))
            }
            Files.writeString(dir.resolve(boot+".commands"),"")
            val builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx512m","-Duser.timezone=UTC","-cp",System.getProperty("java.class.path"),
              GuardianProbeWorker.getClass.getName.stripSuffix("$"),schema,boot,dir.toString,a.fields.getOrElse("preBootstrap",JsBoolean(false)).convertTo[Boolean].toString)
            builder.redirectErrorStream(true);builder.redirectOutput(dir.resolve(boot+".worker.log").toFile)
            builder.environment().put("RUN_MYSQL_SNAPSHOT_TAKEOVER","1")
            val child=manager.launch(boot,builder);identities.update(boot,child.identity)
            Files.writeString(dir.resolve(boot+".identity.json"),child.identity.toJson.prettyPrint)
            emit("LAUNCH_CREATED","boot"->JsString(boot),"identity"->child.identity.toJson)
            if(a.fields.get("holdReply").contains(JsBoolean(true)))awaitCondition("test launch reply gate",30.seconds)(Files.exists(dir.resolve("reply.release")))
          }
          JsObject("identity"->identities(boot).toJson)
        case "STOP"=>
          val id=a.fields("identity").convertTo[SnapshotOwnerIdentity];val operation=text(a,"operation")
          require(identities.get(id.bootId).contains(id),"launch identity mismatch")
          val receipt=receipts.get((id.bootId,operation)).orElse(manager.confirm(id,operation,terminate=true).map { r=>
            receipts.update((id.bootId,operation),r);r
          })
          JsObject("receipt"->receipt.map(_.toJson).getOrElse(JsNull))
        case "TICK"=>coordinator.tick();JsObject("managed"->JsNumber(manager.managed.size))
        case "LOSE_CAPABILITY"=>
          manager=new ManagedJvmIsolation("takeover-experiment",1)
          coordinator=new SnapshotTakeoverCoordinator(db.ha,manager,observer.observe,100)
          JsObject("capabilityLost"->JsBoolean(true))
        case "QUIT_TEST"=>running=false;JsObject("stopped"->JsBoolean(true))
        case _=>throw new IllegalArgumentException("unknown laboratory command")
      }
    }
    try while(running){
      val c=server.accept();var nonce="unparsed"
      try {
        val envelope=read(c).parseJson.asJsObject;val raw=text(envelope,"payload");val body=raw.parseJson.asJsObject;nonce=text(body,"nonce")
        val result=try {
          require(authenticated(raw,text(envelope,"mac"),secret),"authentication failed")
          require(!nonces(nonce) && nonces.size<256,"replayed nonce or bounded nonce inventory exhausted");nonces+=nonce
          JsObject("ok"->JsBoolean(true),"result"->handle(body))
        }catch{case NonFatal(e)=>JsObject("ok"->JsBoolean(false),"error"->JsString(e.getClass.getSimpleName))}
        val response=sign(JsObject(result.fields++Map("nonce"->JsString(nonce),"guardian"->JsString(guardian))),keys.getPrivate)
        append(dir.resolve("guardian-wire.jsonl"),JsObject("request"->envelope,"response"->response))
        write(c,response)
      }catch{case NonFatal(e)=>emit("RPC_CONNECTION_ENDED","error"->JsString(e.getClass.getSimpleName))}finally c.close()
    } finally {server.close();db.close()}
  }
}
