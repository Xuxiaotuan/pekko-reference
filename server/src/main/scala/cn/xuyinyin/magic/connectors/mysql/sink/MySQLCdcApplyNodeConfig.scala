package cn.xuyinyin.magic.connectors.mysql.sink

import cn.xuyinyin.magic.cdc.compat.nodes.WorkflowDSL
import cn.xuyinyin.magic.connectors.jdbc.JdbcPasswordResolver

/** Legacy node adapter; the CDC runtime uses typed configuration directly. */
private[sink] object MySQLCdcApplyNodeConfig {
  def parse(
    node: WorkflowDSL.Node,
    getenv: String => Option[String] = sys.env.get
  ): MySQLCdcApplyConfig = {
    val fields = node.config.fields
    if (fields.contains("password")) {
      throw new IllegalArgumentException("password is not supported for MySQL CDC apply; passwordEnv is required")
    }
    if (fields.contains("mode")) {
      throw new IllegalArgumentException("mode is not supported for MySQL CDC apply")
    }
    MySQLCdcApplyConfig.fromFields(fields, JdbcPasswordResolver.resolve(fields, getenv))
  }

  private[sink] def parseTrustedRuntime(node: WorkflowDSL.Node): MySQLCdcApplyConfig = {
    val fields = node.config.fields
    if (fields.contains("passwordEnv")) {
      throw new IllegalArgumentException("trusted MySQL CDC apply runtime configuration must not contain passwordEnv")
    }
    if (fields.contains("mode")) {
      throw new IllegalArgumentException("mode is not supported for MySQL CDC apply")
    }
    MySQLCdcApplyConfig.fromFields(fields, MySQLCdcApplyConfig.requiredString(fields, "password"))
  }

}
