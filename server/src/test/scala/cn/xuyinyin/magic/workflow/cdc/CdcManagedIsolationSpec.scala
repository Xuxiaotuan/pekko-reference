package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.workflow.scheduling._
import java.security.KeyPairGenerator
import java.util.Base64
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

class CdcManagedIsolationSpec extends AnyWordSpec with Matchers {
  "CDC managed isolation proof" should {
    "bind the retired worker, signed guardian proof and all registered accounts" in {
      import SnapshotGuardianWire._
      val keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      val key=Base64.getEncoder.encodeToString(keys.getPublic.getEncoded)
      val binding=GuardianBinding("d","host","guardian",key)
      val id=SnapshotOwnerIdentity("boot","d",123,"start")
      val owner=CdcManagedOwner("boot","d","host","guardian",key,123,"start","profile")
      val receipt=SnapshotIsolationReceipt("op",id,"managed-child-jvm",1,"{\"alive\":false,\"automaticRestart\":false}")
      val signed=sign(JsObject("guardian"->binding.toJson,"receipt"->receipt.toJson),keys.getPrivate).compactPrint
      val ticket=SnapshotWorkerLaunch("boot","profile","retired",Some(id),Some("op"),Some(signed))
      val state=SnapshotOwner(id,"db_drained",Some("op"),2,Some(2),None)
      val manifest="{\"version\":1,\"accounts\":[{\"endpoint\":\"mysql:3306\",\"serverUuid\":\"uuid\",\"user\":\"sdk\"}]}"
      val inventory=Map("mysql:3306"->("uuid",Set("sdk")))
      CdcManagedIsolation.verify(owner,manifest,binding,ticket,state,inventory).operationId shouldBe "op"
      intercept[IllegalArgumentException](CdcManagedIsolation.verify(owner,manifest,binding,ticket,state,Map("mysql:3306"->("uuid",Set("other")))))
      intercept[IllegalArgumentException](CdcManagedIsolation.verify(owner,manifest,binding,ticket,state.copy(phase="drain_wait"),inventory))
      intercept[IllegalArgumentException](CdcManagedIsolation.verify(owner,manifest,binding,ticket.copy(identity=Some(id.copy(startIdentity="other"))),state,inventory))
      intercept[IllegalArgumentException](CdcManagedIsolation.verify(owner,manifest,binding,ticket.copy(proof=Some("{}")),state,inventory))
    }
    sys.env.get("CDC_REAL_GUARDIAN_PROOF").foreach { path=>
      "verify a real retired guardian receipt and its database drain inventory" in {
        val root=java.nio.file.Files.readString(java.nio.file.Path.of(path)).parseJson.asJsObject.fields
        def rows(name:String)=root(name).asInstanceOf[JsArray].elements.map(_.asJsObject.fields)
        def value(row:Map[String,JsValue],key:String)=row(key).asInstanceOf[JsString].value
        val ticket=rows("launches").find(_("phase")==JsString("retired")).get
        val boot=value(ticket,"boot_id");val owner=rows("owners").find(_("owner_id")==JsString(boot)).get
        val guardian=rows("controller").head
        val binding=GuardianBinding(value(guardian,"deployment_id"),value(guardian,"host_id"),value(guardian,"guardian_boot"),value(guardian,"public_key"))
        val id=SnapshotOwnerIdentity(boot,value(ticket,"deployment_id"),value(ticket,"process_id").toLong,value(ticket,"start_identity"))
        val managed=CdcManagedOwner(boot,id.deploymentId,binding.hostId,binding.bootId,binding.publicKey,id.pid,id.startIdentity,value(ticket,"profile_hash"))
        val launch=SnapshotWorkerLaunch(boot,managed.profileHash,"retired",Some(id),Some(value(ticket,"operation_id")),Some(value(ticket,"signed_proof")))
        val state=SnapshotOwner(id,value(owner,"phase"),Some(value(owner,"operation_id")),value(owner,"manifest_version").toLong,Some(value(owner,"sealed_version").toLong),None)
        val instances=rows("instances").filter(_("owner_id")==JsString(boot))
        val inventory=instances.map {r=>value(r,"instance_id")->(value(r,"server_uuid"),value(r,"database_users").parseJson.asInstanceOf[JsArray].elements.map(_.asInstanceOf[JsString].value).toSet)}.toMap
        val entries=inventory.toVector.flatMap {case(endpoint,(uuid,users))=>users.toVector.map(user=>JsObject("endpoint"->JsString(endpoint),"serverUuid"->JsString(uuid),"user"->JsString(user)))}
        val manifest=JsObject("version"->JsNumber(1),"accounts"->JsArray(entries)).compactPrint
        CdcManagedIsolation.verify(managed,manifest,binding,launch,state,inventory).identity shouldBe id
      }
    }
  }
}
