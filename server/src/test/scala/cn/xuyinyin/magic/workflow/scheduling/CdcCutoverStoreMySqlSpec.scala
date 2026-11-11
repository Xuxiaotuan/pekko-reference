package cn.xuyinyin.magic.cdc.control


import cn.xuyinyin.magic.cdc.control.CdcManagedControlStore

import cn.xuyinyin.magic.cdc.control.SchedulingLimits

import cn.xuyinyin.magic.cdc.control.{CredentialBinding, CutoverAuthorizationKey}
import cn.xuyinyin.magic.tags.ExternalIntegration
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import java.nio.file.{Files, Path}
import java.sql.DriverManager
import java.util.UUID
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

/** Isolated MySQL storage contract; it does not fabricate guardian or account-drain proofs. */
class CdcCutoverStoreMySqlSpec extends AnyWordSpec with Matchers {
  "CDC cutover store" should {
    "persist and re-read exact authorization and permit-bound credential tuples" taggedAs ExternalIntegration in {
      if (!sys.env.get("RUN_CDC_CUTOVER_STORE").contains("1")) cancel("dedicated MySQL required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty))
      val base=sys.env("CAPTURE_STATE_URL")
      require(base.startsWith("jdbc:mysql://127.0.0.1:25822/") && base.contains("pekko_test_shared_state"))
      val user=sys.env("CDC_WINDOW_MYSQL_USER");val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      val schema="pekko_test_cutover_"+UUID.randomUUID().toString.replace("-","").take(12)
      val admin=DriverManager.getConnection(base,user,password)
      try {val q=admin.createStatement();try q.execute("CREATE DATABASE "+schema) finally q.close()} finally admin.close()
      val url=base.replace("pekko_test_shared_state",schema)
      val hc=new HikariConfig();hc.setJdbcUrl(url);hc.setUsername(user);hc.setPassword(password);hc.setMaximumPoolSize(2)
      val ds=new HikariDataSource(hc)
      try {
        val c=ds.getConnection
        try {
          Vector("db/mysql/pekko-table-scheduling-schema.sql","db/mysql/pekko-snapshot-ownership-schema.sql",
            "db/mysql/pekko-snapshot-cutover-schema.sql").foreach { name=>
            val source=scala.io.Source.fromInputStream(getClass.getClassLoader.getResourceAsStream(name))
            val sql=try source.mkString finally source.close()
            sql.split(";").map(_.trim).filter(_.nonEmpty).foreach { text=>val q=c.createStatement();try q.execute(text) finally q.close() }
          }
        } finally c.close()
        val store=new CdcManagedControlStore(ds,SchedulingLimits(2,1,8,1,1048576,8))
        val key=CutoverAuthorizationKey("d","h","op","old",3L,"a"*64,"b"*64,"c"*64)
        val binding=CredentialBinding("workflow","execution","source","source","permit","new-owner","op",
          "127.0.0.1:3306","db","old_user","new_user","CDC_NEW_PASSWORD","d"*64,"ACTIVE")
        store.cutoverStore.recordAuthorization(key);store.cutoverStore.recordAuthorization(key)
        store.transaction { c=>
          store.update(c,"INSERT INTO pekko_table_tasks (workflow_id,revision,workflow_json,source_instance,target_instance,parallelism,page_bytes,phase,execution_id,queue_order,not_before,grant_id,owner_id) VALUES ('workflow',1,'{}','s','t',1,1,'running','execution',1,0,'permit','new-owner')")
          store.update(c,"INSERT INTO pekko_snapshot_writer_permissions (workflow_id,owner_id,generation,permit_id,grant_id,execution_id,revision,definition_hash,phase,predecessor_owner) VALUES ('workflow','new-owner',1,'permit','permit','execution',1,?, 'active',NULL)","d"*64)
        }
        store.cutoverStore.recordBinding(binding,key);store.cutoverStore.recordBinding(binding,key)
        val reopened=new CdcManagedControlStore(ds,SchedulingLimits(2,1,8,1,1048576,8))
        reopened.cutoverStore.authorization(key) shouldBe Some(key)
        reopened.cutoverStore.binding("workflow","execution","source","source") shouldBe Some(binding)
        intercept[IllegalArgumentException](reopened.cutoverStore.recordBinding(binding.copy(runtimeUser="other"),key))
        intercept[IllegalArgumentException](reopened.cutoverStore.recordBinding(binding.copy(permitId="other"),key))
        val dir=Path.of(sys.env("CDC_CUTOVER_EVIDENCE"));Files.createDirectories(dir)
        val result=JsObject("scope"->JsString("isolated MySQL store; synthetic authorization and permit; no guardian or source"),
          "schema"->JsString(schema),"authorizationReread"->JsBoolean(true),"bindingReread"->JsBoolean(true),
          "idempotent"->JsBoolean(true),"changedIdentityRejected"->JsBoolean(true),"wrongPermitRejected"->JsBoolean(true))
        Files.writeString(dir.resolve("result.json"),result.prettyPrint)
      } finally ds.close()
    }
  }
}
