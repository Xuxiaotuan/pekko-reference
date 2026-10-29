package cn.xuyinyin.magic.workflow.scheduling

import java.net.{StandardProtocolFamily,UnixDomainSocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.{ServerSocketChannel,SocketChannel}
import java.nio.file.Path
import java.security.{KeyFactory,MessageDigest,PrivateKey,SecureRandom,Signature}
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import spray.json._
import spray.json.DefaultJsonProtocol._
import scala.util.control.NonFatal

/** Local authenticated transport. No Java serialization and no credentials in response/audit frames. */
object SnapshotGuardianWire {
  implicit val bindingFormat:RootJsonFormat[GuardianBinding]=jsonFormat4(GuardianBinding.apply)
  implicit val controlFormat:RootJsonFormat[SupervisorControl]=jsonFormat3(SupervisorControl.apply)
  implicit val identityFormat:RootJsonFormat[SnapshotOwnerIdentity]=jsonFormat4(SnapshotOwnerIdentity.apply)
  implicit val receiptFormat:RootJsonFormat[SnapshotIsolationReceipt]=jsonFormat5(SnapshotIsolationReceipt.apply)
  def text(v:JsObject,k:String):String=v.fields(k).convertTo[String]
  def sign(value:JsObject,key:PrivateKey):JsObject={
    val raw=value.compactPrint;val s=Signature.getInstance("Ed25519");s.initSign(key);s.update(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    JsObject("payload"->JsString(raw),"signature"->JsString(Base64.getEncoder.encodeToString(s.sign())))
  }
  def verified(value:JsObject,publicKey:String):JsObject={
    val key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder.decode(publicKey)))
    val s=Signature.getInstance("Ed25519");s.initVerify(key);s.update(text(value,"payload").getBytes(java.nio.charset.StandardCharsets.UTF_8))
    require(s.verify(Base64.getDecoder.decode(text(value,"signature"))),"guardian signature mismatch")
    text(value,"payload").parseJson.asJsObject
  }
  def verifyReceipt(raw:String,binding:GuardianBinding,id:SnapshotOwnerIdentity,operation:String):SnapshotIsolationReceipt={
    val v=verified(raw.parseJson.asJsObject,binding.publicKey)
    require(v.fields("guardian").convertTo[GuardianBinding]==binding,"guardian proof identity mismatch")
    val r=v.fields("receipt").convertTo[SnapshotIsolationReceipt]
    require(r.identity==id && r.operationId==operation && r.provider=="managed-child-jvm" && r.details.parseJson.asJsObject.fields("alive")==JsBoolean(false),"original child exit proof mismatch")
    r
  }
  private def mac(raw:String,key:Array[Byte]):Array[Byte]={val m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));m.doFinal(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))}
  private def progress(deadline:Long):Unit={require(System.nanoTime()<deadline,"guardian RPC deadline exceeded");Thread.sleep(5)}
  private def read(c:SocketChannel):String={
    c.configureBlocking(false);val end=System.nanoTime()+15000000000L;val out=new java.io.ByteArrayOutputStream;val b=ByteBuffer.allocate(4096)
    while(out.size()<1048576){b.clear();val n=c.read(b);require(n>=0,"guardian connection ended");if(n==0)progress(end)else{
      b.flip();while(b.hasRemaining){val value=b.get();if(value=='\n'.toByte)return out.toString("UTF-8");out.write(value.toInt)}
    }};throw new IllegalArgumentException("guardian frame too large")
  }
  private def write(c:SocketChannel,v:JsObject):Unit={
    c.configureBlocking(false);val end=System.nanoTime()+15000000000L;val b=ByteBuffer.wrap((v.compactPrint+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))
    while(b.hasRemaining)if(c.write(b)==0)progress(end)
  }
  final class Client(socket:Path,secret:Array[Byte],val control:SupervisorControl,audit:JsObject=>Unit=_=>()) {
    require(secret.length>=32)
    private var sequence=0L
    def request(method:String,args:JsObject=JsObject.empty):JsObject=synchronized {
      sequence=Math.addExact(sequence,1L)
      val request=JsObject("control"->control.toJson,"sequence"->JsNumber(sequence),"method"->JsString(method),"args"->args)
      val raw=request.compactPrint;val envelope=JsObject("payload"->JsString(raw),"mac"->JsString(Base64.getEncoder.encodeToString(mac(raw,secret))))
      val channel=SocketChannel.open(StandardProtocolFamily.UNIX)
      try {
        channel.configureBlocking(false);channel.connect(UnixDomainSocketAddress.of(socket));val end=System.nanoTime()+5000000000L
        while(!channel.finishConnect())progress(end)
        write(channel,envelope);val response=read(channel).parseJson.asJsObject
        audit(JsObject("request"->envelope,"response"->response))
        val body=verified(response,control.guardian.publicKey)
        require(body.fields("request")==request && body.fields("guardian").convertTo[GuardianBinding]==control.guardian,"guardian response binding mismatch")
        require(body.fields("ok")==JsBoolean(true),"guardian rejected request")
        body.fields("result").asJsObject
      }finally channel.close()
    }
  }
  /** Worker challenges use a fresh nonce and cannot affect supervisor control sequencing. */
  final class WorkerClient(socket:Path,secret:Array[Byte],binding:GuardianBinding) {
    require(secret.length>=32)
    private val random=new SecureRandom()
    def attest(identity:SnapshotOwnerIdentity,profile:String):Unit={
      val bytes=new Array[Byte](32);random.nextBytes(bytes)
      val nonce=Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
      val args=JsObject("nonce"->JsString(nonce),"identity"->identity.toJson,"profile"->JsString(profile))
      val request=JsObject("method"->JsString("ATTEST_WORKER"),"args"->args)
      val raw=request.compactPrint
      val envelope=JsObject("payload"->JsString(raw),"mac"->JsString(Base64.getEncoder.encodeToString(mac(raw,secret))))
      val channel=SocketChannel.open(StandardProtocolFamily.UNIX)
      try {
        channel.configureBlocking(false);channel.connect(UnixDomainSocketAddress.of(socket));val end=System.nanoTime()+5000000000L
        while(!channel.finishConnect())progress(end)
        write(channel,envelope)
        val response=verified(read(channel).parseJson.asJsObject,binding.publicKey)
        require(response.fields("request")==request && response.fields("guardian").convertTo[GuardianBinding]==binding,"worker challenge binding mismatch")
        require(response.fields("ok")==JsBoolean(true),"guardian rejected worker challenge")
        val result=response.fields("result").asJsObject.fields
        require(result.get("nonce").contains(JsString(nonce)) && result.get("identity").contains(identity.toJson) &&
          result.get("profile").contains(JsString(profile)) && result.get("provider").contains(JsString("managed-child-jvm")) &&
          result.get("alive").contains(JsBoolean(true)),"worker challenge proof mismatch")
      } finally channel.close()
    }
  }
  /** Serial processing makes cancellation and in-flight fork reconciliation mutually exclusive. */
  final class Server(socket:Path,secret:Array[Byte],binding:GuardianBinding,key:PrivateKey,
    handle:(SupervisorControl,String,JsObject)=>JsObject,audit:JsObject=>Unit=_=>(),
    attest:JsObject=>JsObject=_=>throw new IllegalStateException("worker attestation unavailable")) extends AutoCloseable {
    require(secret.length>=32)
    private val server=ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    server.bind(UnixDomainSocketAddress.of(socket))
    @volatile private var open=true
    private var lastControl=Option.empty[SupervisorControl];private var lastSequence=0L
    def run():Unit=while(open){
      val c=server.accept()
      try {
        val envelope=read(c).parseJson.asJsObject;val raw=text(envelope,"payload");val request=raw.parseJson.asJsObject
        val result=try {
          require(MessageDigest.isEqual(mac(raw,secret),Base64.getDecoder.decode(text(envelope,"mac"))),"guardian authentication failed")
          val method=text(request,"method")
          val value=if(method=="ATTEST_WORKER") {
            require(request.fields.keySet==Set("method","args"),"invalid worker challenge envelope")
            attest(request.fields("args").asJsObject)
          }else {
            val control=request.fields("control").convertTo[SupervisorControl];val seq=request.fields("sequence").convertTo[Long]
            require(control.guardian==binding && seq>0,"guardian binding mismatch")
            if(lastControl.contains(control))require(seq>lastSequence,"replayed request")
            else require(method=="BIND" && lastControl.forall(_.epoch<control.epoch),"controller binding required")
            val value=handle(control,method,request.fields("args").asJsObject)
            lastControl=Some(control);lastSequence=seq;value
          }
          JsObject("ok"->JsBoolean(true),"result"->value)
        }catch{case NonFatal(e)=>JsObject("ok"->JsBoolean(false),"errorClass"->JsString(e.getClass.getName))}
        val response=sign(JsObject(result.fields++Map("request"->request,"guardian"->binding.toJson)),key)
        audit(JsObject("request"->envelope,"response"->response));write(c,response)
      }catch{case NonFatal(e)=>org.slf4j.LoggerFactory.getLogger(getClass).warn("Guardian RPC failed ({})",e.getClass.getName)}finally c.close()
    }
    def close():Unit={open=false;server.close()}
  }
}
