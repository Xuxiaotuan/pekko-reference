package cn.xuyinyin.magic.compatibility

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.serialization.SerializationExtension
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._
import spray.json.DefaultJsonProtocol._
import java.util.Base64

/** Bytes were captured from bf249b1 before any implementation package moved. */
class LegacySerializationCompatibilitySpec extends AnyWordSpec with Matchers {
  "the layout refactor" should {
    "read pre-refactor workflow and scheduler state/events with the original manifests" in {
      val kit = ActorTestKit("legacy-layout-reader", ConfigFactory.parseString("pekko.actor.provider=local").withFallback(ConfigFactory.load()))
      try {
        val source = scala.io.Source.fromResource("compatibility/bf249b1-cbor.json")
        val fixture = try source.mkString.parseJson.asJsObject finally source.close()
        val serialization = SerializationExtension(kit.system.classicSystem)
        val samples = fixture.fields("samples").asInstanceOf[JsArray].elements
        samples.size shouldBe 18
        samples.foreach { row =>
          val fields = row.asJsObject.fields
          def text(name: String) = fields(name).convertTo[String]
          val bytes = Base64.getDecoder.decode(text("payloadBase64"))
          val value = serialization.deserialize(bytes, fields("serializerId").convertTo[Int], text("manifest")).get
          value.getClass.getName shouldBe text("className")
          serialization.serialize(value).get.toVector shouldBe bytes.toVector
        }
      } finally kit.shutdownTestKit()
    }
  }
}
