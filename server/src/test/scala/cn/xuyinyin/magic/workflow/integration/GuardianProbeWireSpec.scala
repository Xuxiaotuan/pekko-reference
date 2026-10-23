package cn.xuyinyin.magic.workflow.integration

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import spray.json._
import java.security.KeyPairGenerator
import cn.xuyinyin.magic.workflow.scheduling.ManagedJvmIsolation

class GuardianProbeWireSpec extends AnyWordSpec with Matchers {
  "guardian probe trust boundary" should {
    "reject altered signatures and responses bound to another key, nonce or guardian" in {
      import GuardianProbeWire._
      val key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      val response=sign(JsObject("nonce"->JsString("nonce-1"),"guardian"->JsString("guardian-1"),"ok"->JsBoolean(true)),key.getPrivate)
      verified(response,key.getPublic,"nonce-1","guardian-1").fields("ok") shouldBe JsBoolean(true)
      intercept[IllegalArgumentException](verified(response,key.getPublic,"nonce-2","guardian-1"))
      intercept[IllegalArgumentException](verified(response,key.getPublic,"nonce-1","guardian-2"))
      val other=KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      intercept[IllegalArgumentException](verified(response,other.getPublic,"nonce-1","guardian-1"))
      val changed=JsObject(response.fields.updated("payload",JsString(text(response,"payload").replace("true","false"))))
      intercept[IllegalArgumentException](verified(changed,key.getPublic,"nonce-1","guardian-1"))
      authenticated("request",hmac(Array[Byte](1,2,3),"request"),Array[Byte](1,2,3)) shouldBe true
      authenticated("altered",hmac(Array[Byte](1,2,3),"request"),Array[Byte](1,2,3)) shouldBe false
    }
    "characterize that a fresh production manager cannot adopt the prior manager's live child" in {
      val original=new ManagedJvmIsolation("probe",1)
      val child=original.launch("before-supervisor-death",new ProcessBuilder("/bin/cat"))
      try {
        val replacement=new ManagedJvmIsolation("probe",1)
        replacement.confirm(child.identity,"recovery-operation",terminate=true) shouldBe None
        child.process.isAlive shouldBe true
        original.confirm(child.identity,"cleanup",terminate=true).get.identity shouldBe child.identity
      }finally if(child.process.isAlive)child.process.destroyForcibly().waitFor()
    }
  }
}
