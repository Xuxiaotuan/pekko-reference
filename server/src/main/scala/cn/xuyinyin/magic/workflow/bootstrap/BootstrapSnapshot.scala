package cn.xuyinyin.magic.workflow.bootstrap

import java.sql.Connection
import spray.json._

/** One read view per window. Page offsets are never resumed in a replacement transaction. */
final class BootstrapSnapshot(open:()=>Connection,val table:String,val codec:BootstrapRowCodec,
  maxRows:Int,maxBytes:Long,observe:(String,JsObject)=>Unit=(_,_)=>()) {
  require(table.matches("[A-Za-z_][A-Za-z0-9_]*") && maxRows>0 && maxBytes>0)
  private val jdbc=new BootstrapJdbc(open,1)
  import jdbc._
  private val key=s"`${codec.primaryKey}`"
  private def bounds(lower:Option[String],upper:String):(String,Seq[Any])=
    lower.fold((s"$key<=?",Seq[Any](new java.math.BigDecimal(upper))))(l=>(s"$key>? AND $key<=?",Seq[Any](new java.math.BigDecimal(l),new java.math.BigDecimal(upper))))
  def validate(expectedLineage:Option[String]=None):Unit=connection {c=>
    require(expectedLineage.forall(_==query(c,"SELECT @@server_uuid AS uuid").head("uuid")),"bootstrap source lineage changed")
    val f=query(c,"SELECT @@binlog_format AS f,@@binlog_row_image AS r,@@binlog_order_commits AS o").head
    require(f("f")=="ROW" && f("r")=="FULL" && f("o")=="1","unsupported bootstrap binlog profile")
    require(query(c,"SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",table).head("engine")=="InnoDB","bootstrap source requires InnoDB")
    require(query(c,"SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='pekko_capture_control'").headOption.exists(_("engine")=="InnoDB"),"bootstrap control markers require InnoDB")
    val columns=c.getMetaData.getColumns(c.getCatalog,null,table,null);val b=Vector.newBuilder[(String,Int)]
    try while(columns.next())if(columns.getString("TABLE_NAME")==table)b+=columns.getString("COLUMN_NAME")->columns.getInt("DATA_TYPE") finally columns.close()
    require(b.result()==codec.columns,"source schema differs from sealed schema");codec.validateTable(c,table)
  }
  def maximum():Option[String]=connection(c=>Option(query(c,s"SELECT MAX($key) AS n FROM `$table`").head("n")))
  def nextUpper(lower:Option[String],upper:String,rows:Int):String=connection {c=>
    val (where,args)=bounds(lower,upper)
    val keys=query(c,s"SELECT $key AS n FROM `$table` WHERE $where ORDER BY $key LIMIT ?",(args:+rows):_*)
    if(keys.size<rows)upper else keys.last("n")
  }
  def scan(window:String,lower:Option[String],upper:String,pageRows:Int,store:BootstrapStore,canceled:()=>Boolean):Unit=connection {c=>
    c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setReadOnly(true);c.setAutoCommit(false)
    val q=c.createStatement();try q.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY")finally q.close()
    val connectionId=query(c,"SELECT CONNECTION_ID() AS id").head("id")
    observe("READ_OPEN",JsObject("window"->JsString(window),"connectionId"->JsString(connectionId)))
    var cursor=lower;var index=0;var total=0;var bytes=0L;var done=false
    val start=System.nanoTime()
    try {
      while(!done) {
        require(!canceled(),"bootstrap canceled during source window")
        require(System.nanoTime()-start<30000000000L,"bootstrap read window exceeded 30 seconds")
        val (where,args)=bounds(cursor,upper)
        val rows=statement(c,s"SELECT ${codec.columns.map(x=>s"`${x._1}`").mkString(",")} FROM `$table` WHERE $where ORDER BY $key LIMIT ?",args:+pageRows){p=>
          val r=p.executeQuery();val b=Vector.newBuilder[JsObject];try while(r.next())b+=codec.read(r)finally r.close();b.result()}
        total+=rows.size;bytes+=cn.xuyinyin.magic.cdc.MySQLCdcGroup.canonical(JsArray(rows)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
        require(total<=maxRows && bytes<=maxBytes,"bootstrap source window capacity exceeded")
        store.rawPage(window,index,rows)
        observe("RAW_PAGE",JsObject("window"->JsString(window),"page"->JsNumber(index),"rows"->JsNumber(rows.size),"connectionId"->JsString(connectionId)))
        index+=1;done=rows.size<pageRows
        if(rows.nonEmpty)cursor=Some(rows.last.fields(codec.primaryKey).toString.replace("\"",""))
      }
    } finally {c.rollback();observe("READ_ROLLED_BACK",JsObject("window"->JsString(window),"connectionId"->JsString(connectionId)))}
  }
}
