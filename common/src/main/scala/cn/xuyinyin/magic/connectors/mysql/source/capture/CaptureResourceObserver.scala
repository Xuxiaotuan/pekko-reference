package cn.xuyinyin.magic.connectors.mysql.source.capture

import java.sql.Connection
import java.util.UUID

/** Accounts must be exclusive to this capture, not a shared application/Journal account.
  * stateUser must cover SDK offset/history, the Reader owner and every connection used
  * by this capture's L Store. Keeping L in another schema does not change this requirement.
  * A caller-supplied Store using an account outside that domain is not covered by the
  * observer's full-capture release proof, even when that account is visible in PROCESSLIST.
  * Original anchor connections, without reconnect, bind observations to the same server lifetime.
  */
trait CaptureResourceObserver extends AutoCloseable {
  def beforeStart(attempt:String,ownerId:Long):Unit
  def sample(attempt:String,ownerId:Long,includeOwner:Boolean):CaptureServerRelease
}
object CaptureResourceObserver {
  def exclusiveAccounts(sourceOpen:()=>Connection,stateOpen:()=>Connection,sourceUser:String,stateUser:String,
    sourceUuid:String,stateUuid:String):CaptureResourceObserver=new CaptureResourceObserver {
    require(sourceUser.nonEmpty && stateUser.nonEmpty && sourceUuid!=stateUuid)
    private case class Anchor(c:Connection,id:Long,uuid:String,canary:String)
    private var source:Anchor=null
    private var state:Anchor=null
    private var runtime:String=null
    private var closed=false
    private def rows(c:Connection,sql:String,args:Seq[Any]=Vector.empty):Vector[Vector[String]]={
      val q=c.prepareStatement(sql);q.setQueryTimeout(2)
      try {
        args.zipWithIndex.foreach {case(v,i)=>q.setObject(i+1,v)}
        val r=q.executeQuery();try {
          val out=Vector.newBuilder[Vector[String]];val columns=r.getMetaData.getColumnCount
          while(r.next())out+=(1 to columns).map(r.getString).toVector
          out.result()
        }finally r.close()
      }finally q.close()
    }
    private def open(factory:()=>Connection,expected:String):Anchor={
      val c=factory()
      try {
        c.setReadOnly(true)
        val identity=rows(c,"SELECT CONNECTION_ID(),@@server_uuid").head
        require(identity(1)==expected,"observer server identity mismatch")
        val grants=rows(c,"SHOW GRANTS").flatten
        require(grants.exists {g=>
          val global=g.takeWhile(_!='\n').toUpperCase(java.util.Locale.ROOT).split(" ON \\*\\.\\* ").headOption.getOrElse("")
          g.toUpperCase(java.util.Locale.ROOT).contains(" ON *.* ") &&
            (global.contains("ALL PRIVILEGES") || global.stripPrefix("GRANT ").split(",").exists(_.trim=="PROCESS"))
        },"observer requires directly granted global PROCESS visibility")
        val canary="capture-observe-"+UUID.randomUUID().toString
        require(rows(c,"SELECT GET_LOCK(?,0)",Vector(canary)).head.head=="1","observer anchor unavailable")
        Anchor(c,identity.head.toLong,expected,canary)
      }catch {case e:Throwable=>try c.close()catch{case close:Throwable=>e.addSuppressed(close)};throw e}
    }
    private def check(a:Anchor):Unit={
      val identity=rows(a.c,"SELECT CONNECTION_ID(),@@server_uuid,IS_USED_LOCK(?)",Vector(a.canary)).head
      require(identity==Vector(a.id.toString,a.uuid,a.id.toString),"original observer anchor lost")
    }
    private def sessions(a:Anchor,user:String,ignored:Set[Long]):Vector[Long]=
      rows(a.c,"SELECT ID FROM information_schema.PROCESSLIST WHERE USER=?",Vector(user)).map(_.head.toLong).filterNot(ignored)
    private def transactions(a:Anchor,user:String):Vector[Long]=
      // An orphan with no visible account cannot safely be attributed to another service.
      rows(a.c,"SELECT t.trx_mysql_thread_id,p.USER FROM information_schema.INNODB_TRX t LEFT JOIN information_schema.PROCESSLIST p ON p.ID=t.trx_mysql_thread_id")
        .filter(r=>r(1)==null || r(1)==user).map(_.head.toLong)
    def beforeStart(attempt:String,ownerId:Long):Unit=synchronized {
      require(runtime==null && !closed,"observer already bound")
      runtime=attempt
      source=open(sourceOpen,sourceUuid)
      try {
        state=open(stateOpen,stateUuid)
        val observed=sample(attempt,ownerId,includeOwner=false)
        require(observed.status=="CONFIRMED","capture account has unresolved previous sessions or transactions")
      }catch {case e:Throwable=>try close()catch{case close:Throwable=>e.addSuppressed(close)};throw e}
    }
    def sample(attempt:String,ownerId:Long,includeOwner:Boolean):CaptureServerRelease=synchronized {
      require(!closed && runtime==attempt && source!=null && state!=null,"observer attempt unavailable")
      check(source);check(state)
      val left=sessions(source,sourceUser,Set(source.id))
      val right=sessions(state,stateUser,if(includeOwner)Set(state.id)else Set(state.id,ownerId))
      val pending=transactions(source,sourceUser)++transactions(state,stateUser)
      CaptureServerRelease(attempt,if(left.isEmpty && right.isEmpty && pending.isEmpty)"CONFIRMED"else"UNKNOWN",
        if(left.isEmpty && right.isEmpty && pending.isEmpty)None else Some("SERVER_SESSIONS_OR_TRANSACTIONS_REMAIN"),left,right,pending)
    }
    override def close():Unit=synchronized {
      var failure:Throwable=null
      Vector(source,state).filter(_!=null).foreach {a=>
        try a.c.close() catch {case e:Throwable=>if(failure==null)failure=e else failure.addSuppressed(e)}
      }
      if(failure!=null)throw failure
      closed=true
    }
  }
}
