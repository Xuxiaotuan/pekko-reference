package cn.xuyinyin.magic.cdc.compat

import com.fasterxml.jackson.databind.JsonNode
import org.apache.pekko.serialization.jackson.JacksonMigration

/** Decode historical snapshot payloads without loading the retired executor. */
final class LegacyWorkflowStateMigration extends JacksonMigration {
  override def currentVersion:Int=2
  override def transformClassName(fromVersion:Int,className:String):String={
    val old="cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor$"
    require(className.startsWith(old),"unexpected legacy snapshot class")
    "cn.xuyinyin.magic.cdc.compat.LegacyWorkflowState$"+className.substring(old.length)
  }
  override def transform(fromVersion:Int,json:JsonNode):JsonNode=json
}
