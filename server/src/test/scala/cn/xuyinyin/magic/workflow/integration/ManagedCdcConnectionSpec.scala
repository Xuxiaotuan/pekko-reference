package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.workflow.scheduling._
import cn.xuyinyin.magic.workflow.cdc.{CdcBootstrapKernel,CdcCaptureSettings,CdcPlatformLimits,CdcPlatformSettings}
import cn.xuyinyin.magic.connectors.mysql.source.{MySQLCdcSourceConfig,MySQLCdcStateConfig}
import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import com.typesafe.config.ConfigFactory
import java.nio.file.{Files,Path}
import java.util.UUID
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** Dedicated MySQL only. The singleton owner context requires this suite to run alone. */
class ManagedCdcConnectionSpec extends AnyWordSpec with Matchers {
  "managed CDC direct JDBC" should {
    "register the account and session before application SQL and reject a revoked owner" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_CDC_MANAGED_CONNECTION").contains("1"))cancel("dedicated MySQL required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty))
      val url=sys.env("CDC_MANAGED_TEST_JDBC_URL");val user=sys.env("CDC_MANAGED_TEST_USER");val password=sys.env("CDC_MANAGED_TEST_PASSWORD")
      val expected=sys.env("CAPTURE_EXPECT_STATE_UUID")
      val config=new HikariConfig();config.setJdbcUrl(url);config.setUsername(user);config.setPassword(password);config.setMaximumPoolSize(4)
      val ds=new HikariDataSource(config)
      try {
        val c=ds.getConnection
        val actual=try {
          val identity=SnapshotOwnerJdbc.databaseIdentity(c)
          require(identity._1==expected,"test MySQL lineage mismatch")
          Vector("db/mysql/pekko-table-scheduling-schema.sql","db/mysql/pekko-snapshot-ownership-schema.sql").foreach { name=>
            val in=getClass.getClassLoader.getResourceAsStream(name)
            val sql=try scala.io.Source.fromInputStream(in).mkString finally in.close()
            sql.split(";").map(_.trim).filter(_.nonEmpty).foreach { s=>val q=c.createStatement();try q.execute(s) finally q.close() }
          }
          identity
        } finally c.close()
        val store=new TableSchedulingStore(ds,SchedulingLimits(2,1,8,1,1048576,8),ownershipEnabled=true)
        val owner=SnapshotOwnerIdentity("cdc-probe-"+UUID.randomUUID(),"test-domain",ProcessHandle.current().pid(),ProcessHandle.current().info().startInstant().orElseThrow().toString)
        store.ownership.get.registerOwner(owner)
        val endpoint=SnapshotOwnerJdbc.endpoint(url)
        val sourceUrl=sys.env("CDC_MANAGED_SOURCE_JDBC_URL")
        val sourceEndpoint=SnapshotOwnerJdbc.endpoint(sourceUrl)
        val sourceUuid=sys.env("CAPTURE_EXPECT_SOURCE_UUID")
        SnapshotOwnerJdbc.install(SnapshotOwnerJdbc.Context(owner,store,Map(endpoint->actual._1,sourceEndpoint->sourceUuid),()=>()))
        val direct=SnapshotOwnerJdbc.open(url,user,password,"source",managed=true)
        val id=try {
          val observed=SnapshotOwnerJdbc.databaseIdentity(direct)._3
          store.ownership.get.knownSessions(owner.bootId,endpoint) should contain (observed)
          store.ownership.get.databaseUsers(owner.bootId,endpoint) should contain (user)
          val q=direct.createStatement();try q.execute("CREATE TABLE IF NOT EXISTS managed_connection_probe(id INT PRIMARY KEY)") finally q.close()
          observed
        } finally direct.close()
        val actorConfig=ConfigFactory.parseMap(Map[String,AnyRef](
          "pekko.actor.provider"->"local","pekko-persistence-jdbc.shared-databases.slick.db.url"->url,
          "pekko-persistence-jdbc.shared-databases.slick.db.user"->user,
          "pekko-persistence-jdbc.shared-databases.slick.db.password"->password).asJava).withFallback(ConfigFactory.load())
        val system=org.apache.pekko.actor.typed.ActorSystem(org.apache.pekko.actor.typed.scaladsl.Behaviors.empty[String],"cdc-manifest-"+UUID.randomUUID().toString.take(8),actorConfig)
        val manifest=try {
          val uri=new java.net.URI(sourceUrl.stripPrefix("jdbc:"))
          val source=MySQLCdcSourceConfig("probe","127.0.0.1",uri.getPort,uri.getPath.stripPrefix("/"),"pekko_capture_control","cdc_src",password,888,128,100)
          val state=MySQLCdcStateConfig(url,user,password,"offsets","history",1000)
          val sdk=state.copy(username="cdc_sdk")
          val capture=CdcCaptureSettings("probe",source,sdk,state,sourceUuid,actual._1)
          val kernel=new CdcBootstrapKernel(CdcPlatformSettings(Vector(capture),CdcPlatformLimits()),system,managed=true)
          try kernel.prepareManagedManifest() finally kernel.closeAndVerify()
        } finally {system.terminate();Await.result(system.whenTerminated,20.seconds)}
        val accounts=manifest.parseJson.asJsObject.fields("accounts").asInstanceOf[JsArray].elements.map(_.asJsObject.fields("user")).toSet
        Set[JsValue](JsString(user),JsString("cdc_sdk"),JsString("cdc_src")).subsetOf(accounts) shouldBe true
        store.ownership.get.beginRevocation(owner.bootId,"test-operation")
        intercept[IllegalStateException](SnapshotOwnerJdbc.open(url,user,password,"source",managed=true))
        val result=JsObject("status"->JsString("PASS"),"scope"->JsString("real MySQL registered direct connection; no guardian, Journal or Sink"),
          "serverUuid"->JsString(actual._1),"owner"->JsString(owner.bootId),"connectionId"->JsNumber(id),
          "registeredUsers"->JsArray(store.ownership.get.databaseUsers(owner.bootId,endpoint).toVector.sorted.map(JsString(_))),
          "registeredSessions"->JsArray(store.ownership.get.knownSessions(owner.bootId,endpoint).map(JsNumber(_))),"manifest"->manifest.parseJson,
          "newConnectionAfterRevocation"->JsString("REJECTED"))
        val output=Path.of(sys.env("CDC_MANAGED_CONNECTION_EVIDENCE"));Files.createDirectories(output)
        Files.writeString(output.resolve("result.json"),result.prettyPrint)
      } finally ds.close()
    }
  }
}
