package cn.xuyinyin.magic.workflow.scheduling

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import java.security.KeyPairGenerator
import java.util.Base64
import spray.json._

class SnapshotGuardianSpec extends AnyWordSpec with Matchers {
  "production launch configuration" should {
    "refuse managed launch properties when the worker HA bootstrap is disabled" in {
      val c=com.typesafe.config.ConfigFactory.parseString("pekko.workflow.snapshot-ha.enabled=false\npekko.workflow.snapshot-ha.supervisor-config=\"must-not-be-read\"")
      intercept[IllegalArgumentException](SnapshotOwnerBootstrap.prepare(c))
    }
    "append control attributes to both legal MySQL URL forms without changing the database" in {
      SnapshotSupervisorSettings.appendParameters("jdbc:mysql://host:3306/db","x=1") shouldBe "jdbc:mysql://host:3306/db?x=1"
      SnapshotSupervisorSettings.appendParameters("jdbc:mysql://host:3306/db?a=2","x=1") shouldBe "jdbc:mysql://host:3306/db?a=2&x=1"
      intercept[IllegalArgumentException](SnapshotSupervisorSettings.appendParameters("jdbc:mysql://host/db?connectionAttributes=foreign","x=1"))
    }
    "refuse a worker profile with another deployment or scheduling database" in {
      val controller=com.typesafe.config.ConfigFactory.parseString("""pekko.workflow.snapshot-ha {enabled=true,deployment-id="d"}
        pekko.workflow.table-scheduling {enabled=true,jdbc {url="jdbc:mysql://host/db",username="u",password="p"}}""")
      val otherDeployment=com.typesafe.config.ConfigFactory.parseString("pekko.workflow.snapshot-ha.deployment-id=\"other\"").withFallback(controller)
      val otherDatabase=com.typesafe.config.ConfigFactory.parseString("pekko.workflow.table-scheduling.jdbc.url=\"jdbc:mysql://host/other\"").withFallback(controller)
      SnapshotSupervisorSettings.validateWorker(controller,controller)
      intercept[IllegalArgumentException](SnapshotSupervisorSettings.validateWorker(otherDeployment,controller))
      intercept[IllegalArgumentException](SnapshotSupervisorSettings.validateWorker(otherDatabase,controller))
    }
  }
  "guardian signed evidence" should {
    "reject a modified response or another guardian public key" in {
      val keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      val key=Base64.getEncoder.encodeToString(keys.getPublic.getEncoded)
      val response=SnapshotGuardianWire.sign(JsObject("operation"->JsString("original")),keys.getPrivate)
      SnapshotGuardianWire.verified(response,key).fields("operation") shouldBe JsString("original")
      intercept[IllegalArgumentException](SnapshotGuardianWire.verified(JsObject(response.fields.updated("payload",JsString("{}"))),key))
      val foreign=KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      intercept[IllegalArgumentException](SnapshotGuardianWire.verified(response,Base64.getEncoder.encodeToString(foreign.getPublic.getEncoded)))
    }
    "bind proof to the original worker and operation, not merely a provider string" in {
      val keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();val key=Base64.getEncoder.encodeToString(keys.getPublic.getEncoded)
      val g=GuardianBinding("d","host","g",key);val id=SnapshotOwnerIdentity("w","d",101,"start")
      import SnapshotGuardianWire._
      val receipt=SnapshotIsolationReceipt("op",id,"managed-child-jvm",123,"{\"alive\":false}")
      val signed=sign(JsObject("guardian"->g.toJson,"receipt"->receipt.toJson),keys.getPrivate).compactPrint
      verifyReceipt(signed,g,id,"op") shouldBe receipt
      intercept[IllegalArgumentException](verifyReceipt(signed,g,id.copy(startIdentity="wrong"),"op"))
      intercept[IllegalArgumentException](verifyReceipt(signed,g,id,"wrong"))
    }
  }
}
