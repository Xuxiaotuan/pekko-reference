package cn.xuyinyin.magic.workflow.scheduling

import com.typesafe.config.{Config,ConfigFactory}
import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import java.nio.channels.FileChannel
import java.nio.file.{Files,Path,StandardOpenOption}
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.{Base64,UUID}
import scala.jdk.CollectionConverters._

object SnapshotSupervisorSettings {
  private[scheduling] def appendParameters(url:String,parameters:String):String={
    val existing=url.split("\\?",2).drop(1).flatMap(_.split("&"))
    require(!existing.exists(_.takeWhile(_!='=').equalsIgnoreCase("connectionAttributes")),"control connection attributes cannot be overridden")
    val separator=if(!url.contains("?"))"?" else if(url.endsWith("?") || url.endsWith("&"))"" else "&"
    url+separator+parameters
  }
  private[scheduling] def validateWorker(worker:Config,controller:Config):Unit={
    require(SnapshotOwnerBootstrap.enabled(controller) && SnapshotOwnerBootstrap.enabled(worker),"both supervisor and worker require explicit snapshot HA")
    require(worker.getBoolean("pekko.workflow.table-scheduling.enabled") && controller.getBoolean("pekko.workflow.table-scheduling.enabled"),"managed workers require durable scheduling")
    require(worker.getString("pekko.workflow.snapshot-ha.deployment-id")==controller.getString("pekko.workflow.snapshot-ha.deployment-id"),"worker deployment differs from guardian")
    val prefix="pekko.workflow.table-scheduling.jdbc."
    Vector("url","username","password").foreach(k=>require(worker.getString(prefix+k)==controller.getString(prefix+k),"worker scheduling database differs from supervisor"))
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
  private val scheduling=config.getConfig("pekko.workflow.table-scheduling")
  private val jdbc=scheduling.getConfig("jdbc")
  private val hc=new HikariConfig();hc.setJdbcUrl(SnapshotSupervisorSettings.appendParameters(jdbc.getString("url"),"connectTimeout=5000&socketTimeout=10000&connectionAttributes=pekko_owner:supervisor,pekko_role:control"))
  hc.setUsername(jdbc.getString("username"));hc.setPassword(jdbc.getString("password"));hc.setMaximumPoolSize(3);hc.setMinimumIdle(0);hc.setConnectionTimeout(5000)
  val dataSource=new HikariDataSource(hc)
  val limits=SchedulingLimits(scheduling.getInt("max-active-tables"),scheduling.getInt("max-active-tables-per-node"),scheduling.getInt("max-connections-per-instance"),scheduling.getInt("max-concurrent-starts"),scheduling.getBytes("max-page-reservation"),scheduling.getInt("candidate-page"))
  val tableStore=new TableSchedulingStore(dataSource,limits,ownershipEnabled=true)
  val metadata=new SnapshotSupervisorStore(tableStore,binding,maxWorkers)
  def lock(role:String):AutoCloseable={
    val channel=FileChannel.open(Path.of(root.getString(role+"-lock")),StandardOpenOption.CREATE,StandardOpenOption.WRITE)
    val lock=try channel.tryLock()catch{case e:Exception=>channel.close();throw e}
    if(lock==null){channel.close();throw new IllegalStateException(role+" process lock held")}
    ()=>{lock.release();channel.close()}
  }
  def signingKey=KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder.decode(Files.readString(Path.of(root.getString("private-key-file"))).trim)))
  val workerConfig=Path.of(root.getString("worker-config-file"))
  private val heap=root.getInt("worker-xmx-mib");require(heap>=128 && heap<=65536)
  def profileHash:String=cn.xuyinyin.magic.workflow.snapshot.SnapshotSplitState.sha256(Files.readString(workerConfig)+"|"+heap+"|"+System.getProperty("java.class.path"))
  def validateWorkerProfile():Unit=SnapshotSupervisorSettings.validateWorker(ConfigFactory.parseFile(workerConfig.toFile).withFallback(ConfigFactory.load()).resolve(),config)
  def builder(expected:String)(ticket:SnapshotWorkerLaunch):ProcessBuilder={
    validateWorkerProfile()
    require(profileHash==expected && ticket.profileHash==expected,"worker configuration changed")
    val builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx"+heap+"m",
      "-Dconfig.file="+workerConfig.toAbsolutePath,"-Dpekko.workflow.snapshot-ha.owner-id="+ticket.bootId,
      "-Dpekko.workflow.snapshot-ha.supervisor-config="+path.toAbsolutePath,"-Dpekko.workflow.snapshot-ha.launch-profile="+expected,
      "-cp",System.getProperty("java.class.path"),"cn.xuyinyin.magic.PekkoServer")
    builder.inheritIO();builder
  }
  def observer:MySqlOwnerDrainObserver={
    val journal=config.getConfig("pekko-persistence-jdbc.shared-databases.slick.db")
    val routes=(Vector((jdbc.getString("url"),jdbc.getString("username"),jdbc.getString("password")),(journal.getString("url"),journal.getString("user"),journal.getString("password")))++
      config.getConfigList("pekko.workflow.snapshot-ha.instances").asScala.map(c=>(c.getString("url"),c.getString("username"),c.getString("password")))).map { case(url,user,password)=>SnapshotOwnerJdbc.endpoint(url)->(url,user,password)}.toMap
    val worker=ConfigFactory.parseFile(workerConfig.toFile).withFallback(ConfigFactory.load()).resolve()
    val exclusive=cn.xuyinyin.magic.workflow.cdc.CdcPlatformSettings.load(worker).toVector.flatMap(_.captures).flatMap {c=>
      Vector(SnapshotOwnerJdbc.endpoint(s"jdbc:mysql://${c.source.host}:${c.source.port}/${c.source.database}")->c.source.username,
        SnapshotOwnerJdbc.endpoint(c.sdkState.jdbcUrl)->c.sdkState.username)
    }.groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap
    new MySqlOwnerDrainObserver(tableStore.ownership.get,key=>{
      val (url,user,password)=routes.getOrElse(key,throw new IllegalStateException("unregistered observation endpoint"))
      java.sql.DriverManager.getConnection(SnapshotSupervisorSettings.appendParameters(url,"connectTimeout=5000&socketTimeout=10000&connectionAttributes=pekko_owner:observer,pekko_role:observer"),user,password)
    },exclusiveAccounts=exclusive)
  }
  /** One bounded, independently observable control-table insert per recovery tick. */
  def wakeCdc(boot:String,instance:(String,String,Long)):Unit={
    val worker=ConfigFactory.parseFile(workerConfig.toFile).withFallback(ConfigFactory.load()).resolve()
    cn.xuyinyin.magic.workflow.cdc.CdcPlatformSettings.load(worker).foreach {cdc=>
      val state=cn.xuyinyin.magic.connectors.mysql.source.MySQLCdcStateConfig.load(worker)
      val control=new cn.xuyinyin.magic.workflow.cdc.CdcTaskStore(()=>java.sql.DriverManager.getConnection(
        SnapshotSupervisorSettings.appendParameters(state.jdbcUrl,"connectTimeout=5000&socketTimeout=5000"),state.username,state.password),cdc.limits)
      if(control.managedOwnerIs(boot))cdc.captures.foreach {capture=>
        val url=s"jdbc:mysql://${capture.source.host}:${capture.source.port}/${capture.source.database}?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=5000"
        val endpoint=SnapshotOwnerJdbc.endpoint(url)
        if(endpoint==instance._1){
          val ownership=tableStore.ownership.get
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
            require(MySqlOwnerDrainObserver.hasProcessPrivilege(visible),"CDC wake observer lacks PROCESS visibility")
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
              val insert=c.prepareStatement("INSERT IGNORE INTO pekko_capture_control VALUES(?,'WAKE',?,?)");insert.setQueryTimeout(5)
              try {insert.setString(1,operation);insert.setString(2,wake.table(capture.id));insert.setString(3,generation);insert.executeUpdate()}finally insert.close()
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
    cn.xuyinyin.magic.workflow.cdc.CdcPlatformSettings.load(worker).exists {cdc=>
      require(profileHash==expectedProfile,"worker configuration changed")
      val launch=metadata.launch(boot).getOrElse(throw new IllegalStateException("worker launch missing"))
      require(launch.profileHash==expectedProfile,"worker launch profile changed")
      val identity=launch.identity.getOrElse(throw new IllegalStateException("worker identity missing"))
      require(identity.bootId==boot && identity.deploymentId==binding.deploymentId,"guardian launch identity changed")
      val expected=cn.xuyinyin.magic.workflow.cdc.CdcManagedOwner(boot,binding.deploymentId,binding.hostId,binding.bootId,
        binding.publicKey,identity.pid,identity.startIdentity,expectedProfile)
      val state=cn.xuyinyin.magic.connectors.mysql.source.MySQLCdcStateConfig.load(worker)
      val store=new cn.xuyinyin.magic.workflow.cdc.CdcTaskStore(()=>java.sql.DriverManager.getConnection(
        SnapshotSupervisorSettings.appendParameters(state.jdbcUrl,"connectTimeout=5000&socketTimeout=5000"),state.username,state.password),cdc.limits)
      store.managedOwnerNeedsIsolation(expected)
    }
  }
  def close():Unit=dataSource.close()
}

object SnapshotGuardianMain {
  def main(args:Array[String]):Unit={
    require(args.length==1,"operator-owned supervisor config required")
    val settings=new SnapshotSupervisorSettings(Path.of(args(0)))
    var lock:AutoCloseable=null
    try {
      lock=settings.lock("guardian");settings.validateWorkerProfile();settings.metadata.enroll()
      val self=ProcessHandle.current();settings.metadata.bindGuardianProcess(self.pid(),self.info().startInstant().orElseThrow().toString)
      val profile=settings.profileHash;val key=settings.signingKey
      // Key match is verified before accepting commands, not on first successful response.
      val probe=SnapshotGuardianWire.sign(spray.json.JsObject.empty,key)
      SnapshotGuardianWire.verified(probe,settings.binding.publicKey)
      val service=new SnapshotGuardianService(settings.metadata,new ManagedJvmIsolation(settings.binding.deploymentId,settings.maxWorkers),settings.builder(profile),key)
      val server=new SnapshotGuardianWire.Server(settings.socket,settings.secret,settings.binding,key,service.handle,attest=service.attest)
      try server.run()finally server.close()
    }finally {try {if(lock!=null)lock.close()}finally settings.close()}
  }
}

object SnapshotSupervisorMain {
  def main(args:Array[String]):Unit={
    require(args.length==1,"operator-owned supervisor config required")
    val settings=new SnapshotSupervisorSettings(Path.of(args(0)))
    var lock:AutoCloseable=null
    try {
      lock=settings.lock("supervisor");settings.validateWorkerProfile();val boot=UUID.randomUUID().toString
      val control=try settings.metadata.claimController(boot)catch{case e:Exception=>settings.metadata.current.filter(_.bootId==boot).getOrElse(throw e)}
      val client=new SnapshotGuardianWire.Client(settings.socket,settings.secret,control)
      val profile=settings.profileHash
      val runtime=new SnapshotSupervisorRuntime(settings.metadata,control,client,settings.observer.observe,profile,event=(kind,value)=>
        org.slf4j.LoggerFactory.getLogger(getClass).info("Snapshot supervisor {} ({})",kind,value),
        needsIsolation=boot=>settings.cdcNeedsIsolation(boot,profile),wake=(boot,instance)=>{
          require(settings.profileHash==profile,"worker configuration changed")
          settings.wakeCdc(boot,instance)
        })
      while(!Thread.currentThread().isInterrupted){runtime.tick();Thread.sleep(250)}
    }finally {try {if(lock!=null)lock.close()}finally settings.close()}
  }
}
