package cn.xuyinyin.magic.cdc.control


import cn.xuyinyin.magic.cdc.guardian.SnapshotOwnerBootstrap

import cn.xuyinyin.magic.cdc.guardian.SnapshotSupervisorSettings



import cn.xuyinyin.magic.cdc.guardian.{GuardianBinding, SnapshotOwnerIdentity, SnapshotIsolationReceipt}
import cn.xuyinyin.magic.cdc.guardian.SnapshotGuardianWire
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import java.security.KeyPairGenerator
import java.util.Base64
import spray.json._

class SnapshotGuardianSpec extends AnyWordSpec with Matchers {
  "production launch configuration" should {
    "reject a legacy worker before opening its control connection pool" in {
      val dir=java.nio.file.Files.createTempDirectory("cdc-invalid-worker-")
      val auth=dir.resolve("auth.key");java.nio.file.Files.write(auth,new Array[Byte](32))
      val worker=dir.resolve("worker.conf");java.nio.file.Files.writeString(worker,"pekko.cdc-standalone.managed-worker=false\n")
      val cfg=dir.resolve("supervisor.conf")
      val content=s"""pekko.workflow.snapshot-ha.deployment-id="d"
        pekko.workflow.snapshot-supervisor { host-id="h", guardian-boot="g", public-key="p", max-workers=1,
          socket="${dir.resolve("rpc.sock")}", auth-key-file="$auth", worker-config-file="$worker" }
        pekko.workflow.table-scheduling.jdbc { url="jdbc:invalid:must-not-open", username="u", password="p" }"""
      java.nio.file.Files.writeString(cfg,content)
      try {
        val error=intercept[IllegalArgumentException](new SnapshotSupervisorSettings(cfg))
        error.getMessage should include("Workflow workers are not part of the CDC runtime")
      } finally {
        java.nio.file.Files.deleteIfExists(cfg)
        java.nio.file.Files.deleteIfExists(worker)
        java.nio.file.Files.deleteIfExists(auth)
        java.nio.file.Files.deleteIfExists(dir)
      }
    }
    "allow ordinary JDBC connections without installing a managed owner" in {
      val url="jdbc:h2:mem:owner_jdbc_"+java.util.UUID.randomUUID()+";DB_CLOSE_DELAY=-1"
      val connection=SnapshotOwnerJdbc.open(url,"sa","","control")
      try connection.isValid(1) shouldBe true finally connection.close()
    }
    "refuse managed launch properties when the worker HA bootstrap is disabled" in {
      val c=com.typesafe.config.ConfigFactory.parseString("pekko.workflow.snapshot-ha.enabled=false\npekko.workflow.snapshot-ha.supervisor-config=\"must-not-be-read\"")
      intercept[IllegalArgumentException](SnapshotOwnerBootstrap.prepare(c))
    }
    "select the independent CDC entry and allow its worker to disable workflow dispatch" in {
      val controller=com.typesafe.config.ConfigFactory.parseString("""pekko.workflow.snapshot-ha {enabled=true,deployment-id="d"}
        pekko.workflow.table-scheduling {enabled=true,jdbc {url="jdbc:mysql://host/db",username="u",password="p"}}""")
      val worker=com.typesafe.config.ConfigFactory.parseString("""pekko.cdc-standalone.managed-worker=true
        pekko.workflow.cdc-platform.enabled=true
        pekko.workflow.table-scheduling.enabled=false""").withFallback(controller)
      intercept[IllegalArgumentException](SnapshotSupervisorSettings.workerMain(controller))
      SnapshotSupervisorSettings.workerMain(worker) shouldBe "cn.xuyinyin.magic.cdc.runtime.CdcRuntimeMain"
      noException should be thrownBy SnapshotSupervisorSettings.validateWorker(worker,controller)
      val noDispatch=com.typesafe.config.ConfigFactory.parseString("pekko.workflow.table-scheduling.enabled=false").withFallback(controller)
      noException should be thrownBy SnapshotSupervisorSettings.validateWorker(worker,noDispatch)
      intercept[IllegalArgumentException](SnapshotSupervisorSettings.validateWorker(controller,noDispatch))
    }
    "append control attributes to both legal MySQL URL forms without changing the database" in {
      SnapshotSupervisorSettings.appendParameters("jdbc:mysql://host:3306/db","x=1") shouldBe "jdbc:mysql://host:3306/db?x=1"
      SnapshotSupervisorSettings.appendParameters("jdbc:mysql://host:3306/db?a=2","x=1") shouldBe "jdbc:mysql://host:3306/db?a=2&x=1"
      intercept[IllegalArgumentException](SnapshotSupervisorSettings.appendParameters("jdbc:mysql://host/db?connectionAttributes=foreign","x=1"))
    }
    "refuse a worker profile with another deployment or scheduling database" in {
      val controller=com.typesafe.config.ConfigFactory.parseString("""pekko.workflow.snapshot-ha {enabled=true,deployment-id="d"}
        pekko.workflow.table-scheduling {enabled=true,jdbc {url="jdbc:mysql://host/db",username="u",password="p"}}""")
      val otherDeployment=com.typesafe.config.ConfigFactory.parseString("pekko.cdc-standalone.managed-worker=true\npekko.workflow.cdc-platform.enabled=true\npekko.workflow.snapshot-ha.deployment-id=\"other\"").withFallback(controller)
      val otherDatabase=com.typesafe.config.ConfigFactory.parseString("pekko.cdc-standalone.managed-worker=true\npekko.workflow.cdc-platform.enabled=true\npekko.workflow.table-scheduling.jdbc.url=\"jdbc:mysql://host/other\"").withFallback(controller)
      intercept[IllegalArgumentException](SnapshotSupervisorSettings.validateWorker(controller,controller))
      intercept[IllegalArgumentException](SnapshotSupervisorSettings.validateWorker(otherDeployment,controller))
      intercept[IllegalArgumentException](SnapshotSupervisorSettings.validateWorker(otherDatabase,controller))
    }
  }
  "guardian signed evidence" should {
    "verify an isolation receipt saved before control identities were moved" in {
      val path=java.nio.file.Path.of("docs/experiments/2026-11-08-cdc-standalone-entry/managed-cdc-c1-20261111/C1-e791978ead66/02-isolated.json")
      val root=java.nio.file.Files.readString(path).parseJson.asJsObject.fields
      val managed=root("managedOwners").asInstanceOf[JsArray].elements.head.asJsObject.fields
      val launch=root("guardian").asJsObject.fields("launches").asInstanceOf[JsArray].elements.head.asJsObject.fields
      def value(fields:Map[String,JsValue],key:String):String=fields(key).asInstanceOf[JsString].value
      val binding=GuardianBinding(value(managed,"deployment_id"),value(managed,"host_id"),value(managed,"guardian_boot"),value(managed,"guardian_key"))
      val id=SnapshotOwnerIdentity(value(managed,"boot_id"),value(managed,"deployment_id"),value(managed,"process_id").toLong,value(managed,"start_identity"))
      val operation=value(launch,"operation_id")
      SnapshotGuardianWire.verifyReceipt(value(launch,"signed_proof"),binding,id,operation).identity shouldBe id
    }
    "reject legacy grants that could modify account or proxy authority" in {
      LegacyMySqlAccountLock.legacyGrantsAreRestricted(Vector("GRANT SELECT, UPDATE ON `work`.* TO `old`@`host`")) shouldBe true
      LegacyMySqlAccountLock.legacyGrantsAreRestricted(Vector("GRANT USAGE ON *.* TO `old`@`host`")) shouldBe true
      LegacyMySqlAccountLock.legacyGrantsAreRestricted(Vector("GRANT CREATE USER ON *.* TO `old`@`host`")) shouldBe false
      LegacyMySqlAccountLock.legacyGrantsAreRestricted(Vector("GRANT UPDATE ON *.* TO `old`@`host`")) shouldBe false
      LegacyMySqlAccountLock.legacyGrantsAreRestricted(Vector("GRANT ALL ON *.* TO `old`@`host`")) shouldBe false
      LegacyMySqlAccountLock.legacyGrantsAreRestricted(Vector("GRANT UPDATE ON `mysql`.`user` TO `old`@`host`")) shouldBe false
      LegacyMySqlAccountLock.legacyGrantsAreRestricted(Vector("GRANT UPDATE ON `%`.* TO `old`@`host`")) shouldBe false
      LegacyMySqlAccountLock.legacyGrantsAreRestricted(Vector("GRANT UPDATE ON `m%`.* TO `old`@`host`")) shouldBe false
      LegacyMySqlAccountLock.legacyGrantsAreRestricted(Vector("GRANT EXECUTE ON `work`.* TO `old`@`host`")) shouldBe false
      LegacyMySqlAccountLock.legacyGrantsAreRestricted(Vector("GRANT `role`@`%` TO `old`@`host`")) shouldBe false
      LegacyMySqlAccountLock.legacyGrantsAreRestricted(Vector("GRANT PROXY ON ''@'' TO `old`@`host`")) shouldBe false
    }
    "bind a signed worker challenge to its nonce, controller context and owner identity" in {
      import SnapshotGuardianWire._
      val keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      val g=GuardianBinding("d","host","g",Base64.getEncoder.encodeToString(keys.getPublic.getEncoded))
      val id=SnapshotOwnerIdentity("boot","d",101,"start");val nonce="a"*43
      val ctx=WorkerAttestationContext("attempt","a"*64,"c"*36,"db","host:3306","controller@%","permit","b"*64)
      val args=JsObject("nonce"->JsString(nonce),"identity"->id.toJson,"profile"->JsString("profile"),"context"->ctx.toJson)
      val request=JsObject("method"->JsString("ATTEST_WORKER"),"args"->args)
      val result=JsObject(args.fields++Map("provider"->JsString("managed-child-jvm"),"alive"->JsBoolean(true)))
      val raw=sign(JsObject("guardian"->g.toJson,"request"->request,"ok"->JsBoolean(true),"result"->result),keys.getPrivate).compactPrint
      verifyWorkerAttestation(raw,g,id,"profile",ctx,nonce).context shouldBe ctx
      intercept[IllegalArgumentException](verifyWorkerAttestation(raw,g,id,"profile",ctx.copy(permitId="other"),nonce))
      intercept[IllegalArgumentException](verifyWorkerAttestation(raw,g,id.copy(startIdentity="other"),"profile",ctx,nonce))
      intercept[IllegalArgumentException](verifyWorkerAttestation(raw,g,id,"profile",ctx,"b"*43))
    }
    "count a foreign-tagged session that uses an exclusive CDC account" in {
      val row=Map("user"->"cdc_sdk","owner"->"foreign")
      cn.xuyinyin.magic.cdc.guardian.DrainObservationPolicy.unattributed(row,"old",Set("cdc_sdk"),Set("cdc_sdk")) shouldBe true
      cn.xuyinyin.magic.cdc.guardian.DrainObservationPolicy.unattributed(row,"old",Set("cdc_sdk"),Set.empty) shouldBe false
      cn.xuyinyin.magic.cdc.guardian.DrainObservationPolicy.unattributed(row.updated("owner","old"),"old",Set("cdc_sdk"),Set("cdc_sdk")) shouldBe false
    }
    "require directly granted global process visibility" in {
      val policy = cn.xuyinyin.magic.cdc.guardian.DrainObservationPolicy
      policy.hasProcessPrivilege(Vector("GRANT PROCESS ON *.* TO 'observer'@'%'")) shouldBe true
      policy.hasProcessPrivilege(Vector("GRANT ALL PRIVILEGES ON *.* TO 'observer'@'%'")) shouldBe true
      policy.hasProcessPrivilege(Vector("GRANT SELECT ON *.* TO 'observer'@'%'")) shouldBe false
      policy.hasProcessPrivilege(Vector("GRANT PROCESS ON `db`.* TO 'observer'@'%'")) shouldBe false
      policy.hasProcessPrivilege(Vector("GRANT `role_with_process`@`%` TO 'observer'@'%'")) shouldBe false
    }
    "reject disabled or missing observer connection attributes" in {
      cn.xuyinyin.magic.cdc.guardian.DrainObservationPolicy.hasObserverIdentity("0", Map.empty) shouldBe false
      cn.xuyinyin.magic.cdc.guardian.DrainObservationPolicy.hasObserverIdentity("1024", Map.empty) shouldBe false
      cn.xuyinyin.magic.cdc.guardian.DrainObservationPolicy.hasObserverIdentity("1024", Map("pekko_owner" -> "unexpected")) shouldBe false
      cn.xuyinyin.magic.cdc.guardian.DrainObservationPolicy.hasObserverIdentity("1024", Map("pekko_owner" -> "observer")) shouldBe true
      cn.xuyinyin.magic.cdc.guardian.DrainObservationPolicy.hasObserverIdentity("1024", Map("pekko_owner" -> "observer", "pekko_role" -> "observer")) shouldBe true
    }
    "include a dedicated material account in the state endpoint's exclusive drain inventory" in {
      import cn.xuyinyin.magic.connectors.mysql.source.{MySQLCdcSourceConfig,MySQLCdcStateConfig}
      import cn.xuyinyin.magic.cdc.platform.{CdcCaptureSettings,CdcPlatformSettings,CdcPlatformLimits}
      val state=MySQLCdcStateConfig("jdbc:mysql://db:3306/state","control","secret","offsets","history",0)
      val source=MySQLCdcSourceConfig("capture","source",3306,"source_db","pekko_capture_control","source_user","secret",101,128,100)
      val capture=CdcCaptureSettings("capture",source,state.copy(username="sdk"),state,"source-uuid","state-uuid",
        materialState=Some(state.copy(username="spool")))
      val accounts=SnapshotSupervisorSettings.exclusiveCdcAccounts(Vector(CdcPlatformSettings(Vector(capture),CdcPlatformLimits())))
      accounts("db:3306") shouldBe Set("sdk","spool")
      cn.xuyinyin.magic.cdc.guardian.DrainObservationPolicy.unattributed(Map("user"->"spool","owner"->"foreign"),"old",Set("spool"),accounts("db:3306")) shouldBe true
      cn.xuyinyin.magic.cdc.guardian.DrainObservationPolicy.unattributed(Map("user"->"spool","owner"->null),"old",Set("spool"),accounts("db:3306")) shouldBe true
      SnapshotSupervisorSettings.exclusiveCdcAccounts(Vector(CdcPlatformSettings(Vector(capture.copy(materialState=None)),CdcPlatformLimits())))("db:3306") shouldBe Set("sdk")
    }
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
