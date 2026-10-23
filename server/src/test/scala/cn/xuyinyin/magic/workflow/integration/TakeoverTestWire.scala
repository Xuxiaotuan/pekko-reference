package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.workflow.scheduling._
import spray.json.DefaultJsonProtocol

/** Laboratory command transport only; production persisted case classes are unchanged. */
private[integration] object TakeoverTestWire extends DefaultJsonProtocol {
  implicit val demandFormat=jsonFormat4(TableResourceDemand.apply)
  implicit val definitionFormat=jsonFormat4(ScheduledTableDefinition.apply)
  implicit val taskFormat=jsonFormat8(ScheduledTable.apply)
  implicit val grantFormat=jsonFormat2(TableGrant.apply)
}
