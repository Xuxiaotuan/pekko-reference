package cn.xuyinyin.magic.cdc.control


import cn.xuyinyin.magic.cdc.control.SnapshotRecoveryPermit



import cn.xuyinyin.magic.cdc.control.{CredentialBinding, CredentialBindingPlan}
import cn.xuyinyin.magic.cdc.guardian.{SnapshotOwnerIdentity}
import java.sql.{Connection,Driver,DriverPropertyInfo}
import java.util.Properties
import com.zaxxer.hikari.HikariConfig

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
    cn.xuyinyin.magic.cdc.guardian.ManagedCdcJdbc.install(cn.xuyinyin.magic.cdc.guardian.ManagedCdcJdbc.Context(
      value.identity,value.routes,
      (endpoint,uuid,started,id,role,user)=>value.owner.registerSession(value.identity.bootId,endpoint,uuid,started,id,role,user),
      (id,endpoint)=>value.owner.databaseUsers(id,endpoint),id=>value.owner.instances(id),value.close))
    installed=Some(value)
  }
  /** Install once, then register each control borrow on that same physical connection. */
  private[magic] def installForBootstrap(value:Context):Unit=synchronized {
    install(value)
    value.control.onBorrow=c=>value.owner.registerControlSession(c,value.identity.bootId)
  }
  def endpoint(url:String):String=cn.xuyinyin.magic.cdc.guardian.ManagedCdcJdbc.endpoint(url)
  def taggedUrl(url:String,identity:SnapshotOwnerIdentity,role:String):String=
    cn.xuyinyin.magic.cdc.guardian.ManagedCdcJdbc.taggedUrl(url,identity,role)
  def configure(config:HikariConfig,role:String):Unit=cn.xuyinyin.magic.cdc.guardian.ManagedCdcJdbc.configure(config,role)
  def open(url:String,user:String,password:String,role:String,managed:Boolean=false):Connection=
    cn.xuyinyin.magic.cdc.guardian.ManagedCdcJdbc.open(url,user,password,role,managed)
  def databaseUser(c:Connection):String=cn.xuyinyin.magic.cdc.guardian.ManagedCdcJdbc.databaseUser(c)
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
  def databaseIdentity(c:Connection):(String,Long,Long)=
    cn.xuyinyin.magic.cdc.guardian.MySqlDatabaseIdentity.read(c)
}

/** Tracks physical connections before the pool can execute business SQL, including Journal/Snapshot SQL. */
final class SnapshotOwnerJdbcDriver extends Driver {
  private val delegate=new cn.xuyinyin.magic.cdc.guardian.ManagedCdcJdbcDriver()
  override def connect(url:String,properties:Properties):Connection=delegate.connect(url,properties)
  override def acceptsURL(url:String):Boolean=delegate.acceptsURL(url)
  override def getPropertyInfo(url:String,p:Properties):Array[DriverPropertyInfo]=delegate.getPropertyInfo(url,p)
  override def getMajorVersion:Int=delegate.getMajorVersion
  override def getMinorVersion:Int=delegate.getMinorVersion
  override def jdbcCompliant():Boolean=delegate.jdbcCompliant()
  override def getParentLogger:java.util.logging.Logger=delegate.getParentLogger
}
