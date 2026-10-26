package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.testkit.STSpec
import com.typesafe.config.ConfigFactory
import cn.xuyinyin.magic.connectors.mysql.source.{MySQLCdcSourceConfig,MySQLCdcStateConfig}

class CdcPlatformSettingsSpec extends STSpec {
  "CDC platform settings" should {
    "stay disabled unless explicitly enabled" in {
      CdcPlatformSettings.load(ConfigFactory.empty()) shouldBe None
    }
    "reject configuration that reuses the control account as the exclusive SDK account" in {
      val cfg=ConfigFactory.parseString("""
        pekko.workflow.mysql-cdc.state-jdbc {
          url="jdbc:mysql://127.0.0.1:14376/state"
          username="control"
          password="test-state-secret"
          offset-table="capture_offsets"
          history-table="capture_history"
          offset-flush-interval-ms=0
        }
        pekko.workflow.cdc-platform {
          enabled=true
          captures=[{
            id="capture"
            host="127.0.0.1"
            port=14375
            database="source"
            username="source_sdk"
            password="test-source-secret"
            server-id=4810
            source-lineage="11111111-1111-1111-1111-111111111111"
            state-lineage="22222222-2222-2222-2222-222222222222"
            sdk-state-username="control"
            sdk-state-password="test-state-secret"
            exclusive-sdk-accounts=true
          }]
        }
      """)
      intercept[CdcPlatformException](CdcPlatformSettings.load(cfg)).code shouldBe "INVALID_EXCLUSIVE_SDK_ACCOUNT"
    }
    "reject two capture domains that reuse an exclusive source or state account" in {
      val control=MySQLCdcStateConfig("jdbc:mysql://127.0.0.1:14376/state","control","control-secret","offsets","history",0)
      val source=MySQLCdcSourceConfig("first","127.0.0.1",14375,"source","pekko_capture_control","source_sdk","source-secret",4810,128,100)
      val first=CdcCaptureSettings("first",source,control.copy(username="sdk",password="sdk-secret"),control,"11111111-1111-1111-1111-111111111111","22222222-2222-2222-2222-222222222222")
      val sharedSource=first.copy(id="second",source=source.copy(connectorId="second"),sdkState=control.copy(username="sdk_two",password="sdk-secret"))
      intercept[CdcPlatformException](CdcPlatformSettings(Vector(first,sharedSource),CdcPlatformLimits())).code shouldBe "INVALID_EXCLUSIVE_SDK_ACCOUNT"
      val sharedState=first.copy(id="second",source=source.copy(connectorId="second",username="source_two"))
      intercept[CdcPlatformException](CdcPlatformSettings(Vector(first,sharedState),CdcPlatformLimits())).code shouldBe "INVALID_EXCLUSIVE_SDK_ACCOUNT"
    }
    "reject an empty SDK password and a half-specified observer identity" in {
      val control=MySQLCdcStateConfig("jdbc:mysql://127.0.0.1:14376/state","control","control-secret","offsets","history",0)
      val source=MySQLCdcSourceConfig("first","127.0.0.1",14375,"source","pekko_capture_control","source_sdk","source-secret",4810,128,100)
      intercept[CdcPlatformException](CdcCaptureSettings("first",source,control.copy(username="sdk",password=""),control,"source-id","state-id")).code shouldBe "INVALID_EXCLUSIVE_SDK_ACCOUNT"
      intercept[CdcPlatformException](CdcCaptureSettings("first",source,control.copy(username="sdk",password="sdk-secret"),control,"source-id","state-id",sourceObserverUsername=Some("observer"))).code shouldBe "INVALID_OBSERVER_ACCOUNT"
    }
  }
}
