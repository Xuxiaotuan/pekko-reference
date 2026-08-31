package cn.xuyinyin.magic.config

import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import cn.xuyinyin.magic.workflow.nodes.sources.MySQLCdcStateConfig

/**
 * 配置验证器
 * 
 * 验证应用程序配置的完整性和正确性
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-28
 */
object ConfigValidator {
  
  private val logger = Logger(getClass)
  
  /**
   * 验证配置
   * 
   * @param config 配置对象
   * @return 验证结果（成功返回None，失败返回错误消息列表）
   */
  def validate(config: Config): Option[List[String]] = {
    val errors = scala.collection.mutable.ListBuffer[String]()
    
    // 验证集群配置
    validateClusterConfig(config, errors)
    
    // 验证远程配置
    validateRemoteConfig(config, errors)
    
    // 验证持久化配置
    validatePersistenceConfig(config, errors)
    
    // 验证工作流配置
    validateWorkflowConfig(config, errors)

    validateMySQLCdcStateConfig(config, errors)
    
    // 验证Sharding配置
    validateShardingConfig(config, errors)
    
    if (errors.isEmpty) {
      logger.info("Configuration validation passed")
      None
    } else {
      logger.error(s"Configuration validation failed with ${errors.size} errors:")
      errors.foreach(err => logger.error(s"  - $err"))
      Some(errors.toList)
    }
  }

  def validateOrThrow(config: Config): Unit =
    validate(config).foreach(errors => throw new IllegalArgumentException(errors.mkString("Invalid configuration: ", "; ", "")))
  
  private def validateClusterConfig(config: Config, errors: scala.collection.mutable.ListBuffer[String]): Unit = {
    try {
      // 验证种子节点
      if (!config.hasPath("pekko.cluster.seed-nodes")) {
        errors += "Missing required config: pekko.cluster.seed-nodes"
      } else {
        val seedNodes = config.getStringList("pekko.cluster.seed-nodes")
        val bootstrapEnabled = config.hasPath("pekko.workflow.cluster-bootstrap.enabled") &&
          config.getBoolean("pekko.workflow.cluster-bootstrap.enabled")
        if (bootstrapEnabled && !seedNodes.isEmpty) {
          errors += "pekko.cluster.seed-nodes must be empty when pekko.workflow.cluster-bootstrap.enabled is true"
        } else if (!bootstrapEnabled && seedNodes.isEmpty) {
          errors += "pekko.cluster.seed-nodes cannot be empty when pekko.workflow.cluster-bootstrap.enabled is false"
        }
      }
      
      // 验证角色
      if (!config.hasPath("pekko.cluster.roles")) {
        errors += "Missing required config: pekko.cluster.roles"
      } else {
        val roles = config.getStringList("pekko.cluster.roles")
        if (roles.isEmpty) {
          errors += "pekko.cluster.roles cannot be empty"
        }
      }
      
      // 验证故障检测器配置
      if (config.hasPath("pekko.cluster.failure-detector.threshold")) {
        val threshold = config.getDouble("pekko.cluster.failure-detector.threshold")
        if (threshold < 4.0 || threshold > 20.0) {
          errors += s"pekko.cluster.failure-detector.threshold should be between 4.0 and 20.0, got $threshold"
        }
      }
      
    } catch {
      case e: Exception =>
        errors += s"Error validating cluster config: ${e.getMessage}"
    }
  }
  
  private def validateRemoteConfig(config: Config, errors: scala.collection.mutable.ListBuffer[String]): Unit = {
    try {
      // 验证hostname
      if (!config.hasPath("pekko.remote.artery.canonical.hostname")) {
        errors += "Missing required config: pekko.remote.artery.canonical.hostname"
      } else {
        val hostname = config.getString("pekko.remote.artery.canonical.hostname")
        if (hostname.isEmpty) {
          errors += "pekko.remote.artery.canonical.hostname cannot be empty"
        }
      }
      
      // 验证port
      if (!config.hasPath("pekko.remote.artery.canonical.port")) {
        errors += "Missing required config: pekko.remote.artery.canonical.port"
      } else {
        val port = config.getInt("pekko.remote.artery.canonical.port")
        if (port < 1024 || port > 65535) {
          errors += s"pekko.remote.artery.canonical.port should be between 1024 and 65535, got $port"
        }
      }
      
    } catch {
      case e: Exception =>
        errors += s"Error validating remote config: ${e.getMessage}"
    }
  }
  
  private def validatePersistenceConfig(config: Config, errors: scala.collection.mutable.ListBuffer[String]): Unit = {
    try {
      val journalPath = "pekko.persistence.journal.plugin"
      val snapshotPath = "pekko.persistence.snapshot-store.plugin"
      if (!config.hasPath(journalPath)) {
        errors += "Missing required config: pekko.persistence.journal.plugin"
      }
      if (!config.hasPath(snapshotPath)) {
        errors += "Missing required config: pekko.persistence.snapshot-store.plugin"
      }

      if (config.hasPath(journalPath)) {
        val journalPlugin = config.getString(journalPath)
        if (journalPlugin.contains("leveldb")) {
          if (!config.hasPath("pekko.persistence.journal.leveldb.dir")) {
            errors += "Missing required config: pekko.persistence.journal.leveldb.dir"
          }
        }
      }

      if (config.hasPath(journalPath) && config.hasPath(snapshotPath)) {
        val journalPlugin = config.getString(journalPath)
        val snapshotPlugin = config.getString(snapshotPath)
        val jdbcJournal = journalPlugin == "jdbc-journal"
        val jdbcSnapshot = snapshotPlugin == "jdbc-snapshot-store"
        if (jdbcJournal != jdbcSnapshot) {
          errors += "JDBC journal and snapshot-store plugins must be configured together"
        } else if (jdbcJournal) {
          validateJdbcPersistenceConfig(config, errors)
        }
      }
      
    } catch {
      case e: Exception =>
        errors += s"Error validating persistence config: ${e.getMessage}"
    }
  }

  private def validateJdbcPersistenceConfig(config: Config, errors: scala.collection.mutable.ListBuffer[String]): Unit = {
    val sharedDb = "pekko-persistence-jdbc.shared-databases.slick"
    val profilePath = s"$sharedDb.profile"
    val urlPath = s"$sharedDb.db.url"
    val driverPath = s"$sharedDb.db.driver"
    val requiredPaths = Seq(profilePath, urlPath, driverPath)
    requiredPaths.foreach { path =>
      if (!config.hasPath(path) || config.getString(path).trim.isEmpty) {
        errors += s"Missing required config: $path"
      }
    }

    Seq("jdbc-journal", "jdbc-snapshot-store", "jdbc-read-journal").foreach { plugin =>
      val path = s"$plugin.use-shared-db"
      if (!config.hasPath(path) || config.getString(path) != "slick") {
        errors += s"$plugin must use the shared slick database"
      }
    }
    if (!config.hasPath("jdbc-journal.tables.event_journal.schemaName") ||
        !config.hasPath("jdbc-snapshot-store.tables.snapshot.schemaName")) {
      errors += "JDBC persistence schema settings are required"
    }

    if (requiredPaths.forall(config.hasPath)) {
      val profile = config.getString(profilePath)
      val url = config.getString(urlPath)
      val driver = config.getString(driverPath)
      if (!url.startsWith("jdbc:")) {
        errors += s"JDBC URL must start with jdbc:, got $url"
      }
      if (profile == "slick.jdbc.MySQLProfile$" && (!driver.contains("mysql") || !url.startsWith("jdbc:mysql:"))) {
        errors += "MySQL Slick profile requires a MySQL JDBC driver and jdbc:mysql: URL"
      }
      if (profile == "slick.jdbc.H2Profile$" && (!driver.contains("h2") || !url.startsWith("jdbc:h2:"))) {
        errors += "H2 Slick profile requires an H2 JDBC driver and jdbc:h2: URL"
      }
    }
  }
  
  private def validateWorkflowConfig(config: Config, errors: scala.collection.mutable.ListBuffer[String]): Unit = {
    try {
      // 验证快照频率
      if (config.hasPath("pekko.workflow.event-sourcing.snapshot-every")) {
        val snapshotEvery = config.getInt("pekko.workflow.event-sourcing.snapshot-every")
        if (snapshotEvery < 10 || snapshotEvery > 10000) {
          errors += s"pekko.workflow.event-sourcing.snapshot-every should be between 10 and 10000, got $snapshotEvery"
        }
      }
      
      // 验证快照保留数量
      if (config.hasPath("pekko.workflow.event-sourcing.keep-n-snapshots")) {
        val keepSnapshots = config.getInt("pekko.workflow.event-sourcing.keep-n-snapshots")
        if (keepSnapshots < 1 || keepSnapshots > 10) {
          errors += s"pekko.workflow.event-sourcing.keep-n-snapshots should be between 1 and 10, got $keepSnapshots"
        }
      }
      
    } catch {
      case e: Exception =>
        errors += s"Error validating workflow config: ${e.getMessage}"
    }
  }

  private def validateMySQLCdcStateConfig(config: Config, errors: scala.collection.mutable.ListBuffer[String]): Unit = {
    val enabledPath = "pekko.workflow.mysql-cdc.enabled"
    if (config.hasPath(enabledPath) && config.getBoolean(enabledPath)) {
      try {
        MySQLCdcStateConfig.load(config)
      } catch {
        case e: IllegalArgumentException => errors += e.getMessage
        case _: Exception => errors += "Invalid MySQL CDC state JDBC configuration"
      }
    }
  }
  
  private def validateShardingConfig(config: Config, errors: scala.collection.mutable.ListBuffer[String]): Unit = {
    try {
      // 验证分片数量
      if (config.hasPath("pekko.cluster.sharding.number-of-shards")) {
        val numberOfShards = config.getInt("pekko.cluster.sharding.number-of-shards")
        if (numberOfShards < 10 || numberOfShards > 1000) {
          errors += s"pekko.cluster.sharding.number-of-shards should be between 10 and 1000, got $numberOfShards"
        }
      }
      
      // 验证角色
      if (config.hasPath("pekko.cluster.sharding.role")) {
        val role = config.getString("pekko.cluster.sharding.role")
        if (role.isEmpty) {
          errors += "pekko.cluster.sharding.role cannot be empty"
        }
      }
      
      // 验证remember-entities配置
      if (config.hasPath("pekko.cluster.sharding.remember-entities")) {
        val rememberEntities = config.getBoolean("pekko.cluster.sharding.remember-entities")
        if (rememberEntities && !config.hasPath("pekko.cluster.sharding.remember-entities-store")) {
          errors += "pekko.cluster.sharding.remember-entities-store must be set when remember-entities is enabled"
        }
      }
      
    } catch {
      case e: Exception =>
        errors += s"Error validating sharding config: ${e.getMessage}"
    }
  }
  
  /**
   * 打印配置摘要
   */
  def printConfigSummary(config: Config): Unit = {
    logger.info("=== Configuration Summary ===")
    
    try {
      logger.info(s"System Name: ${config.getString("pekko.pekko-sys")}")
      logger.info(s"Project Version: ${config.getString("pekko.project-version")}")
      
      logger.info("Cluster:")
      logger.info(s"  Seed Nodes: ${config.getStringList("pekko.cluster.seed-nodes")}")
      logger.info(s"  Roles: ${config.getStringList("pekko.cluster.roles")}")
      
      logger.info("Remote:")
      logger.info(s"  Hostname: ${config.getString("pekko.remote.artery.canonical.hostname")}")
      logger.info(s"  Port: ${config.getInt("pekko.remote.artery.canonical.port")}")
      
      logger.info("Sharding:")
      logger.info(s"  Number of Shards: ${config.getInt("pekko.cluster.sharding.number-of-shards")}")
      logger.info(s"  Role: ${config.getString("pekko.cluster.sharding.role")}")
      logger.info(s"  Remember Entities: ${config.getBoolean("pekko.cluster.sharding.remember-entities")}")
      
      logger.info("Persistence:")
      logger.info(s"  Journal Plugin: ${config.getString("pekko.persistence.journal.plugin")}")
      logger.info(s"  Snapshot Store Plugin: ${config.getString("pekko.persistence.snapshot-store.plugin")}")
      
      logger.info("Workflow:")
      logger.info(s"  Snapshot Every: ${config.getInt("pekko.workflow.event-sourcing.snapshot-every")}")
      logger.info(s"  Keep N Snapshots: ${config.getInt("pekko.workflow.event-sourcing.keep-n-snapshots")}")
      
    } catch {
      case e: Exception =>
        logger.error(s"Error printing config summary: ${e.getMessage}")
    }
    
    logger.info("=============================")
  }
}
