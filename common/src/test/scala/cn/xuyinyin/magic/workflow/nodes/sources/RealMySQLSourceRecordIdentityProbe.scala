package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.connectors.mysql.source.{RealMySQLSourceRecordIdentityProbe => ConnectorRealMySQLSourceRecordIdentityProbe}

/** Historical experiment command entry; implementation follows the connector package. */
object RealMySQLSourceRecordIdentityProbe {
  def main(args: Array[String]): Unit = ConnectorRealMySQLSourceRecordIdentityProbe.main(args)
}
