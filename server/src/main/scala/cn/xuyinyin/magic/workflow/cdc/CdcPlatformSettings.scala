package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.connectors.mysql.source.{MySQLCdcSourceConfig,MySQLCdcStateConfig}
import cn.xuyinyin.magic.workflow.bootstrap.BootstrapLimits
import com.typesafe.config.Config
import java.net.URI
import scala.jdk.CollectionConverters._

final case class CdcCaptureSettings(id:String,source:MySQLCdcSourceConfig,sdkState:MySQLCdcStateConfig,
  controlState:MySQLCdcStateConfig,sourceLineage:String,stateLineage:String,maxLogBytes:Long=128L*1024*1024,
  maxSpoolBytes:Long=64L*1024*1024,bootstrapLimits:BootstrapLimits=BootstrapLimits(),
  sourceObserverUsername:Option[String]=None,sourceObserverPassword:Option[String]=None) {
  override def toString:String=s"CdcCaptureSettings($id,${source.database},<credentials-redacted>)"
  if(sourceObserverUsername.isDefined!=sourceObserverPassword.isDefined || sourceObserverUsername.exists(_.isEmpty) || sourceObserverPassword.exists(_.isEmpty))
    throw new CdcPlatformException("INVALID_OBSERVER_ACCOUNT")
  if(sdkState.password.isEmpty || sdkState.username==controlState.username)throw new CdcPlatformException("INVALID_EXCLUSIVE_SDK_ACCOUNT")
  private val stateUri=URI.create(controlState.jdbcUrl.stripPrefix("jdbc:"))
  val targetHost:String=Option(stateUri.getHost).getOrElse(throw new CdcPlatformException("INVALID_STATE_JDBC"))
  val targetPort:Int=if(stateUri.getPort>0)stateUri.getPort else 3306
  val targetDatabase:String=Option(stateUri.getPath).getOrElse("").stripPrefix("/")
  if(!targetDatabase.matches("[A-Za-z_][A-Za-z0-9_]*"))throw new CdcPlatformException("INVALID_STATE_JDBC")
}
final case class CdcPlatformSettings(captures:Vector[CdcCaptureSettings],limits:CdcPlatformLimits,pollIntervalMillis:Long=100L) {
  require(captures.nonEmpty && captures.size<=32 && captures.map(_.id).distinct.size==captures.size &&
    captures.map(_.controlState.jdbcUrl).distinct.size==1 && pollIntervalMillis>0)
  private def duplicate[A](values:Vector[A])=values.distinct.size!=values.size
  if(duplicate(captures.map(c=>(c.source.host.toLowerCase(java.util.Locale.ROOT),c.source.port,c.source.username))) ||
    duplicate(captures.map(c=>(c.sourceLineage.toLowerCase(java.util.Locale.ROOT),c.source.username))) ||
    duplicate(captures.map(c=>(c.targetHost.toLowerCase(java.util.Locale.ROOT),c.targetPort,c.sdkState.username))) ||
    duplicate(captures.map(c=>(c.stateLineage.toLowerCase(java.util.Locale.ROOT),c.sdkState.username))))
    throw new CdcPlatformException("INVALID_EXCLUSIVE_SDK_ACCOUNT")
}
object CdcPlatformSettings {
  def load(root:Config):Option[CdcPlatformSettings]={
    val path="pekko.workflow.cdc-platform"
    if(!root.hasPath(path+".enabled") || !root.getBoolean(path+".enabled"))None
    else {
      val cfg=root.getConfig(path);val control=MySQLCdcStateConfig.load(root)
      def number(c:Config,k:String,d:Int)=if(c.hasPath(k))c.getInt(k)else d
      def long(c:Config,k:String,d:Long)=if(c.hasPath(k))c.getLong(k)else d
      val captures=cfg.getConfigList("captures").asScala.toVector.map {c=>
        val id=c.getString("id")
        def uuid(k:String):String={val v=c.getString(k);if(!v.matches("[a-fA-F0-9-]{36}"))throw new CdcPlatformException("INVALID_SOURCE_LINEAGE");v}
        val sdkUser=c.getString("sdk-state-username")
        if(!c.hasPath("exclusive-sdk-accounts") || !c.getBoolean("exclusive-sdk-accounts") || sdkUser==control.username)
          throw new CdcPlatformException("INVALID_EXCLUSIVE_SDK_ACCOUNT")
        val source=MySQLCdcSourceConfig(id,c.getString("host"),number(c,"port",3306),c.getString("database"),"pekko_capture_control",
          c.getString("username"),c.getString("password"),c.getLong("server-id"),number(c,"batch-size",128),100)
        if(!id.matches("[A-Za-z0-9_-]{1,64}") || !source.database.matches("[A-Za-z_][A-Za-z0-9_]*") || source.host.isEmpty ||
          source.port<1 || source.port>65535 || source.serverId<=0 || source.serverId>4294967295L || source.username.isEmpty || source.password.isEmpty)
          throw new CdcPlatformException("INVALID_CAPTURE_CONFIGURATION")
        CdcCaptureSettings(id,source,control.copy(username=sdkUser,password=c.getString("sdk-state-password")),control,
          uuid("source-lineage"),uuid("state-lineage"),long(c,"max-log-bytes",128L*1024*1024),long(c,"max-spool-bytes",64L*1024*1024),
          BootstrapLimits(chunkRows=number(c,"chunk-rows",32),pageRows=number(c,"page-rows",8),groupBatch=number(c,"batch-size",128)),
          if(c.hasPath("source-observer-username"))Some(c.getString("source-observer-username"))else None,
          if(c.hasPath("source-observer-password"))Some(c.getString("source-observer-password"))else None)
      }
      Some(CdcPlatformSettings(captures,CdcPlatformLimits(snapshotSteps=number(cfg,"snapshot-steps",2),tailSteps=number(cfg,"tail-steps",2),
        candidatePage=number(cfg,"candidate-page",32),maxPendingOperations=number(cfg,"max-pending-operations",64),
        tailDelayMillis=long(cfg,"tail-delay-ms",500L)),long(cfg,"poll-interval-ms",100L)))
    }
  }
}
