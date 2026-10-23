package cn.xuyinyin.magic.workflow.scheduling

import com.typesafe.config.{Config,ConfigFactory}
import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import java.sql.DriverManager
import scala.jdk.CollectionConverters._

/** Explicit opt-in. Schema provisioning and a quiescent migration baseline are operator prerequisites. */
object SnapshotOwnerBootstrap {
  def enabled(config:Config):Boolean=config.hasPath("pekko.workflow.snapshot-ha.enabled") && config.getBoolean("pekko.workflow.snapshot-ha.enabled")
  def prepare(config:Config):Config={
    if(!enabled(config))return config
    require(config.getBoolean("pekko.workflow.table-scheduling.enabled"),"HA requires durable table scheduling")
    require(config.getString("pekko.cluster.downing-provider-class").isEmpty,"HA requires externally proven retirement; automatic downing is unsupported")
    require(config.getString("pekko.cluster.auto-down-unreachable-after")=="off","automatic downing bypasses the isolation proof")
    val boot=config.getString("pekko.workflow.snapshot-ha.owner-id")
    val identity=SnapshotOwnerIdentity(boot,config.getString("pekko.workflow.snapshot-ha.deployment-id"),ProcessHandle.current().pid(),ProcessHandle.current().info().startInstant().orElseThrow().toString)
    val scheduling=config.getConfig("pekko.workflow.table-scheduling")
    val db=scheduling.getConfig("jdbc")
    val pool=new HikariConfig();pool.setJdbcUrl(SnapshotOwnerJdbc.taggedUrl(db.getString("url"),identity,"control"))
    pool.setUsername(db.getString("username"));pool.setPassword(db.getString("password"));pool.setMaximumPoolSize(4);pool.setMinimumIdle(0);pool.setConnectionTimeout(5000)
    val ds=new HikariDataSource(pool)
    try {
      val limits=SchedulingLimits(scheduling.getInt("max-active-tables"),scheduling.getInt("max-active-tables-per-node"),scheduling.getInt("max-connections-per-instance"),scheduling.getInt("max-concurrent-starts"),scheduling.getBytes("max-page-reservation"),scheduling.getInt("candidate-page"))
      val store=new TableSchedulingStore(ds,limits,ownershipEnabled=true)
      val ha=store.ownership.get;ha.registerBootstrapOwner(identity)
      val journal=config.getConfig("pekko-persistence-jdbc.shared-databases.slick.db")
      val endpoints=Vector((db.getString("url"),db.getString("username"),db.getString("password")),
        (journal.getString("url"),journal.getString("user"),journal.getString("password"))) ++
        config.getConfigList("pekko.workflow.snapshot-ha.instances").asScala.map(c=>(c.getString("url"),c.getString("username"),c.getString("password")))
      val routes=endpoints.map { case (url,user,password)=>
        val c=DriverManager.getConnection(SnapshotOwnerJdbc.taggedUrl(url,identity,"control"),user,password)
        try {val (uuid,started,id)=SnapshotOwnerJdbc.databaseIdentity(c)
          if(url==journal.getString("url")) {
            Vector("event_journal","event_tag","snapshot").foreach { table=>
              val q=c.prepareStatement("SELECT ENGINE FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?")
              try {q.setString(1,table);val r=q.executeQuery();try require(r.next() && r.getString(1)=="InnoDB","managed Journal/Snapshot tables must be InnoDB")finally r.close()}finally q.close()
            }
          }
          ha.registerSession(boot,SnapshotOwnerJdbc.endpoint(url),uuid,started,id,"control",SnapshotOwnerJdbc.databaseUser(c));SnapshotOwnerJdbc.endpoint(url)->uuid}finally c.close()
      }.toMap
      SnapshotOwnerJdbc.install(SnapshotOwnerJdbc.Context(identity,store,routes,()=>ds.close()))
      // Metadata/control borrows are registered using their own connection, avoiding recursive pool acquisition.
      store.onBorrow=c=>ha.registerControlSession(c,identity.bootId)
      ConfigFactory.parseMap(Map[String,AnyRef](
        "pekko-persistence-jdbc.shared-databases.slick.db.driver"->classOf[SnapshotOwnerJdbcDriver].getName,
        "pekko-persistence-jdbc.shared-databases.slick.db.url"->SnapshotOwnerJdbc.taggedUrl(journal.getString("url"),identity,"journal"),
        "pekko.cluster.sharding.remember-entities"->java.lang.Boolean.FALSE,
        "pekko.cluster.sharding.buffer-size"->Int.box(64),
        "pekko.cluster.roles"->(config.getStringList("pekko.cluster.roles").asScala.toVector:+("snapshot-owner-"+boot)).asJava
      ).asJava).withFallback(config)
    } catch {case e:Exception=>ds.close();throw e}
  }
}
