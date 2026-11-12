package cn.xuyinyin.magic.cdc.platform

import com.typesafe.config.Config
import scala.jdk.CollectionConverters._

/** Opt-in maintenance only; no credentials are persisted in GC state. */
final case class CdcGcSettings(username:String,password:String,accountHost:String,ledgerDefiner:String,
  trustedAdmins:Set[String],producerAccounts:Set[String],enableLegacy:Boolean=false,
  intervalMillis:Long=60000L,batchFrames:Int=32,pageSize:Int=16,inventoryLimit:Int=10000) {
  require(username.matches("[A-Za-z0-9_]+") && password.nonEmpty && accountHost.matches("[A-Za-z0-9.:-]+") &&
    ledgerDefiner.matches("[A-Za-z0-9_]+@[A-Za-z0-9_.%:-]+") && trustedAdmins.nonEmpty &&
    (trustedAdmins++producerAccounts).forall(_.matches("[A-Za-z0-9_]+@[A-Za-z0-9_.%:-]+")) &&
    !trustedAdmins(account) && !producerAccounts(account) && (trustedAdmins intersect producerAccounts).isEmpty &&
    intervalMillis>=100 && batchFrames>=1 && batchFrames<=128 && pageSize>=1 && pageSize<=32 && inventoryLimit>=1)
  def account:String=username+"@"+accountHost
  override def toString:String=s"CdcGcSettings($username,<credentials-redacted>)"
}
object CdcGcSettings {
  def load(c:Config):Option[CdcGcSettings]=if(!c.hasPath("gc.enabled") || !c.getBoolean("gc.enabled"))None else {
    val g=c.getConfig("gc")
    def n(k:String,d:Int)=if(g.hasPath(k))g.getInt(k)else d
    Some(CdcGcSettings(g.getString("username"),g.getString("password"),g.getString("account-host"),g.getString("ledger-definer"),
      g.getStringList("trusted-admin-accounts").asScala.toSet,g.getStringList("producer-accounts").asScala.toSet,
      g.hasPath("enable-legacy") && g.getBoolean("enable-legacy"),
      if(g.hasPath("interval-ms"))g.getLong("interval-ms")else 60000L,n("batch-frames",32),n("page-size",16),n("inventory-limit",10000)))
  }
}
