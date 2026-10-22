package cn.xuyinyin.magic.api.http.routes

import cn.xuyinyin.magic.workflow.WorkflowFixtures
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import cn.xuyinyin.magic.workflow.scheduling._
import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import org.apache.pekko.http.scaladsl.model.{ContentTypes,HttpEntity,StatusCodes}
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers
import spray.json._

class SnapshotSchedulingRoutesSpec extends AnyWordSpecLike with Matchers with ScalatestRouteTest {
  "snapshot task API" should {
    "durably accept a cold task and list it without loading its definition or activating an entity" in {
      val c=new HikariConfig();c.setJdbcUrl("jdbc:h2:mem:api-admission-"+java.util.UUID.randomUUID()+";MODE=MySQL");c.setUsername("sa");c.setMaximumPoolSize(2)
      val ds=new HikariDataSource(c);val cn=ds.getConnection
      try {val in=getClass.getClassLoader.getResourceAsStream("db/h2/pekko-table-scheduling-schema.sql");val sql=try scala.io.Source.fromInputStream(in).mkString finally in.close()
        sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(q=>{val s=cn.createStatement();try s.execute(q) finally s.close()})
      } finally cn.close()
      val runtime=new TableSchedulingRuntime(new TableSchedulingStore(ds,SchedulingLimits(2,2,8,2,67108864,8)),"api")(system.dispatcher)
      val base=WorkflowFixtures.mysqlCdcWorkflow
      val w=base.copy(id="cold",nodes=base.nodes.map(n=>n.copy(nodeType=if(n.`type`=="source")"mysql.snapshot" else "mysql.write",config=JsObject(n.config.fields++Map(
        "host"->JsString("mysql"),"port"->JsNumber(3306),"splitRows"->JsNumber(4),"chunkSize"->JsNumber(2),"snapshotConsistency"->JsString("quiescent"))))))
      val routes=new SnapshotSchedulingRoutes(Some(runtime))(system.dispatcher).routes
      try {
        Post("/api/v1/snapshot-tasks?requestId=same",HttpEntity(ContentTypes.`application/json`,w.toJson.compactPrint)) ~> routes ~> check {
          status shouldBe StatusCodes.Accepted
          responseAs[String].parseJson.asJsObject.fields("phase") shouldBe JsString("ready")
        }
        Get("/api/v1/snapshot-tasks?pageSize=1") ~> routes ~> check {
          status shouldBe StatusCodes.OK
          val items=responseAs[String].parseJson.asJsObject.fields("items").asInstanceOf[JsArray].elements
          items.size shouldBe 1
          items.head.asJsObject.fields.keySet should not contain "workflowJson"
        }
        runtime.store.get("cold").get.state shouldBe "ready"
      } finally ds.close()
    }
  }
}
