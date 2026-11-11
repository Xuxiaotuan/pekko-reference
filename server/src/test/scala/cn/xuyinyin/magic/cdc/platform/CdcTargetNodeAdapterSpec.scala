package cn.xuyinyin.magic.cdc.platform

import cn.xuyinyin.magic.connectors.mysql.source.{MySQLCdcSourceConfig,MySQLCdcStateConfig}
import cn.xuyinyin.magic.cdc.compat.nodes.WorkflowDSL.{Node,Position}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

class CdcTargetNodeAdapterSpec extends AnyWordSpec with Matchers {
  "the CDC target adapter" should {
    "preserve the existing workflow sink node and take credentials from control state" in {
      val source=MySQLCdcSourceConfig("capture","source-host",3306,"source_db","orders","source-user","source-password",1234,128,100)
      val control=MySQLCdcStateConfig("jdbc:mysql://target-host:3307/target_db","control-user","test-password-!", "offsets","history",0)
      val sdk=control.copy(username="sdk-user",password="sdk-password")
      val settings=CdcCaptureSettings("capture",source,sdk,control,"source-uuid","state-uuid")
      val expected=Node("target","sink","mysql.cdc.apply","CDC platform target",Position(0,0),JsObject(
        "host"->JsString("target-host"),"port"->JsNumber(3307),"database"->JsString("target_db"),
        "table"->JsString("orders_out"),"username"->JsString("control-user"),"password"->JsString("test-password-!")))
      CdcTargetNodeAdapter.forTarget(settings,"orders_out") shouldBe expected
      CdcTargetConfig.forTarget(settings,"orders_out") shouldBe
        cn.xuyinyin.magic.connectors.mysql.sink.MySQLCdcApplyConfig("target-host",3307,"target_db","orders_out","control-user","test-password-!")
      intercept[IllegalArgumentException](CdcTargetConfig.forTarget(settings,"orders;bad"))
    }
  }
}
