package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.connectors.mysql.source._
import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.tags.ExternalIntegration
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files,Path}
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import spray.json._

class ManagedCaptureReleaseSpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  "managed real capture termination" should {
    for(busy<-Vector(false,true)) {
      s"confirm physical release after ${if(busy)"business traffic"else"a quiet source"} without touching another account" taggedAs ExternalIntegration in {
        if(!sys.env.get("RUN_CAPTURE_ADMISSION_MATRIX").contains("1"))cancel("exclusive dual-MySQL slot required")
        val source=Settings.environment();val state=source.copy(port=sys.env("CAPTURE_STATE_PORT").toInt)
        require(!Set(3306,13365,13366).contains(source.port) && !Set(3306,13365,13366).contains(state.port))
        def uuid(s:Settings)=withConnection(s,"")(query(_,"SELECT @@server_uuid AS uuid")).elements.head.asJsObject.fields("uuid").convertTo[String]
        val lineage=uuid(source);val stateLineage=uuid(state)
        require(lineage==sys.env("CAPTURE_EXPECT_SOURCE_UUID") && stateLineage==sys.env("CAPTURE_EXPECT_STATE_UUID"))
        val suffix=UUID.randomUUID().toString.replace("-","").take(12)
        val schema="pekko_test_cdc_window_"+suffix;val id="release_"+suffix
        val sourceUser="cs_"+suffix;val stateUser="ct_"+suffix;val secret=UUID.randomUUID().toString
        val out=Path.of(sys.env("CAPTURE_ADMISSION_EVIDENCE")).resolve(id);Files.createDirectories(out)
        initialize(source,schema);initialize(state,schema)
        def sql(s:Settings,statement:String)=withConnection(s,schema)(executeSql(_,statement))
        sql(source,"CREATE TABLE a LIKE source_rows");sql(source,"INSERT INTO a SELECT * FROM source_rows WHERE id<=4")
        sql(source,s"CREATE USER '$sourceUser'@'127.0.0.1' IDENTIFIED BY '$secret'")
        sql(source,s"GRANT REPLICATION SLAVE,REPLICATION CLIENT,SHOW DATABASES ON *.* TO '$sourceUser'@'127.0.0.1'")
        sql(source,s"GRANT ALL PRIVILEGES ON `$schema`.* TO '$sourceUser'@'127.0.0.1'")
        sql(state,s"CREATE USER '$stateUser'@'127.0.0.1' IDENTIFIED BY '$secret'")
        sql(state,s"GRANT ALL PRIVILEGES ON `$schema`.* TO '$stateUser'@'127.0.0.1'")
        val config=MySQLCdcStateConfig(state.url(schema),stateUser,secret,"capture_offsets","capture_history",0)
        val store=new MySQLCaptureStore(()=>DriverManager.getConnection(state.url(schema),state.user,state.password),id,lineage,64L*1024*1024)
        store.initialize()
        val observer=CaptureResourceObserver.exclusiveAccounts(
          ()=>DriverManager.getConnection(source.url(schema),source.user,source.password),
          ()=>DriverManager.getConnection(state.url(schema),state.user,state.password),sourceUser,stateUser,lineage,stateLineage)
        val events=new ConcurrentLinkedQueue[JsObject]()
        val capture=new SharedMySQLCapture(MySQLCdcSourceConfig(id,source.host,source.port,schema,"a",sourceUser,secret,
          (id.hashCode.toLong & 0x7fffffffL)+100000,128,100),config,store,
          observe=(k,v)=>events.add(JsObject(v.fields+("kind"->JsString(k)))),resourceObserver=Some(observer))
        val other=DriverManager.getConnection(source.url(schema),source.user,source.password)
        try {
          capture.start();awaitCondition("production ready",90.seconds)(capture.startupEvidence.phase=="ADMISSION_READY")
          if(busy){
            capture.subscribe("a","a","g1");awaitCondition("B bound")(store.subscription("a").exists(_.b.nonEmpty))
            sql(source,"UPDATE a SET label='release-active',revision=revision+1 WHERE id=1")
            awaitCondition("durable captured group")(store.read("a",store.subscription("a").get.bSequence.get,16).nonEmpty)
          }
          val offset=MySQLCdcSourceNode.connectorStateTables(config,id)._1
          write(out,"before.json",JsObject("L"->store.evidence,
            "D"->withConnection(state,schema)(query(_,s"SELECT offset_key,offset_val FROM `$offset`")),
            "source"->withConnection(source,schema)(query(_,"SELECT * FROM a ORDER BY id"))))
          val first=capture.requestClose();capture.requestClose() shouldBe first
          val response=Await.result(first,12.seconds)
          response.localTermination shouldBe "CONFIRMED"
          write(out,"initial-response.json",release(response))
          val samples=Vector.newBuilder[JsValue]
          awaitCondition("same close operation physically released",180.seconds){
            val e=Await.result(capture.refreshRelease(),12.seconds)
            e.operationId shouldBe response.operationId;samples+=release(e);e.reusable
          }
          val finalEvidence=capture.releaseEvidence
          finalEvidence.reusable shouldBe true
          val stillAlive=query(other,"SELECT 1 AS alive").elements.head.asJsObject.fields("alive")
          stillAlive shouldBe JsString("1")
          write(out,"result.json",JsObject("status"->JsString("REUSABLE"),"release"->release(finalEvidence),
            "samples"->JsArray(samples.result()),"otherAccountStillAlive"->JsBoolean(true),
            "L"->store.evidence,"events"->JsArray(events.iterator().asScala.toVector),
            "scope"->JsString("Reader termination only; no target/Journal reconciliation in this case")))
        }finally {other.close();capture.close()}
      }
    }
  }
  private def release(e:CaptureReleaseEvidence):JsObject=JsObject("attempt"->JsString(e.runtimeAttemptId),"operation"->JsString(e.operationId),
    "local"->JsString(e.localTermination),"server"->JsString(e.serverRelease),"ownerReleased"->JsBoolean(e.ownerReleased),
    "reusable"->JsBoolean(e.reusable),"reason"->e.reasonCode.map(JsString(_)).getOrElse(JsNull),"observedAt"->JsNumber(e.observedAt))
}
