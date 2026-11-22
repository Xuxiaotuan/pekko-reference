package cn.xuyinyin.magic.cdc.guardian

/** Conservative admission checks for MySQL session and transaction observation. */
private[magic] object DrainObservationPolicy {
  def hasProcessPrivilege(grants:Vector[String]):Boolean=grants.exists {g=>
    val text=g.toUpperCase(java.util.Locale.ROOT)
    val global=text.takeWhile(_!='\n').split(" ON \\*\\.\\* ").headOption.getOrElse("")
    text.contains(" ON *.* ") && (global.contains("ALL PRIVILEGES") || global.stripPrefix("GRANT ").split(",").exists(_.trim=="PROCESS"))
  }
  def unattributed(row:Map[String,String],owner:String,users:Set[String],exclusive:Set[String]):Boolean=
    (users(row("user")) && row("owner")==null) || (exclusive(row("user")) && row("owner")!=owner)
  def hasObserverIdentity(size:String,attrs:Map[String,String]):Boolean=
    scala.util.Try(size.toLong).toOption.exists(_>0) && attrs.get("pekko_owner").contains("observer")
}
