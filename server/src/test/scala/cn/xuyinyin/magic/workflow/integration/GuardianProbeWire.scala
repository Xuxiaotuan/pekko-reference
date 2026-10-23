package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.workflow.scheduling._
import spray.json._
import spray.json.DefaultJsonProtocol._
import java.net.{StandardProtocolFamily,UnixDomainSocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.{Files,Path}
import java.security.{KeyFactory,MessageDigest,PrivateKey,PublicKey,Signature}
import java.security.spec.X509EncodedKeySpec
import java.util.{Base64,UUID}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Test-only authenticated local transport. Production actor messages and serializers are untouched. */
private[integration] object GuardianProbeWire {
  implicit val identityFormat:RootJsonFormat[SnapshotOwnerIdentity]=jsonFormat4(SnapshotOwnerIdentity.apply)
  implicit val receiptFormat:RootJsonFormat[SnapshotIsolationReceipt]=jsonFormat5(SnapshotIsolationReceipt.apply)
  def text(v:JsObject,k:String):String=v.fields(k).convertTo[String]
  def number(v:JsObject,k:String):Long=v.fields(k).convertTo[Long]
  def jsonFile(p:Path):JsObject=Files.readString(p).parseJson.asJsObject
  def append(p:Path,v:JsValue):Unit=synchronized {
    Files.writeString(p,v.compactPrint+"\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);()
  }
  def hmac(key:Array[Byte],text:String):String={
    val mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"))
    Base64.getEncoder.encodeToString(mac.doFinal(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
  }
  def authenticated(raw:String,mac:String,key:Array[Byte]):Boolean=
    MessageDigest.isEqual(hmac(key,raw).getBytes(java.nio.charset.StandardCharsets.UTF_8),mac.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  def publicKey(v:String):PublicKey=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder.decode(v)))
  def sign(v:JsObject,key:PrivateKey):JsObject={
    val raw=v.compactPrint;val s=Signature.getInstance("Ed25519");s.initSign(key);s.update(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    JsObject("payload"->JsString(raw),"signature"->JsString(Base64.getEncoder.encodeToString(s.sign())))
  }
  def verified(envelope:JsObject,key:PublicKey,nonce:String,guardian:String):JsObject={
    val raw=text(envelope,"payload");val s=Signature.getInstance("Ed25519");s.initVerify(key);s.update(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    require(s.verify(Base64.getDecoder.decode(text(envelope,"signature"))),"guardian signature mismatch")
    val value=raw.parseJson.asJsObject
    require(text(value,"nonce")==nonce && text(value,"guardian")==guardian,"guardian or nonce mismatch")
    value
  }
  private def poll(deadline:Long):Unit={require(System.nanoTime()<deadline,"bounded guardian RPC timed out");Thread.sleep(5)}
  def read(c:SocketChannel,seconds:Int=8):String={
    val out=new java.io.ByteArrayOutputStream();val buffer=ByteBuffer.allocate(4096);val deadline=System.nanoTime()+seconds.toLong*1000000000L
    c.configureBlocking(false)
    while(out.size()<131072){
      buffer.clear();val count=c.read(buffer)
      require(count>=0,"guardian RPC ended before reply")
      if(count==0)poll(deadline) else {
        buffer.flip();while(buffer.hasRemaining){val b=buffer.get();if(b=='\n'.toByte)return out.toString("UTF-8");out.write(b.toInt)}
      }
    };throw new IllegalArgumentException("guardian frame too large")
  }
  def write(c:SocketChannel,value:JsObject):Unit={
    val data=ByteBuffer.wrap((value.compactPrint+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));val deadline=System.nanoTime()+8000000000L
    c.configureBlocking(false);while(data.hasRemaining)if(c.write(data)==0)poll(deadline)
  }
  final class Client(anchor:Path,out:Path,controller:String,val epoch:Long) {
    private val pin=jsonFile(anchor.resolve("pin.json"))
    val guardian=text(pin,"guardian")
    private val key=publicKey(text(pin,"publicKey"))
    private val secret=Files.readAllBytes(anchor.resolve("auth.key"))
    def replay(request:JsObject):JsObject={
      val body=text(request,"payload").parseJson.asJsObject
      exchange(request,text(body,"nonce"),8)
    }
    private def exchange(request:JsObject,nonce:String,seconds:Int):JsObject={
      val c=SocketChannel.open(StandardProtocolFamily.UNIX)
      try {
        c.connect(UnixDomainSocketAddress.of(anchor.resolve("rpc.sock")));write(c,request)
        val response=read(c,seconds).parseJson.asJsObject
        append(out,JsObject("request"->request,"response"->response))
        verified(response,key,nonce,guardian)
      }finally c.close()
    }
    def request(method:String,args:JsObject=JsObject.empty,overrideMac:Option[String]=None):JsObject={
      val nonce=UUID.randomUUID().toString
      val body=JsObject("method"->JsString(method),"args"->args,"nonce"->JsString(nonce),"controller"->JsString(controller),"epoch"->JsNumber(epoch))
      val raw=body.compactPrint;val request=JsObject("payload"->JsString(raw),"mac"->JsString(overrideMac.getOrElse(hmac(secret,raw))))
      val value=exchange(request,nonce,if(method=="LAUNCH")45 else 8)
      require(value.fields("ok")==JsBoolean(true),"guardian rejected: "+value.fields.getOrElse("error",JsNull))
      value.fields("result").asJsObject
    }
  }
}
