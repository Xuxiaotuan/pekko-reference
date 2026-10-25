package cn.xuyinyin.magic.workflow.scheduling

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import spray.json._
import java.security.KeyPairGenerator
import java.util.Base64

class GuardianOsProbeProofSpec extends AnyWordSpec with Matchers {
  private val keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
  private val pin=Base64.getEncoder.encodeToString(keys.getPublic.getEncoded)
  private val binding=JsObject("guardian"->JsString("old-guardian"),"unit"->JsString("pekko-osprobe-owned.service"),
    "invocation"->JsString("0123456789abcdef0123456789abcdef"),"cgroup"->JsString("/system.slice/pekko-osprobe-owned.service"),"hostBoot"->JsString("host-boot"))
  private val body=JsObject("provider"->JsString("test-systemd-cgroup-v2"),"binding"->binding,"operation"->JsString("stable-op"),
    "startSealed"->JsBoolean(true),"stopCompleted"->JsBoolean(true),"subtreeEmpty"->JsBoolean(true),"beforeMatched"->JsBoolean(true))
  private def raw(v:JsObject)=SnapshotGuardianWire.sign(v,keys.getPrivate).compactPrint
  "laboratory OS isolation proof" should {
    "accept an authenticated bound stop observation without an original Process" in {
      GuardianOsProbeProof.verify(raw(body),pin,binding,"stable-op") shouldBe body
    }
    "reject successful signal delivery when the subtree is still populated" in {
      intercept[IllegalArgumentException](GuardianOsProbeProof.verify(raw(JsObject(body.fields.updated("subtreeEmpty",JsBoolean(false)))),pin,binding,"stable-op"))
    }
    "reject a missing original unit binding even when no process is found" in {
      intercept[IllegalArgumentException](GuardianOsProbeProof.verify(raw(JsObject(body.fields.updated("beforeMatched",JsBoolean(false)))),pin,binding,"stable-op"))
    }
    "reject a still-open start path" in {
      intercept[IllegalArgumentException](GuardianOsProbeProof.verify(raw(JsObject(body.fields.updated("startSealed",JsBoolean(false)))),pin,binding,"stable-op"))
    }
    "reject wrong incarnation and operation" in {
      intercept[IllegalArgumentException](GuardianOsProbeProof.verify(raw(body),pin,JsObject(binding.fields.updated("invocation",JsString("other"))),"stable-op"))
      intercept[IllegalArgumentException](GuardianOsProbeProof.verify(raw(body),pin,binding,"other-op"))
    }
    "reject a changed signed payload" in {
      val envelope=raw(body).parseJson.asJsObject
      intercept[IllegalArgumentException](GuardianOsProbeProof.verify(JsObject(envelope.fields.updated("payload",JsString("{}"))).compactPrint,pin,binding,"stable-op"))
    }
  }
}
