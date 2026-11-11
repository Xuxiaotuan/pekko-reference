package cn.xuyinyin.magic.cdc.guardian

import java.sql.{Connection, Driver, DriverPropertyInfo}
import java.util.Properties
import com.zaxxer.hikari.HikariConfig
import scala.util.control.NonFatal

/** Per-process managed JDBC session. Registration finishes before a connection is returned. */
object ManagedCdcJdbc {
  final case class Context(identity: SnapshotOwnerIdentity, routes: Map[String, String],
    register: (String, String, Long, Long, String, String) => Unit,
    databaseUsers: (String, String) => Set[String], instances: String => Vector[(String, String, Long)], close: () => Unit)

  @volatile private var installed: Option[Context] = None
  def context: Option[Context] = installed
  private[magic] def install(value: Context): Unit = synchronized {
    require(installed.isEmpty, "one managed owner per JVM; boot identities may not be replaced")
    installed = Some(value)
  }
  def endpoint(url: String): String = {
    val uri = new java.net.URI(url.stripPrefix("jdbc:"))
    require(uri.getScheme == "mysql" && uri.getHost != null, "managed snapshots require a fixed MySQL endpoint")
    uri.getHost.toLowerCase(java.util.Locale.ROOT) + ":" + (if (uri.getPort < 0) 3306 else uri.getPort)
  }
  def taggedUrl(url: String, identity: SnapshotOwnerIdentity, role: String): String = {
    require(Set("journal", "source", "target", "control").contains(role))
    val parts = url.split("\\?", 2); val parameters = if (parts.length == 1) Vector.empty else parts(1).split("&").toVector
    require(!parameters.exists(_.startsWith("connectionAttributes=")), "managed connection attributes cannot be overridden")
    parts(0) + "?" + (parameters :+ s"connectionAttributes=pekko_owner:${identity.bootId},pekko_role:$role" :+ s"pekkoOwnerRole=$role").mkString("&")
  }
  def configure(config: HikariConfig, role: String): Unit = context.foreach { ctx =>
    require(ctx.routes.contains(endpoint(config.getJdbcUrl)), "database endpoint is not in the fixed managed manifest")
    config.setJdbcUrl(taggedUrl(config.getJdbcUrl, ctx.identity, role))
    config.setDriverClassName(classOf[ManagedCdcJdbcDriver].getName)
  }
  def open(url: String, user: String, password: String, role: String, managed: Boolean = false): Connection = {
    if (!managed) java.sql.DriverManager.getConnection(url, user, password)
    else {
      val ctx = context.getOrElse(throw new IllegalStateException("managed owner bootstrap is missing"))
      require(ctx.routes.contains(endpoint(url)), "database endpoint is not in the fixed managed manifest")
      val props = new Properties(); props.setProperty("user", user); props.setProperty("password", password)
      new ManagedCdcJdbcDriver().connect(taggedUrl(url, ctx.identity, role), props)
    }
  }
  def databaseUser(c: Connection): String = {
    val q = c.createStatement(); q.setQueryTimeout(5)
    try { val r = q.executeQuery("SELECT USER()"); try { require(r.next()); val name = r.getString(1); val split = name.lastIndexOf('@'); if (split < 0) name else name.substring(0, split) } finally r.close() } finally q.close()
  }
  def databaseIdentity(c: Connection): (String, Long, Long) = MySqlDatabaseIdentity.read(c)
}

final class ManagedCdcJdbcDriver extends Driver {
  private val delegate = new com.mysql.cj.jdbc.Driver()
  override def connect(url: String, properties: Properties): Connection = {
    if (!acceptsURL(url)) return null
    val ctx = ManagedCdcJdbc.context.getOrElse(throw new IllegalStateException("managed owner bootstrap is missing"))
    val key = ManagedCdcJdbc.endpoint(url)
    val expected = ctx.routes.getOrElse(key, throw new IllegalStateException("unregistered MySQL endpoint"))
    val role = url.split("[?&]").find(_.startsWith("pekkoOwnerRole=")).map(_.stripPrefix("pekkoOwnerRole="))
      .filter(Set("journal", "source", "target", "control")).getOrElse(throw new IllegalStateException("managed database role is missing"))
    val cleaned = url.replaceAll("[&]pekkoOwnerRole=[a-z]+", "")
    val c = delegate.connect(cleaned, properties)
    try {
      val (uuid, started, id) = ManagedCdcJdbc.databaseIdentity(c)
      require(uuid == expected, "database identity changed")
      ctx.register(key, uuid, started, id, role, ManagedCdcJdbc.databaseUser(c)); c
    } catch { case NonFatal(e) => try c.close() catch { case NonFatal(close) => e.addSuppressed(close) }; throw e }
  }
  override def acceptsURL(url: String): Boolean = url != null && url.startsWith("jdbc:mysql:")
  override def getPropertyInfo(url: String, p: Properties): Array[DriverPropertyInfo] = delegate.getPropertyInfo(url, p)
  override def getMajorVersion: Int = delegate.getMajorVersion
  override def getMinorVersion: Int = delegate.getMinorVersion
  override def jdbcCompliant(): Boolean = delegate.jdbcCompliant()
  override def getParentLogger: java.util.logging.Logger = delegate.getParentLogger
}
