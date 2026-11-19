package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.tags.ExternalIntegration
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import spray.json._
import java.nio.file.{Path,Files}
import java.net.URLClassLoader
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class RealMySQLSchedulingMetadataSpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  "the durable scheduling index" should {
    "retain bounded control-plane behavior for700000 records and a cold JVM restart" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_MYSQL_TABLE_SCHEDULING").contains("1"))cancel("external_blocked: explicit isolated MySQL required")
      val settings=Settings.environment();val suffix=UUID.randomUUID().toString.replace("-","").take(12);val schema=s"pekko_test_cdc_window_$suffix"
      val dir=Path.of("target","table-scheduling-metadata",suffix).toAbsolutePath;Files.createDirectories(dir)
      TableSchedulingExperimentSupport.provision(settings,schema)
      write(dir,"environment.json",JsObject("schema"->JsString(schema),"rows"->JsNumber(700000),"java"->JsString(System.getProperty("java.version"))))
      val loaders=Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(l=>Option(l.getParent))).takeWhile(_.nonEmpty).flatten
        .collect{case l:URLClassLoader=>l.getURLs.iterator}.flatten.filter(_.getProtocol=="file").map(u=>Path.of(u.toURI).toString)
      val cp=(loaders++System.getProperty("java.class.path","").split(File.pathSeparator)).filter(_.nonEmpty).toVector.distinct.mkString(File.pathSeparator)
      Vector("load","inspect").foreach { mode=>
        val builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx256m","-Duser.timezone=UTC","-cp",cp,
          RealMySQLSchedulingMetadataProcess.getClass.getName.stripSuffix("$"),schema,dir.toString,mode)
        builder.redirectErrorStream(true);builder.redirectOutput(dir.resolve(mode+".log").toFile)
        builder.environment().put("CDC_WINDOW_MYSQL_HOST",settings.host);builder.environment().put("CDC_WINDOW_MYSQL_PORT",settings.port.toString)
        builder.environment().put("CDC_WINDOW_MYSQL_USER",settings.user);builder.environment().put("CDC_WINDOW_MYSQL_PASSWORD",settings.password)
        val process=builder.start()
        try {process.waitFor(900,TimeUnit.SECONDS) shouldBe true;process.exitValue() shouldBe 0}
        finally if(process.isAlive)process.destroyForcibly()
        val result=Files.readString(dir.resolve(mode+"-result.json")).parseJson.asJsObject
        result.fields("rows") shouldBe JsNumber(700000);result.fields("passed") shouldBe JsBoolean(true)
        result.fields("heap").asInstanceOf[JsArray].elements.foreach(v=>v.asJsObject.fields("heapUsedAfterGc").convertTo[Long] should be <= v.asJsObject.fields("heapMax").convertTo[Long])
      }
      write(dir,"result.json",JsObject("passed"->JsBoolean(true),"schema"->JsString(schema),"rows"->JsNumber(700000),"classification"->JsString("metadata_only_bounded_and_restart_preserved")))
    }
  }
}
