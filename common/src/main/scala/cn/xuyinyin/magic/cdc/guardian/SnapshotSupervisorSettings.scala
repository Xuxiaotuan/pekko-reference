package cn.xuyinyin.magic.cdc.guardian

import cn.xuyinyin.magic.cdc.control.CdcManagedControlStore

import cn.xuyinyin.magic.cdc.control.SchedulingLimits
import cn.xuyinyin.magic.cdc.control.{SnapshotOwnerJdbc, SnapshotSupervisorStore}
import com.typesafe.config.{Config,ConfigFactory}
import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import java.nio.channels.FileChannel
import java.nio.file.{Files,Path,StandardOpenOption}
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import scala.jdk.CollectionConverters._

object SnapshotSupervisorSettings {
  def cutoverIdentity(config:Config):Option[CdcManagedControlStore.CutoverIdentity]=
    Option.when(config.hasPath("pekko.workflow.snapshot-ha.cutover")) {
      val c=config.getConfig("pekko.workflow.snapshot-ha.cutover")
      CdcManagedControlStore.CutoverIdentity(c.getString("deployment-id"),c.getString("host-id"),c.getString("operation-id"))
    }
  private[magic] def exclusiveCdcAccounts(settings:Vector[cn.xuyinyin.magic.cdc.platform.CdcPlatformSettings]):Map[String,Set[String]]=
    settings.flatMap(_.captures).flatMap {c=>
      Vector(SnapshotOwnerJdbc.endpoint(s"jdbc:mysql://${c.source.host}:${c.source.port}/${c.source.database}")->c.source.username,
        SnapshotOwnerJdbc.endpoint(c.sdkState.jdbcUrl)->c.sdkState.username)++
        c.materialState.toVector.map(m=>SnapshotOwnerJdbc.endpoint(m.jdbcUrl)->m.username)
    }.groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap
  private[magic] def appendParameters(url:String,parameters:String):String={
    val existing=url.split("\\?",2).drop(1).flatMap(_.split("&"))
    require(!existing.exists(_.takeWhile(_!='=').equalsIgnoreCase("connectionAttributes")),"control connection attributes cannot be overridden")
    val separator=if(!url.contains("?"))"?" else if(url.endsWith("?") || url.endsWith("&"))"" else "&"
    url+separator+parameters
  }
  private[magic] def workerMain(worker:Config):String =
    if(worker.hasPath("pekko.cdc-standalone.managed-worker") && worker.getBoolean("pekko.cdc-standalone.managed-worker")) {
      require(worker.hasPath("pekko.workflow.cdc-platform.enabled") && worker.getBoolean("pekko.workflow.cdc-platform.enabled"),"managed CDC worker requires CDC platform")
      "cn.xuyinyin.magic.cdc.runtime.CdcRuntimeMain"
    } else throw new IllegalArgumentException("Workflow workers are not part of the CDC runtime")
  private[magic] def validateWorker(worker:Config,controller:Config):Unit={
    require(SnapshotOwnerBootstrap.enabled(controller) && SnapshotOwnerBootstrap.enabled(worker),"both supervisor and worker require explicit snapshot HA")
    workerMain(worker) // Enforce the CDC-only worker contract.
    require(worker.getString("pekko.workflow.snapshot-ha.deployment-id")==controller.getString("pekko.workflow.snapshot-ha.deployment-id"),"worker deployment differs from guardian")
    val prefix="pekko.workflow.table-scheduling.jdbc."
    Vector("url","username","password").foreach(k=>require(worker.getString(prefix+k)==controller.getString(prefix+k),"worker scheduling database differs from supervisor"))
    require(cutoverIdentity(worker)==cutoverIdentity(controller),"worker and supervisor cutover policy differs")
    require(SnapshotOwnerBootstrap.bindingPlans(worker)==SnapshotOwnerBootstrap.bindingPlans(controller),"worker and supervisor credential binding plans differ")
  }
}

/** Explicit operator-owned config and pinned guardian key; no discovery or trust-on-reconnect. */
final class SnapshotSupervisorSettings(val path:Path) extends AutoCloseable {
  val config:Config=ConfigFactory.parseFile(path.toFile).withFallback(ConfigFactory.load()).resolve()
  private val root=config.getConfig("pekko.workflow.snapshot-supervisor")
  val binding=GuardianBinding(config.getString("pekko.workflow.snapshot-ha.deployment-id"),root.getString("host-id"),root.getString("guardian-boot"),root.getString("public-key"))
  val maxWorkers=root.getInt("max-workers")
  val socket=Path.of(root.getString("socket"))
  val secret=Files.readAllBytes(Path.of(root.getString("auth-key-file")))
  require(secret.length>=32)
  val workerConfig=Path.of(root.getString("worker-config-file"))
  SnapshotSupervisorSettings.workerMain(ConfigFactory.parseFile(workerConfig.toFile).withFallback(ConfigFactory.load()).resolve())
  private val scheduling=config.getConfig("pekko.workflow.table-scheduling")
  private val jdbc=scheduling.getConfig("jdbc")
  private val hc=new HikariConfig();hc.setJdbcUrl(SnapshotSupervisorSettings.appendParameters(jdbc.getString("url"),"connectTimeout=5000&socketTimeout=10000&connectionAttributes=pekko_owner:supervisor,pekko_role:control"))
  hc.setUsername(jdbc.getString("username"));hc.setPassword(jdbc.getString("password"));hc.setMaximumPoolSize(3);hc.setMinimumIdle(0);hc.setConnectionTimeout(5000)
  val dataSource=new HikariDataSource(hc)
  val limits=SchedulingLimits(scheduling.getInt("max-active-tables"),scheduling.getInt("max-active-tables-per-node"),scheduling.getInt("max-connections-per-instance"),scheduling.getInt("max-concurrent-starts"),scheduling.getBytes("max-page-reservation"),scheduling.getInt("candidate-page"))
  private val cutover=SnapshotSupervisorSettings.cutoverIdentity(config)
  private val controlStore=new CdcManagedControlStore(dataSource,limits,cutover)
  private val ownership=controlStore.ownership
  val metadata=new SnapshotSupervisorStore(controlStore,ownership,binding,maxWorkers)
  def lock(role:String):AutoCloseable={
    val channel=FileChannel.open(Path.of(root.getString(role+"-lock")),StandardOpenOption.CREATE,StandardOpenOption.WRITE)
    val lock=try channel.tryLock()catch{case e:Exception=>channel.close();throw e}
    if(lock==null){channel.close();throw new IllegalStateException(role+" process lock held")}
    ()=>{lock.release();channel.close()}
  }
  def signingKey=KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder.decode(Files.readString(Path.of(root.getString("private-key-file"))).trim)))
  private val heap=root.getInt("worker-xmx-mib");require(heap>=128 && heap<=65536)
  def profileHash:String=cn.xuyinyin.magic.cdc.snapshot.SnapshotSplitState.sha256(Files.readString(workerConfig)+"|"+heap+"|"+System.getProperty("java.class.path"))
  def validateWorkerProfile():Unit=SnapshotSupervisorSettings.validateWorker(ConfigFactory.parseFile(workerConfig.toFile).withFallback(ConfigFactory.load()).resolve(),config)
  def builder(expected:String)(ticket:SnapshotWorkerLaunch):ProcessBuilder={
    validateWorkerProfile()
    require(profileHash==expected && ticket.profileHash==expected,"worker configuration changed")
    val worker=ConfigFactory.parseFile(workerConfig.toFile).withFallback(ConfigFactory.load()).resolve()
    SnapshotSupervisorSettings.validateWorker(worker,config)
    val main=SnapshotSupervisorSettings.workerMain(worker)
    val builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx"+heap+"m",
      "-Dconfig.file="+workerConfig.toAbsolutePath,"-Dpekko.workflow.snapshot-ha.owner-id="+ticket.bootId,
      "-Dpekko.workflow.snapshot-ha.supervisor-config="+path.toAbsolutePath,"-Dpekko.workflow.snapshot-ha.launch-profile="+expected,
      "-cp",System.getProperty("java.class.path"),main)
    builder.inheritIO();builder
  }
  def observer:MySqlOwnerDrainObserver={
    val journal=config.getConfig("pekko-persistence-jdbc.shared-databases.slick.db")
    val routes=(Vector((jdbc.getString("url"),jdbc.getString("username"),jdbc.getString("password")),(journal.getString("url"),journal.getString("user"),journal.getString("password")))++
      config.getConfigList("pekko.workflow.snapshot-ha.instances").asScala.map(c=>(c.getString("url"),c.getString("username"),c.getString("password")))).map { case(url,user,password)=>SnapshotOwnerJdbc.endpoint(url)->(url,user,password)}.toMap
    val worker=ConfigFactory.parseFile(workerConfig.toFile).withFallback(ConfigFactory.load()).resolve()
    val exclusive=SnapshotSupervisorSettings.exclusiveCdcAccounts(cn.xuyinyin.magic.cdc.platform.CdcPlatformSettings.load(worker).toVector)
    new MySqlOwnerDrainObserver(ownership,key=>{
      val (url,user,password)=routes.getOrElse(key,throw new IllegalStateException("unregistered observation endpoint"))
      java.sql.DriverManager.getConnection(SnapshotSupervisorSettings.appendParameters(url,"connectTimeout=5000&socketTimeout=10000&connectionAttributes=pekko_owner:observer,pekko_role:observer"),user,password)
    },exclusiveAccounts=exclusive)
  }
  /** One bounded, independently observable control-table insert per recovery tick. */
  def wakeCdc(boot:String,instance:(String,String,Long)):Unit={
    val worker=ConfigFactory.parseFile(workerConfig.toFile).withFallback(ConfigFactory.load()).resolve()
    cn.xuyinyin.magic.cdc.platform.CdcPlatformSettings.load(worker).foreach {cdc=>
      val state=cn.xuyinyin.magic.connectors.mysql.source.MySQLCdcStateConfig.load(worker)
      val control=new cn.xuyinyin.magic.cdc.platform.CdcTaskStore(()=>java.sql.DriverManager.getConnection(
        SnapshotSupervisorSettings.appendParameters(state.jdbcUrl,"connectTimeout=5000&socketTimeout=5000"),state.username,state.password),cdc.limits)
      if(control.managedOwnerIs(boot))cdc.captures.foreach {capture=>
        val url=s"jdbc:mysql://${capture.source.host}:${capture.source.port}/${capture.source.database}?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=5000"
        val endpoint=SnapshotOwnerJdbc.endpoint(url)
        if(endpoint==instance._1){
          val ownership=metadata.ownerRegistry
          require(ownership.owner(boot).exists(o=>Set("fenced","drain_wait")(o.phase)) && instance._2==capture.sourceLineage &&
            ownership.databaseUsers(boot,endpoint)(capture.source.username),"CDC wake lacks isolated source manifest")
          val user=capture.sourceObserverUsername.getOrElse(capture.source.username)
          val password=capture.sourceObserverPassword.getOrElse(capture.source.password)
          val c=java.sql.DriverManager.getConnection(url,user,password)
          try {
            val (sourceUuid,sourceStarted,_)=SnapshotOwnerJdbc.databaseIdentity(c)
            require(sourceUuid==capture.sourceLineage && math.abs(sourceStarted-instance._3)<=3000,"CDC wake source instance changed")
            val grants=c.createStatement();grants.setQueryTimeout(5)
            val visible=try {val r=grants.executeQuery("SHOW GRANTS");val b=Vector.newBuilder[String];try while(r.next())b+=r.getString(1)finally r.close();b.result()}finally grants.close()
            require(cn.xuyinyin.magic.cdc.guardian.DrainObservationPolicy.hasProcessPrivilege(visible),"CDC wake observer lacks PROCESS visibility")
            val q=c.prepareStatement("SELECT 1 FROM information_schema.PROCESSLIST WHERE USER=? AND COMMAND LIKE 'Binlog Dump%' LIMIT 1");q.setQueryTimeout(5)
            val stalled=try {q.setString(1,capture.source.username);val r=q.executeQuery();try r.next() finally r.close()}finally q.close()
            if(stalled){
              val wake=cn.xuyinyin.magic.connectors.mysql.source.capture.CaptureWake
              val slot=wake.nextAvailable {n=>
                val q=c.prepareStatement("SELECT 1 FROM pekko_capture_control WHERE operation_id=?");q.setQueryTimeout(5)
                try {q.setString(1,wake.operation(capture.id,boot,n));val r=q.executeQuery();try r.next() finally r.close()}finally q.close()
              }
              slot.foreach {slot=>
              val operation=wake.operation(capture.id,boot,slot)
              val generation=wake.generation(boot,slot)
              if(capture.sourceFenceV2){
                require(capture.sourceObserverUsername.exists(_!=capture.source.username) && capture.sourceObserverPassword.nonEmpty,"v2 wake requires a distinct trusted observer")
                c.setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false)
                val writer=java.sql.DriverManager.getConnection(url,capture.source.username,capture.source.password)
                try {
                  writer.setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED);writer.setAutoCommit(false)
                  cn.xuyinyin.magic.connectors.mysql.source.capture.CaptureControlFence.protect(c,writer,sourceUuid,capture.source.database)
                    .writeOpen(operation,"WAKE",wake.table(capture.id),generation)
                  writer.commit()
                } finally {try writer.close()finally c.rollback()}
              }else{
                val insert=c.prepareStatement("INSERT IGNORE INTO pekko_capture_control VALUES(?,'WAKE',?,?)");insert.setQueryTimeout(5)
                try {insert.setString(1,operation);insert.setString(2,wake.table(capture.id));insert.setString(3,generation);insert.executeUpdate()}finally insert.close()
              }
              val check=c.prepareStatement("SELECT action,table_name,generation FROM pekko_capture_control WHERE operation_id=?");check.setQueryTimeout(5)
              try {check.setString(1,operation);val r=check.executeQuery();try require(r.next() && r.getString(1)=="WAKE" && r.getString(2)==wake.table(capture.id) && r.getString(3)==generation && !r.next(),"CDC wake marker conflict")finally r.close()}finally check.close()
              }
            }
          }finally c.close()
        }
      }
    }
  }
  def cdcNeedsIsolation(boot:String,expectedProfile:String):Boolean={
    val worker=ConfigFactory.parseFile(workerConfig.toFile).withFallback(ConfigFactory.load()).resolve()
    cn.xuyinyin.magic.cdc.platform.CdcPlatformSettings.load(worker).exists {cdc=>
      require(profileHash==expectedProfile,"worker configuration changed")
      val launch=metadata.launch(boot).getOrElse(throw new IllegalStateException("worker launch missing"))
      require(launch.profileHash==expectedProfile,"worker launch profile changed")
      val identity=launch.identity.getOrElse(throw new IllegalStateException("worker identity missing"))
      require(identity.bootId==boot && identity.deploymentId==binding.deploymentId,"guardian launch identity changed")
      val expected=cn.xuyinyin.magic.cdc.platform.CdcManagedOwner(boot,binding.deploymentId,binding.hostId,binding.bootId,
        binding.publicKey,identity.pid,identity.startIdentity,expectedProfile)
      val state=cn.xuyinyin.magic.connectors.mysql.source.MySQLCdcStateConfig.load(worker)
      val store=new cn.xuyinyin.magic.cdc.platform.CdcTaskStore(()=>java.sql.DriverManager.getConnection(
        SnapshotSupervisorSettings.appendParameters(state.jdbcUrl,"connectTimeout=5000&socketTimeout=5000"),state.username,state.password),cdc.limits)
      store.managedOwnerNeedsIsolation(expected)
    }
  }
  def close():Unit=dataSource.close()
}
