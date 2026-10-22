package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.workflow.scheduling._
import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import spray.json._
import java.nio.file.{Path,Files}
import scala.concurrent.{Await,ExecutionContext}
import scala.concurrent.duration._

/** SQL contract probe: completed is seeded, not a completed workflow or an end-to-end data claim. */
class RealMySQLSchedulingRequestIdentitySpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  "the real MySQL scheduling request contract" should {
    "reject changing the definition of an existing completed request without mutation" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_MYSQL_TABLE_SCHEDULING").contains("1"))cancel("isolated loopback MySQL required")
      val settings=Settings.environment()
      val schema="pekko_test_cdc_window_"+java.util.UUID.randomUUID().toString.replace("-","").take(12)
      val dir=Path.of("target","table-scheduling-request-identity",schema).toAbsolutePath;Files.createDirectories(dir)
      TableSchedulingExperimentSupport.provision(settings,schema)
      write(dir,"environment.json",JsObject("schema"->JsString(schema),"scope"->JsString("SQL control-store contract; seeded completed state, no Source/Actor/Sink execution")))
      val config=new HikariConfig();config.setJdbcUrl(settings.url(schema));config.setUsername(settings.user);config.setPassword(settings.password);config.setMaximumPoolSize(2)
      val ds=new HikariDataSource(config)
      implicit val ec:ExecutionContext=ExecutionContext.global
      val store=new TableSchedulingStore(ds,SchedulingLimits(6,4,16,2,131072,128))
      val runtime=new TableSchedulingRuntime(store,"request-contract",Map(s"${settings.host}:${settings.port}"->"mysql-local"))
      val original=TableSchedulingExperimentSupport.workflow(settings,schema,0)
      try {
        Await.result(runtime.submit(original,"request-one"),5.seconds)
        store.release(store.claim(1).head,"completed",true) shouldBe true
        val before=store.get(original.id).get
        val revised=original.copy(name="revision-two")
        intercept[IllegalStateException](Await.result(runtime.submit(revised,"request-one",2L),5.seconds))
        store.get(original.id).get shouldBe before
        Await.result(runtime.submit(original,"request-one"),5.seconds).state shouldBe "completed"
        Await.result(runtime.submit(revised,"request-two",2L),5.seconds).state shouldBe "ready"
        val after=store.get(original.id).get
        write(dir,"result.json",JsObject("passed"->JsBoolean(true),"oldRevision"->JsNumber(before.definition.revision),"oldPhase"->JsString(before.state),
          "oldExecution"->JsString(before.executionId.get),"newRevision"->JsNumber(after.definition.revision),"newPhase"->JsString(after.state),
          "newExecution"->JsString(after.executionId.get),"sameRequestMutationRejected"->JsBoolean(true),"originalRetryPreserved"->JsBoolean(true),
          "tasks"->withConnection(settings,schema)(query(_,"SELECT workflow_id,revision,phase,execution_id FROM pekko_table_tasks")),
          "journalRows"->withConnection(settings,schema)(query(_,"SELECT COUNT(*) AS n FROM event_journal")),
          "D"->JsString("N/A; control-store-only probe")))
      } finally ds.close()
    }
  }
}
