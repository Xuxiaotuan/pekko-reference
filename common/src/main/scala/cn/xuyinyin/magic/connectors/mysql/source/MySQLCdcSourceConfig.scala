package cn.xuyinyin.magic.connectors.mysql.source

import cn.xuyinyin.magic.cdc.MySQLCdcGroupProtocol

final case class MySQLCdcSourceConfig(
  connectorId: String,
  host: String,
  port: Int,
  database: String,
  table: String,
  username: String,
  password: String,
  serverId: Long,
  maxBatchSize: Int,
  pollIntervalMillis: Int,
  groupProtocol: Option[MySQLCdcGroupProtocol] = None
)
