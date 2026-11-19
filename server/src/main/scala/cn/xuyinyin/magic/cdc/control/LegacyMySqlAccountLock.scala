package cn.xuyinyin.magic.cdc.control


import java.sql.{Connection,DriverManager}
import java.util.Properties
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Locks one exact account. A successful result does not establish session or transaction drain. */
private[control] object LegacyMySqlAccountLock {
  private def scopeMayCoverMysql(scope:String):Boolean={
    val dot=scope.indexOf('.')
    if(dot<=0)true
    else {
      val schema=scope.substring(0,dot)
      if(schema.contains('\\'))true
      else {
        val pattern=schema.map {
          case '%'=>".*"
          case '_'=>"."
          case c=>java.util.regex.Pattern.quote(c.toString)
        }.mkString
        java.util.regex.Pattern.compile("(?i)^"+pattern+"$").matcher("mysql").matches()
      }
    }
  }
  private[control] def legacyGrantsAreRestricted(grants:Vector[String]):Boolean={
    val forbidden=Set("CREATE USER","SUPER","SYSTEM_VARIABLES_ADMIN","SESSION_VARIABLES_ADMIN","ROLE_ADMIN",
      "PERSIST_RO_VARIABLES_ADMIN","APPLICATION_PASSWORD_ADMIN","PASSWORDLESS_USER_ADMIN","SET_ANY_DEFINER",
      "SET_USER_ID","ALLOW_NONEXISTENT_DEFINER","CREATE ROLE","DROP ROLE","CONNECTION_ADMIN","EXECUTE")
    val allowedGlobal=Set("USAGE","REPLICATION CLIENT","REPLICATION SLAVE","REPLICATION REPLICA")
    grants.nonEmpty && grants.forall { raw=>
      val grant=raw.toUpperCase(java.util.Locale.ROOT)
      val on=grant.indexOf(" ON ");val to=grant.indexOf(" TO ",on+4)
      if(!grant.startsWith("GRANT ") || on<0 || to<0 || grant.contains("WITH GRANT OPTION") || grant.contains("WITH ADMIN OPTION"))false
      else {
        val privileges=grant.substring(6,on).split(",").map(_.trim).toSet
        val scope=grant.substring(on+4,to).replace("`","").replace(" ","")
        !privileges.exists(forbidden) && !privileges("PROXY") && (scope=="*.*" || !scopeMayCoverMysql(scope)) &&
          (scope!="*.*" || privileges.subsetOf(allowedGlobal))
      }
    }
  }
  final case class Account(user:String,host:String) {
    require(user.matches("[A-Za-z0-9_]{1,32}"),"unsupported legacy account name")
    require(host.matches("[A-Za-z0-9.:-]{1,255}"),"wildcard or unsupported legacy account host")
  }

  /** ALTER USER implicitly commits; open a fresh physical connection for this DDL only. */
  def lock(url:String,adminUser:String,adminPassword:String,expectedServerUuid:String,account:Account,protectedUsers:Set[String],
    trustedProxyPrincipals:Set[Account]=Set.empty):Unit={
    require(protectedUsers.nonEmpty && !protectedUsers(account.user),"legacy account overlaps a protected account")
    require(trustedProxyPrincipals.size<=64 && !trustedProxyPrincipals(account),"legacy account cannot be a trusted proxy")
    val admin=openAdmin(url,adminUser,adminPassword)
    try lockOnFreshConnection(admin,expectedServerUuid,account,trustedProxyPrincipals) finally admin.close()
  }

  private def openAdmin(url:String,adminUser:String,adminPassword:String):Connection={
    require(url.startsWith("jdbc:mysql://"),"a fixed MySQL endpoint is required")
    val parameters=url.split("\\?",2).drop(1).flatMap(_.split("&")).map { p=>
      URLDecoder.decode(p.takeWhile(_!='='),StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT)
    }.toSet
    require(parameters.intersect(Set("connecttimeout","sockettimeout","user","password")).isEmpty,
      "account lock connection parameters cannot override credentials or timeouts")
    val properties=new Properties();properties.setProperty("user",adminUser);properties.setProperty("password",adminPassword)
    properties.setProperty("connectTimeout","5000");properties.setProperty("socketTimeout","10000")
    DriverManager.getConnection(url,properties)
  }

  /** One database observation only; caller must retain account lock and prove guardian isolation. */
  def verifyDrain(url:String,adminUser:String,adminPassword:String,expectedServerUuid:String,account:Account,
    trustedProxyPrincipals:Set[Account]):Unit={
    val admin=openAdmin(url,adminUser,adminPassword)
    try {
      val identity=admin.createStatement();identity.setQueryTimeout(5)
      val (uuid,id)=try {val r=identity.executeQuery("SELECT @@server_uuid,CONNECTION_ID()");try {require(r.next());(r.getString(1),r.getLong(2))}finally r.close()}finally identity.close()
      require(uuid==expectedServerUuid,"database instance identity changed")
      val grants=admin.createStatement();grants.setQueryTimeout(5)
      val canObserve=try {val r=grants.executeQuery("SHOW GRANTS");try {
        var allowed=false
        while(r.next()) {
          val grant=r.getString(1).toUpperCase(java.util.Locale.ROOT);val end=grant.indexOf(" ON *.* ")
          if(grant.startsWith("GRANT ") && end>0) {
            val privileges=grant.substring(6,end).split(",").map(_.trim).toSet
            if(privileges("ALL PRIVILEGES") || privileges("PROCESS"))allowed=true
          }
        };allowed
      }finally r.close()}finally grants.close()
      require(canObserve,"global PROCESS privilege is required to observe all sessions and transactions")
      val state=admin.prepareStatement("SELECT account_locked FROM mysql.user WHERE User=? AND Host=?");state.setQueryTimeout(5)
      try {state.setString(1,account.user);state.setString(2,account.host);val r=state.executeQuery();try {
        if(!r.next() || r.getString(1)!="Y" || r.next())throw new IllegalStateException("legacy account lock is not confirmed")
      }finally r.close()}finally state.close()
      val users=(trustedProxyPrincipals.map(_.user)+account.user).toVector.sorted
      val placeholders=users.map(_=>"?").mkString(",")
      def count(sql:String):Long={
        val q=admin.prepareStatement(sql);q.setQueryTimeout(5)
        try {q.setLong(1,id);users.zipWithIndex.foreach{case(u,i)=>q.setString(i+2,u)}
          val r=q.executeQuery();try {require(r.next());r.getLong(1)}finally r.close()
        }finally q.close()
      }
      val sessions=count(s"SELECT COUNT(*) FROM information_schema.processlist WHERE ID<>? AND (USER IS NULL OR USER IN ($placeholders))")
      if(sessions!=0)throw new IllegalStateException("legacy or proxy sessions remain")
      val transactions=count(s"SELECT COUNT(*) FROM information_schema.innodb_trx t LEFT JOIN information_schema.processlist p ON p.ID=t.trx_mysql_thread_id WHERE (t.trx_mysql_thread_id IS NULL OR t.trx_mysql_thread_id<>?) AND (t.trx_mysql_thread_id IS NULL OR t.trx_mysql_thread_id=0 OR p.ID IS NULL OR p.USER IS NULL OR p.USER IN ($placeholders))")
      if(transactions!=0)throw new IllegalStateException("legacy or unattributed transactions remain")
    }finally admin.close()
  }
  private def lockOnFreshConnection(admin:Connection,expectedServerUuid:String,account:Account,trustedProxyPrincipals:Set[Account]):Unit={
    require(admin.getAutoCommit,"account DDL requires a dedicated autocommit connection")
    val identity=admin.createStatement()
    val (uuid,current)=try {
      identity.setQueryTimeout(5)
      val r=identity.executeQuery("SELECT @@server_uuid,CURRENT_USER()")
      try {require(r.next());(r.getString(1),r.getString(2))} finally r.close()
    } finally identity.close()
    require(uuid==expectedServerUuid,"database instance identity changed")
    require(current!=null && current.takeWhile(_!='@')!=account.user,"administrative account cannot lock itself")
    val show=admin.createStatement()
    val globalPrivileges=try {
      show.setQueryTimeout(5);val r=show.executeQuery("SHOW GRANTS");val out=Set.newBuilder[String]
      try while(r.next()) {
        val grant=r.getString(1).toUpperCase(java.util.Locale.ROOT)
        val separator=grant.indexOf(" ON *.* ")
        if(separator>0 && grant.startsWith("GRANT "))grant.substring(6,separator).split(",").map(_.trim).foreach(out+=_)
      } finally r.close()
      out.result()
    } finally show.close()
    require(globalPrivileges("ALL PRIVILEGES") || Set("SELECT","SHOW VIEW","TRIGGER","EVENT").subsetOf(globalPrivileges),
      "direct global privileges are required for complete DEFINER inventory")
    val oldGrants=admin.createStatement()
    val grants=try {
      oldGrants.setQueryTimeout(5);val r=oldGrants.executeQuery(s"SHOW GRANTS FOR '${account.user}'@'${account.host}'")
      val out=Vector.newBuilder[String];try while(r.next())out+=r.getString(1) finally r.close();out.result()
    } finally oldGrants.close()
    require(legacyGrantsAreRestricted(grants),"legacy account has role, delegation, system-table or account-management privileges")
    def checkAccountsAndProxies():Unit={
      val inventory=admin.createStatement()
      try {
        inventory.setQueryTimeout(5)
        val roles=inventory.executeQuery("SELECT @@GLOBAL.mandatory_roles")
        try {require(roles.next());require(Option(roles.getString(1)).exists(_.trim.isEmpty),"mandatory roles prevent a complete legacy privilege proof")} finally roles.close()
        val anonymous=inventory.executeQuery("SELECT COUNT(*) FROM mysql.user WHERE User=''")
        try {require(anonymous.next());require(anonymous.getLong(1)==0,"anonymous accounts prevent a complete legacy identity proof")} finally anonymous.close()
      } finally inventory.close()
      val aliases=admin.prepareStatement("SELECT Host FROM mysql.user WHERE User=?")
      val hosts=try {
        aliases.setQueryTimeout(5);aliases.setString(1,account.user)
        val r=aliases.executeQuery();val out=Vector.newBuilder[String]
        try while(r.next())out+=r.getString(1) finally r.close()
        out.result()
      } finally aliases.close()
      require(hosts==Vector(account.host),"legacy user has missing or alternate host accounts")
      val proxy=admin.prepareStatement("SELECT User,Host FROM mysql.proxies_priv WHERE Proxied_user=? OR Proxied_user=''")
      val principals=try {
        proxy.setQueryTimeout(5);proxy.setString(1,account.user)
        val r=proxy.executeQuery();val out=Vector.newBuilder[(String,String)]
        try while(r.next())out+=((r.getString(1),r.getString(2))) finally r.close()
        out.result()
      } finally proxy.close()
      require(principals.forall{case(u,h)=>trustedProxyPrincipals.exists(p=>p.user==u && p.host==h)},"untrusted proxy privilege may impersonate the legacy account")
      Vector("ROUTINES","VIEWS","TRIGGERS","EVENTS").foreach { table=>
        val q=admin.prepareStatement(s"SELECT COUNT(*) FROM information_schema.$table WHERE SUBSTRING_INDEX(DEFINER,'@',1)=?")
        try {
          q.setQueryTimeout(5);q.setString(1,account.user)
          val r=q.executeQuery();try {require(r.next());require(r.getLong(1)==0,"legacy account still owns DEFINER objects")} finally r.close()
        } finally q.close()
      }
    }
    checkAccountsAndProxies()
    def state():String={
      val q=admin.prepareStatement("SELECT account_locked FROM mysql.user WHERE User=? AND Host=?")
      try {
        q.setQueryTimeout(5);q.setString(1,account.user);q.setString(2,account.host)
        val r=q.executeQuery()
        try {require(r.next(),"exact legacy account is missing");val result=r.getString(1);require(!r.next());result} finally r.close()
      } finally q.close()
    }
    state() match {
      case "Y"=>()
      case "N"=>
        val q=admin.createStatement()
        try {q.setQueryTimeout(5);q.execute(s"ALTER USER '${account.user}'@'${account.host}' ACCOUNT LOCK")} finally q.close()
        require(state()=="Y","account lock was not confirmed")
      case _=>throw new IllegalStateException("account lock state is unknown")
    }
    checkAccountsAndProxies()
  }
}
