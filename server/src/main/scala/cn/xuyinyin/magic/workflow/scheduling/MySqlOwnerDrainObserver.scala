package cn.xuyinyin.magic.workflow.scheduling

import java.sql.Connection
import spray.json._
import scala.util.control.NonFatal

/** Authoritative fixed-instance observations. Errors and truncated inventory are never represented as an empty result. */
final class MySqlOwnerDrainObserver(ownership:SnapshotOwnershipStore,open:String=>Connection,maxRows:Int=10000) {
  require(maxRows>0 && maxRows<=10000)
  def observe(owner:String,instance:(String,String,Long)):SnapshotDatabaseObservation={
    val at=System.currentTimeMillis()
    var c:Connection=null
    try {
      c=open(instance._1)
      val (uuid,started,selfId)=SnapshotOwnerJdbc.databaseIdentity(c)
      require(uuid==instance._2 && math.abs(started-instance._3)<=3000,"database instance changed or restarted")
      def rows(sql:String):Vector[Map[String,String]]={
        val q=c.createStatement();q.setQueryTimeout(5)
        try {
          val r=q.executeQuery(sql);val out=Vector.newBuilder[Map[String,String]]
          try {var n=0;val md=r.getMetaData;while(r.next() && n<=maxRows){out+=(1 to md.getColumnCount).map(i=>md.getColumnLabel(i).toLowerCase(java.util.Locale.ROOT)->r.getString(i)).toMap;n+=1}
            require(n<=maxRows,"database observation exceeds bounded inventory");out.result()}finally r.close()
        }finally q.close()
      }
      val lost=rows("SHOW GLOBAL STATUS WHERE Variable_name IN ('Performance_schema_session_connect_attrs_lost','Performance_schema_thread_instances_lost')")
      require(lost.size==2 && lost.forall(_("value")=="0"),"database observation lost connection identity information")
      val sessions=rows(s"""SELECT p.ID AS id,p.USER AS user,p.COMMAND AS command,p.STATE AS state,p.INFO AS info,
        a.ATTR_VALUE AS owner,r.ATTR_VALUE AS role
        FROM information_schema.processlist p
        LEFT JOIN performance_schema.session_connect_attrs a ON p.ID=a.PROCESSLIST_ID AND a.ATTR_NAME='pekko_owner'
        LEFT JOIN performance_schema.session_connect_attrs r ON p.ID=r.PROCESSLIST_ID AND r.ATTR_NAME='pekko_role'
        WHERE p.ID<>$selfId ORDER BY p.ID LIMIT ${maxRows+1}""")
      val transactions=rows(s"SELECT trx_id,trx_state,trx_mysql_thread_id,trx_query FROM information_schema.innodb_trx ORDER BY trx_mysql_thread_id LIMIT ${maxRows+1}")
      val known=ownership.knownSessions(owner,instance._1).toSet
      val attributed=sessions.filter(_("owner")==owner)
      val ids=known++attributed.map(_("id").toLong)
      val oldSessions=sessions.filter(s=>ids(s("id").toLong))
      val oldTransactions=transactions.filter(t=>ids(t("trx_mysql_thread_id").toLong))
      val workerUsers=ownership.databaseUsers(owner,instance._1)
      require(workerUsers.nonEmpty,"worker database account inventory is incomplete")
      val unassigned=sessions.filter(s=>s("owner")==null && workerUsers(s("user")))
      val visible=sessions.map(_("id").toLong).toSet+selfId
      val orphan=transactions.filter(t=> !visible(t("trx_mysql_thread_id").toLong) && !ids(t("trx_mysql_thread_id").toLong))
      def json(v:Vector[Map[String,String]])=JsArray(v.map(m=>JsObject(m.map{case(k,value)=>k->Option(value).map(JsString(_)).getOrElse(JsNull)})))
      val evidence=JsObject("owner"->JsString(owner),"instance"->JsString(instance._1),"observedAt"->JsNumber(at),
        "serverStarted"->JsNumber(started),"knownIds"->JsArray(ids.toVector.sorted.map(JsNumber(_))),
        "sessions"->json(oldSessions),"transactions"->json(oldTransactions),"unattributed"->json(unassigned),"unassignedTransactions"->json(orphan))
      val used=sessions.map(_("id").toLong).toSet++transactions.map(_("trx_mysql_thread_id").toLong)
      SnapshotDatabaseObservation(instance._1,true,oldSessions.size,oldTransactions.size,unassigned.size+orphan.size,evidence.compactPrint,at,(known--used).toVector)
    } catch {case NonFatal(e)=>SnapshotDatabaseObservation(instance._1,false,-1,-1,-1,
      JsObject("owner"->JsString(owner),"instance"->JsString(instance._1),"errorClass"->JsString(e.getClass.getName)).compactPrint,at)
    } finally if(c!=null)try c.close() catch {case NonFatal(_)=>()}
  }
}
