package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.connectors.mysql.source.{RealMySQLDeferredAckProbe => ConnectorRealMySQLDeferredAckProbe}

/** Historical experiment command entry; implementation follows the connector package. */
object RealMySQLDeferredAckProbe {
  def main(args: Array[String]): Unit = ConnectorRealMySQLDeferredAckProbe.main(args)
}
