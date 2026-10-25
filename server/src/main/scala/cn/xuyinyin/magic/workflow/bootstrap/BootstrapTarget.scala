package cn.xuyinyin.magic.workflow.bootstrap

import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import java.sql.Connection
import spray.json._

/** Initialization receipts have a separate namespace from original CDC complete groups. */
final class BootstrapTarget(open:()=>Connection,val table:String,codec:BootstrapRowCodec,val id:String) {
  require(table.matches("[A-Za-z_][A-Za-z0-9_]*"))
  private val jdbc=new BootstrapJdbc(open)
  import jdbc._
  private def canonical(v:JsValue)=MySQLCdcGroup.canonical(v)
  private def hash(s:String)=MySQLCdcGroup.hash(s)
  private val targetKey=hash(table)
  def claim():Unit={
    connection {c=>
      Vector(
        "CREATE TABLE IF NOT EXISTS pekko_bootstrap_targets(target_key CHAR(64) PRIMARY KEY,bootstrap_id VARCHAR(128) NOT NULL,table_name VARCHAR(128) NOT NULL,schema_id CHAR(64) NOT NULL)",
        "CREATE TABLE IF NOT EXISTS pekko_bootstrap_receipts(bootstrap_id VARCHAR(128),operation_id VARCHAR(160),payload LONGTEXT NOT NULL,digest CHAR(64) NOT NULL,PRIMARY KEY(bootstrap_id,operation_id))"
      ).foreach(q=>update(c,q+" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin"))
      require(query(c,"SELECT COUNT(*) AS n FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('pekko_bootstrap_targets','pekko_bootstrap_receipts') AND engine='InnoDB'").head("n")=="2","bootstrap target receipts require InnoDB")
      require(query(c,"SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",table).head("engine")=="InnoDB","bootstrap target requires InnoDB")
      val indexes=c.getMetaData.getIndexInfo(c.getCatalog,null,table,true,false)
      try {while(indexes.next())if(indexes.getString("COLUMN_NAME")!=null)require(indexes.getString("INDEX_NAME")=="PRIMARY","bootstrap v1 forbids secondary unique constraints")}finally indexes.close()
      require(query(c,"SELECT COUNT(*) AS n FROM information_schema.triggers WHERE event_object_schema=DATABASE() AND event_object_table=?",table).head("n")=="0","bootstrap v1 forbids target business triggers")
      val keys=c.getMetaData.getImportedKeys(c.getCatalog,null,table);try require(!keys.next(),"bootstrap v1 forbids target foreign keys")finally keys.close()
      val rs=c.getMetaData.getPrimaryKeys(c.getCatalog,null,table);val names=Vector.newBuilder[String]
      try {while(rs.next())names+=rs.getString("COLUMN_NAME")}finally rs.close()
      require(names.result()==Vector(codec.primaryKey),"bootstrap target key mismatch")
      val metadata=c.getMetaData.getColumns(c.getCatalog,null,table,null);val cols=Vector.newBuilder[(String,Int)]
      try {while(metadata.next())if(metadata.getString("TABLE_NAME")==table)cols+=metadata.getString("COLUMN_NAME")->metadata.getInt("DATA_TYPE")}finally metadata.close()
      require(cols.result()==codec.columns,"bootstrap target columns differ from source");codec.validateTable(c,table)
    }
    transaction {c=>
      val inserted=update(c,"INSERT IGNORE INTO pekko_bootstrap_targets VALUES(?,?,?,?)",targetKey,id,table,codec.schemaId)==1
      val r=query(c,"SELECT * FROM pekko_bootstrap_targets WHERE target_key=? FOR UPDATE",targetKey).head
      require(r("bootstrap_id")==id && r("schema_id")==codec.schemaId,"target already belongs to another initialization")
      if(inserted)require(query(c,s"SELECT COUNT(*) AS n FROM `$table`").head("n")=="0","bootstrap requires an initially empty target")
    }
  }
  private def payload(window:String,index:Int,manifest:String,rows:Vector[JsObject]):String=canonical(JsObject(
    "version"->JsNumber(1),"schema"->JsString(codec.schemaId),"table"->JsString(table),"window"->JsString(window),"page"->JsNumber(index),
    "manifest"->JsString(hash(manifest)),"rows"->JsArray(rows)))
  private def receipt(c:Connection,operation:String,proof:String)(effect: =>Unit):Boolean={
    query(c,"SELECT bootstrap_id FROM pekko_bootstrap_targets WHERE target_key=? FOR UPDATE",targetKey).headOption match {
      case Some(r)=>require(r("bootstrap_id")==id,"target claim lost")
      case None=>throw new IllegalArgumentException("target not claimed")
    }
    query(c,"SELECT payload,digest FROM pekko_bootstrap_receipts WHERE bootstrap_id=? AND operation_id=?",id,operation).headOption match {
      case Some(r)=>require(r("payload")==proof && r("digest")==hash(proof),"bootstrap target receipt conflict");false
      case None=>effect;update(c,"INSERT INTO pekko_bootstrap_receipts VALUES(?,?,?,?)",id,operation,proof,hash(proof));true
    }
  }
  def applyPage(window:String,index:Int,manifest:String,rows:Vector[JsObject]):Boolean=transaction {c=>
    val names=codec.columns.map(x=>s"`${x._1}`")
    receipt(c,window+":"+index,payload(window,index,manifest,rows)) {
      val sql=s"INSERT INTO `$table` (${names.mkString(",")}) VALUES (${names.map(_=>"?").mkString(",")}) ON DUPLICATE KEY UPDATE ${names.map(n=>s"$n=VALUES($n)").mkString(",")}"
      val q=c.prepareStatement(sql);q.setQueryTimeout(30)
      try rows.foreach {row=>codec.bind(q,row);q.executeUpdate()}finally q.close()
    }
  }
  def verifyPage(window:String,index:Int,manifest:String,rows:Vector[JsObject]):Unit=connection {c=>
    val expected=payload(window,index,manifest,rows)
    val r=query(c,"SELECT payload,digest FROM pekko_bootstrap_receipts WHERE bootstrap_id=? AND operation_id=?",id,window+":"+index).head
    require(r("payload")==expected && r("digest")==hash(expected),"bootstrap target proof missing/corrupted")
  }
  def activate(proof:String):Unit=transaction(c=>{receipt(c,"ACTIVATE",proof)(());()})
  def activation:Option[String]=connection(c=>query(c,"SELECT payload,digest FROM pekko_bootstrap_receipts WHERE bootstrap_id=? AND operation_id='ACTIVATE'",id).headOption.map {r=>require(hash(r("payload"))==r("digest"));r("payload")})
  def verifyGroup(group:String,identity:String):Unit=connection {c=>
    val g=group.parseJson.asJsObject;val gid=g.fields("groupId").asInstanceOf[JsString].value
    val r=query(c,"SELECT evidence FROM pekko_sync_cdc_group_ledger WHERE group_id=?",gid).head
    require(r("evidence")==canonical(JsObject("identity"->identity.parseJson,"targetTable"->JsString(table),"group"->g)),"replay target group proof missing/conflicting")
  }
  def evidence:JsArray=connection(c=>JsArray(query(c,"SELECT * FROM pekko_bootstrap_receipts WHERE bootstrap_id=? ORDER BY operation_id",id).map(r=>JsObject(r.map {case(k,v)=>k->JsString(v)}))))
}
