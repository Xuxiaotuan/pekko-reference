package cn.xuyinyin.magic.workflow.scheduling

import java.sql.{Connection,Driver,DriverPropertyInfo}
import java.util.Properties
import com.zaxxer.hikari.HikariConfig
import scala.util.control.NonFatal

/** A single managed worker per JVM. Installed before ActorSystem (including the shared Journal pool). */
object SnapshotOwnerJdbc {
  final case class Context(identity:SnapshotOwnerIdentity,control:ManagedControlTransactions,owner:ManagedOwnerStore,routes:Map[String,String],close:()=>Unit,
    bindingPlans:Vector[CredentialBindingPlan]=Vector.empty)
  object Context {
    def apply(identity:SnapshotOwnerIdentity,store:TableSchedulingStore,routes:Map[String,String],close:()=>Unit):Context=
      new Context(identity,store,store.ownership.getOrElse(throw new IllegalArgumentException("owner registry missing")),routes,close)
  }
  @volatile private var installed:Option[Context]=None
  def context:Option[Context]=installed
  def credentialBinding(workflowId:String,executionId:String,nodeId:String,role:String):Option[CredentialBinding]=
    context.flatMap(ctx=>if(ctx.bindingPlans.isEmpty)None else ctx.control.cutoverStore.binding(workflowId,executionId,nodeId,role).filter(_.ownerId==ctx.identity.bootId))
  def install(value:Context):Unit=synchronized {
    require(installed.isEmpty,"one managed owner per JVM; boot identities may not be replaced")
    installed=Some(value)
  }
  def endpoint(url:String):String={
    val uri=new java.net.URI(url.stripPrefix("jdbc:"));require(uri.getScheme=="mysql" && uri.getHost!=null,"managed snapshots require a fixed MySQL endpoint")
    uri.getHost.toLowerCase(java.util.Locale.ROOT)+":"+(if(uri.getPort<0)3306 else uri.getPort)
  }
  def taggedUrl(url:String,identity:SnapshotOwnerIdentity,role:String):String={
    require(Set("journal","source","target","control").contains(role))
    val parts=url.split("\\?",2);val parameters=if(parts.length==1)Vector.empty else parts(1).split("&").toVector
    require(!parameters.exists(_.startsWith("connectionAttributes=")),"managed connection attributes cannot be overridden")
    parts(0)+"?"+(parameters:+s"connectionAttributes=pekko_owner:${identity.bootId},pekko_role:$role":+s"pekkoOwnerRole=$role").mkString("&")
  }
  def configure(config:HikariConfig,role:String):Unit=context.foreach { ctx=>
    require(ctx.routes.contains(endpoint(config.getJdbcUrl)),"database endpoint is not in the fixed managed manifest")
    config.setJdbcUrl(taggedUrl(config.getJdbcUrl,ctx.identity,role))
    config.setDriverClassName(classOf[SnapshotOwnerJdbcDriver].getName)
  }
  /** Register a direct JDBC connection before it can issue application SQL. */
  def open(url:String,user:String,password:String,role:String,managed:Boolean=false):Connection={
    if(!managed)java.sql.DriverManager.getConnection(url,user,password)
    else {
      val ctx=context.getOrElse(throw new IllegalStateException("managed owner bootstrap is missing"))
      require(ctx.routes.contains(endpoint(url)),"database endpoint is not in the fixed managed manifest")
      val props=new Properties();props.setProperty("user",user);props.setProperty("password",password)
      new SnapshotOwnerJdbcDriver().connect(taggedUrl(url,ctx.identity,role),props)
    }
  }
  def databaseUser(c:Connection):String={
    val q=c.createStatement();q.setQueryTimeout(5)
    try {val r=q.executeQuery("SELECT USER()");try {require(r.next());val name=r.getString(1);val split=name.lastIndexOf('@');if(split<0)name else name.substring(0,split)}finally r.close()}finally q.close()
  }
  def activatePlannedBinding(workflowId:String,executionId:String,permit:SnapshotRecoveryPermit):Unit=context.foreach { ctx=>
    ctx.bindingPlans.filter(p=>p.workflowId==workflowId && p.nodeId.nonEmpty).foreach { plan=>
      require(plan.operationId.nonEmpty && plan.hostId.nonEmpty && plan.workflowId==workflowId,"binding plan workflow mismatch")
      require(permit.workflowId==workflowId && permit.executionId==executionId && permit.owner==ctx.identity.bootId,
        "binding permit does not belong to the managed worker")
      val auth=ctx.control.cutoverStore.authorizationByOperation(ctx.identity.deploymentId,plan.hostId,plan.operationId)
        .getOrElse(throw new IllegalStateException("cutover authorization is missing"))
      require(auth.policyHash.nonEmpty,"cutover authorization policy is missing")
      ctx.control.cutoverStore.recordBinding(CredentialBinding(workflowId,executionId,plan.nodeId,plan.nodeRole,permit.id,ctx.identity.bootId,
        plan.operationId,plan.endpoint,plan.databaseName,plan.legacyUser,plan.runtimeUser,plan.passwordEnv,plan.definitionHash,"ACTIVE"),auth)
    }
  }
  /** Apply only a durable, permit-bound runtime identity; persisted Workflow JSON is untouched. */
  def rebindRuntimeNode(node:cn.xuyinyin.magic.workflow.execution.ExecutionNode,workflowId:String,executionId:String,definitionHash:String):cn.xuyinyin.magic.workflow.execution.ExecutionNode={
    credentialBinding(workflowId,executionId,node.id,node.role) match {
      case None=>node
      case Some(binding) => applyCredentialBinding(node,binding,definitionHash)
    }
  }
  def rebindRuntimeNode(node:cn.xuyinyin.magic.workflow.execution.ExecutionNode,binding:CredentialBinding):cn.xuyinyin.magic.workflow.execution.ExecutionNode=
    applyCredentialBinding(node,binding,binding.definitionHash)
  private def applyCredentialBinding(node:cn.xuyinyin.magic.workflow.execution.ExecutionNode,binding:CredentialBinding,definitionHash:String):cn.xuyinyin.magic.workflow.execution.ExecutionNode={
    require(binding.definitionHash==definitionHash && binding.legacyUser==node.config.fields.get("username").collect{case spray.json.JsString(v)=>v}.getOrElse(""),"credential binding does not match the persisted node")
    val host=node.config.fields.get("host").collect{case spray.json.JsString(v)=>v}.getOrElse("localhost")
    val port=node.config.fields.get("port").collect{case spray.json.JsNumber(v)=>v.toInt}.getOrElse(3306)
    val database=node.config.fields.get("database").collect{case spray.json.JsString(v)=>v}.getOrElse("")
    require(endpoint(s"jdbc:mysql://$host:$port/$database")==binding.endpoint && database==binding.databaseName,"credential binding endpoint differs")
    node.copy(config=spray.json.JsObject((node.config.fields-"password")+ ("username"->spray.json.JsString(binding.runtimeUser))+ ("passwordEnv"->spray.json.JsString(binding.passwordEnv))))
  }
  def databaseIdentity(c:Connection):(String,Long,Long)={
    val q=c.createStatement();q.setQueryTimeout(5)
    try {
      val r=q.executeQuery("SELECT @@server_uuid,UNIX_TIMESTAMP(NOW(3))*1000,CONNECTION_ID()")
      val (uuid,clock,id)=try {require(r.next());(r.getString(1),r.getBigDecimal(2).longValue(),r.getLong(3))}finally r.close()
      val u=q.executeQuery("SHOW GLOBAL STATUS LIKE 'Uptime'")
      val uptime=try {require(u.next());u.getLong(2)}finally u.close()
      (uuid,clock-uptime*1000,id)
    } finally q.close()
  }
}

/** Tracks physical connections before the pool can execute business SQL, including Journal/Snapshot SQL. */
final class SnapshotOwnerJdbcDriver extends Driver {
  private val delegate=new com.mysql.cj.jdbc.Driver()
  override def connect(url:String,properties:Properties):Connection={
    if(!acceptsURL(url))return null
    val ctx=SnapshotOwnerJdbc.context.getOrElse(throw new IllegalStateException("managed owner bootstrap is missing"))
    val key=SnapshotOwnerJdbc.endpoint(url)
    val expected=ctx.routes.getOrElse(key,throw new IllegalStateException("unregistered MySQL endpoint"))
    val role=url.split("[?&]").find(_.startsWith("pekkoOwnerRole=")).map(_.stripPrefix("pekkoOwnerRole="))
      .filter(Set("journal","source","target","control")).getOrElse(throw new IllegalStateException("managed database role is missing"))
    val cleaned=url.replaceAll("[&]pekkoOwnerRole=[a-z]+","")
    val c=delegate.connect(cleaned,properties)
    try {
      val (uuid,started,id)=SnapshotOwnerJdbc.databaseIdentity(c)
      require(uuid==expected,"database identity changed")
      ctx.owner.registerSession(ctx.identity.bootId,key,uuid,started,id,role,SnapshotOwnerJdbc.databaseUser(c))
      c
    } catch {case NonFatal(e)=>try c.close() catch {case NonFatal(close)=>e.addSuppressed(close)};throw e}
  }
  override def acceptsURL(url:String):Boolean=url!=null && url.startsWith("jdbc:mysql:")
  override def getPropertyInfo(url:String,p:Properties):Array[DriverPropertyInfo]=delegate.getPropertyInfo(url,p)
  override def getMajorVersion:Int=delegate.getMajorVersion
  override def getMinorVersion:Int=delegate.getMinorVersion
  override def jdbcCompliant():Boolean=delegate.jdbcCompliant()
  override def getParentLogger:java.util.logging.Logger=delegate.getParentLogger
}
