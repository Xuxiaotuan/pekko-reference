package cn.xuyinyin.magic.cdc.platform

import cn.xuyinyin.magic.cdc.bootstrap.{BootstrapJournal,BootstrapProgress}
import java.nio.file.{Files,Path}
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.serialization.SerializationExtension
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._
import spray.json.DefaultJsonProtocol._

class CdcProgressCompatibilitySpec extends AnyWordSpec with Matchers {
  "bootstrap progress serialization" should {
    "read legacy continuation JSON and preserve a new permit binding" in {
      val old=CdcProgressJson.encode(BootstrapProgress(revision=7,definition="definition")).parseJson.asJsObject
      val legacy=JsObject(old.fields-"recoveryPermit"-"recoveryReceiptDigest").compactPrint
      val recovered=CdcProgressJson.decode(legacy)
      recovered.revision shouldBe 7
      recovered.recoveryPermit shouldBe None
      recovered.recoveryReceiptDigest shouldBe None
      val current=recovered.copy(recoveryPermit=Some("permit-1"),recoveryReceiptDigest=Some("a"*64))
      CdcProgressJson.decode(CdcProgressJson.encode(current)) shouldBe current
    }
    "deserialize the preserved pre-change real MySQL Journal CBOR payloads" in {
      val path=Path.of("docs/experiments/2026-11-05-cdc-flow-refactor/managed/C1-7bc6494fa216/03-recovered.json")
      val records=Files.readString(path).parseJson.asJsObject.fields("J").asInstanceOf[JsArray].elements
      records.nonEmpty shouldBe true
      val kit=ActorTestKit("legacy-bootstrap-cbor")
      try {
        val serializer=SerializationExtension(kit.system.toClassic)
        records.foreach {v=>val f=v.asJsObject.fields
          val bytes=java.util.HexFormat.of().parseHex(f("payload_hex").convertTo[String])
          val decoded=serializer.deserialize(bytes,
            f("event_ser_id").convertTo[String].toInt,f("event_ser_manifest").convertTo[String]).get
          val event=decoded.asInstanceOf[BootstrapJournal.Advanced]
          event.state.recoveryPermit shouldBe None
          event.state.recoveryReceiptDigest shouldBe None
          // Extract the old event's unmodified progress object as a legacy snapshot payload.
          val mapper=new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.cbor.CBORFactory())
          val oldState=mapper.readTree(bytes).get("state")
          oldState.has("recoveryPermit") shouldBe false
          val snapshot=serializer.deserialize(mapper.writeValueAsBytes(oldState),f("event_ser_id").convertTo[String].toInt,
            "cn.xuyinyin.magic.workflow.bootstrap.BootstrapProgress").get.asInstanceOf[BootstrapProgress]
          snapshot shouldBe event.state
        }
      }finally kit.shutdownTestKit()
    }
    "round-trip a newly written bootstrap journal event" in {
      val kit=ActorTestKit("current-bootstrap-cbor")
      try {
        val serializer=SerializationExtension(kit.system.toClassic)
        val event=BootstrapJournal.Advanced(BootstrapProgress(revision=8,definition="definition"))
        val binding=serializer.findSerializerFor(event)
        val manifest=org.apache.pekko.serialization.Serializers.manifestFor(binding,event)
        manifest should include ("cn.xuyinyin.magic.cdc.bootstrap.BootstrapJournal$Advanced")
        serializer.deserialize(binding.toBinary(event),binding.identifier,manifest).get shouldBe event
      }finally kit.shutdownTestKit()
    }
  }
}
