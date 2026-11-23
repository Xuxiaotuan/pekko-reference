package cn.xuyinyin.magic.cdc.platform

import cn.xuyinyin.magic.cdc.bootstrap.BootstrapJdbc
import cn.xuyinyin.magic.cdc.guardian.ManagedCdcJdbc
import java.sql.Connection

/** Opens the state, source and auxiliary database roles used by one capture. */
private[platform] final class CdcJdbcConnections(managed: Boolean) {

  def state(capture: CdcCaptureSettings): Connection =
    open(capture.controlState.jdbcUrl, capture.controlState.username, capture.controlState.password)

  def gc(capture: CdcCaptureSettings): Connection = {
    val account = capture.gc.get
    open(capture.controlState.jdbcUrl, account.username, account.password)
  }

  def material(capture: CdcCaptureSettings): Connection = capture.materialState match {
    case Some(account) => open(account.jdbcUrl, account.username, account.password)
    case None          => state(capture)
  }

  def captureLog(capture: CdcCaptureSettings): Connection =
    open(capture.controlState.jdbcUrl, capture.sdkState.username, capture.sdkState.password)

  def source(capture: CdcCaptureSettings, observer: Boolean = false, controller: Boolean = false): Connection = {
    require(!(observer && controller), "source roles are mutually exclusive")
    val (username, password) =
      if (controller) (
        capture.sourceControllerUsername.getOrElse(throw new IllegalStateException("source controller missing")),
        capture.sourceControllerPassword.getOrElse(throw new IllegalStateException("source controller password missing")))
      else if (observer) (
        capture.sourceObserverUsername.getOrElse(capture.source.username),
        capture.sourceObserverPassword.getOrElse(capture.source.password))
      else (capture.source.username, capture.source.password)

    open(
      s"jdbc:mysql://${capture.source.host}:${capture.source.port}/${capture.source.database}?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&connectTimeout=5000&socketTimeout=5000",
      username,
      password,
      role = "source")
  }

  def requireStateIdentity(capture: CdcCaptureSettings): Unit = {
    val jdbc = new BootstrapJdbc(() => state(capture), 1)
    jdbc.connection { connection =>
      require(jdbc.query(connection, "SELECT @@server_uuid AS uuid").head("uuid") == capture.stateLineage, "CDC platform state lineage changed")
    }
  }

  private def open(url: String, username: String, password: String, role: String = "control"): Connection =
    ManagedCdcJdbc.open(CdcJdbc.withTimeouts(url), username, password, role, managed)
}
