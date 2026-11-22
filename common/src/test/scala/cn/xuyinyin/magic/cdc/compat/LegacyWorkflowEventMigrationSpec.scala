package cn.xuyinyin.magic.cdc.compat

import cn.xuyinyin.magic.cdc.checkpoint.{BatchCheckpoint, SnapshotBoundary, SourceCursor}
import cn.xuyinyin.magic.cdc.compat.WorkflowEvents._
import cn.xuyinyin.magic.cdc.snapshot.StartSnapshotSplit
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.serialization.SerializationExtension
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.io.Source
import spray.json._
import spray.json.DefaultJsonProtocol._

class LegacyWorkflowEventMigrationSpec extends AnyWordSpec with Matchers {
  "legacy workflow event migration" should {
    "read every pre-move event fixture, including a versioned split update" in {
      val trigger=ExecutionTrigger("manual",Some("request"))
      val node=PersistedNodeExecutionResult("n","source","ok","",false,1L,true)
      val result=PersistedExecutionResult("ok",true,"",1,true,2L,true,Vector(node))
      val checkpoint=BatchCheckpoint("source","part",0,"batch",SourceCursor("mysql","1","9"),1,1)
      val expected=Vector[AnyRef](trigger,node,result,WorkflowDefined("{}",1,2),ExecutionStarted("e",trigger,2),
        ResumableExecutionStarted("e",trigger,1,2),ExecutionSnapshotInitialized("e",SnapshotBoundary("source","part",Some("9")),2),
        ExecutionCheckpointAdvanced("e",checkpoint,2),ExecutionRecoveryState(),ExecutionRecoveryScheduled("e",1,2,"reason","stage",3),
        ExecutionRecoveryAttemptStarted("e",1,2),ExecutionRecoveryBlocked("e","reason","stage",2),ExecutionCompleted("e",result,2),
        ExecutionFailed("e",result,2),SnapshotSplitsUpdated("e",StartSnapshotSplit("p:0"),2),SnapshotExecutionPaused("e","reason",2),
        SnapshotSchedulingChanged("e","running","grant",2),ExecutionSkipped("e",trigger,"reason",2))
      val source=Source.fromResource("cdc/legacy-workflow-events.tsv")
      val rows=try source.getLines().toVector finally source.close()
      rows.size shouldBe expected.size
      val kit=ActorTestKit("legacy-workflow-event-migration")
      try {
        val serialization=SerializationExtension(kit.system.toClassic)
        rows.zip(expected).foreach {case (line,value)=>
          val columns=line.split("\t",3)
          withClue(columns(1)) {
            serialization.deserialize(java.util.HexFormat.of.parseHex(columns(2)),columns(0).toInt,columns(1)).get shouldBe value
          }
        }
      } finally kit.shutdownTestKit()
    }
    "read all events from a preserved real MySQL Journal sequence" in {
      val path=java.nio.file.Path.of("docs/experiments/2026-10-25-capture-startup/R02-original-trigger-eaa005be087a/final.json")
      val root=java.nio.file.Files.readString(path).parseJson.asJsObject.fields
      val events=root("worker").asJsObject.fields("J").asInstanceOf[JsArray].elements
      val kit=ActorTestKit("actual-workflow-event-migration")
      try {
        val serialization=SerializationExtension(kit.system.toClassic)
        val decoded=events.map { row=>val fields=row.asJsObject.fields
          serialization.deserialize(java.util.HexFormat.of.parseHex(fields("payload_hex").convertTo[String]),
            fields("event_ser_id").convertTo[String].toInt,fields("event_ser_manifest").convertTo[String]).get
        }
        decoded.map(_.getClass.getSimpleName) shouldBe Vector("WorkflowDefined","ResumableExecutionStarted",
          "ExecutionSnapshotInitialized","ExecutionCheckpointAdvanced")
      } finally kit.shutdownTestKit()
    }
    "reject an unrecognized legacy class name" in {
      intercept[IllegalArgumentException](new LegacyWorkflowEventMigration().transformClassName(1,
        "cn.xuyinyin.magic.workflow.events.WorkflowEvents$Unexpected"))
    }
  }
}
