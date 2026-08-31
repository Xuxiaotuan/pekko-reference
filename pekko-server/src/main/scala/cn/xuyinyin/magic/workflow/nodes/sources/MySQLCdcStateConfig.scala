package cn.xuyinyin.magic.workflow.nodes.sources

import com.typesafe.config.Config

final case class MySQLCdcStateConfig(
  jdbcUrl: String,
  username: String,
  password: String,
  offsetTable: String,
  historyTable: String,
  offsetFlushIntervalMillis: Int
)

object MySQLCdcStateConfig {
  private val StateJdbcPath = "pekko.workflow.mysql-cdc.state-jdbc"
  private val Identifier = "[A-Za-z_][A-Za-z0-9_]*".r

  def load(config: Config): MySQLCdcStateConfig = {
    if (!config.hasPath(StateJdbcPath)) {
      throw new IllegalArgumentException(s"Missing required config: $StateJdbcPath")
    }

    val state = config.getConfig(StateJdbcPath)
    val jdbcUrl = requiredString(state, "url")
    if (!jdbcUrl.startsWith("jdbc:")) {
      throw new IllegalArgumentException("pekko.workflow.mysql-cdc.state-jdbc.url must start with jdbc:")
    }
    if (!jdbcUrl.startsWith("jdbc:mysql:")) {
      throw new IllegalArgumentException("pekko.workflow.mysql-cdc.state-jdbc.url must use jdbc:mysql:")
    }

    MySQLCdcStateConfig(
      jdbcUrl = jdbcUrl,
      username = requiredString(state, "username"),
      password = requiredString(state, "password"),
      offsetTable = identifier(state, "offset-table"),
      historyTable = identifier(state, "history-table"),
      offsetFlushIntervalMillis = nonNegativeInt(state, "offset-flush-interval-ms")
    )
  }

  private def requiredString(config: Config, key: String): String = {
    val path = s"$StateJdbcPath.$key"
    if (!config.hasPath(key)) {
      throw new IllegalArgumentException(s"Missing required config: $path")
    }

    val value = config.getString(key).trim
    if (value.isEmpty) {
      throw new IllegalArgumentException(s"$path must not be blank")
    }
    value
  }

  private def identifier(config: Config, key: String): String = {
    val value = requiredString(config, key)
    if (Identifier.pattern.matcher(value).matches()) value
    else throw new IllegalArgumentException(s"$StateJdbcPath.$key must be a valid identifier")
  }

  private def nonNegativeInt(config: Config, key: String): Int = {
    val path = s"$StateJdbcPath.$key"
    if (!config.hasPath(key)) {
      throw new IllegalArgumentException(s"Missing required config: $path")
    }

    val value = config.getInt(key)
    if (value < 0) {
      throw new IllegalArgumentException(s"$path must be non-negative")
    }
    value
  }
}
