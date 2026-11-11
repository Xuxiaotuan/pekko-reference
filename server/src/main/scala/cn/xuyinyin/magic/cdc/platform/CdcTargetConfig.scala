package cn.xuyinyin.magic.cdc.platform

import cn.xuyinyin.magic.connectors.mysql.sink.MySQLCdcApplyConfig

/** CDC runtime settings without a Workflow Node. */
private[cdc] object CdcTargetConfig {
  def forTarget(settings:CdcCaptureSettings,table:String):MySQLCdcApplyConfig =
    MySQLCdcApplyConfig.trusted(settings.targetHost,settings.targetPort,settings.targetDatabase,table,
      settings.controlState.username,settings.controlState.password)
}
