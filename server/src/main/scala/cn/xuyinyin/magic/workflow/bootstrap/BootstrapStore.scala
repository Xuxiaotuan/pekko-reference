package cn.xuyinyin.magic.workflow.bootstrap

import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import java.sql.Connection
import spray.json._

/** Immutable input/output, not a second execution state machine. No TTL or automatic GC. */
final class BootstrapStore(open:()=>Connection,val id:String,maxBytes:Long) {
  require(id.matches("[A-Za-z0-9_-]{1,128}") && maxBytes>0)
  val jdbc=new BootstrapJdbc(open)
  import jdbc._
  private def hash(s:String)=MySQLCdcGroup.hash(s)
  private def canonical(v:JsValue)=MySQLCdcGroup.canonical(v)
  def initialize(definition:String,recovering:Boolean=false):Unit={
    connection {c=>
    val lock="bootstrap-register-"+hash(c.getCatalog).take(32)
    require(query(c,"SELECT GET_LOCK(?,5) AS acquired",lock).head("acquired")=="1","bootstrap registry admission timed out")
    try {
    Vector(
      "CREATE TABLE IF NOT EXISTS pekko_bootstrap_storage(bootstrap_id VARCHAR(128) PRIMARY KEY,definition_hash CHAR(64) NOT NULL,used_bytes BIGINT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_bootstrap_windows(bootstrap_id VARCHAR(128),window_id VARCHAR(128),spec_json LONGTEXT NOT NULL,spec_hash CHAR(64) NOT NULL,manifest_json LONGTEXT NULL,manifest_hash CHAR(64) NULL,PRIMARY KEY(bootstrap_id,window_id))",
      "CREATE TABLE IF NOT EXISTS pekko_bootstrap_done(bootstrap_id VARCHAR(128),ordinal BIGINT,window_id VARCHAR(128),parent_root CHAR(64) NOT NULL,root CHAR(64) NOT NULL,PRIMARY KEY(bootstrap_id,ordinal))",
      "CREATE TABLE IF NOT EXISTS pekko_bootstrap_pages(bootstrap_id VARCHAR(128),window_id VARCHAR(128),page_kind VARCHAR(16),page_index INT,payload LONGTEXT NOT NULL,digest CHAR(64) NOT NULL,PRIMARY KEY(bootstrap_id,window_id,page_kind,page_index))"
    ).foreach(s=>update(c,s+" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin"))
    require(query(c,"SELECT COUNT(*) AS n FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('pekko_bootstrap_storage','pekko_bootstrap_windows','pekko_bootstrap_pages','pekko_bootstrap_done') AND engine='InnoDB'").head("n")=="4","bootstrap state requires InnoDB")
    transaction {c=>
      query(c,"SELECT definition_hash FROM pekko_bootstrap_storage WHERE bootstrap_id=? FOR UPDATE",id).headOption match {
        case Some(r)=>require(r("definition_hash")==hash(definition),"bootstrap definition changed")
        case None=>
          val orphan=Vector("pekko_bootstrap_windows","pekko_bootstrap_pages","pekko_bootstrap_done").exists(t=>query(c,s"SELECT bootstrap_id FROM $t WHERE bootstrap_id=? LIMIT 1",id).nonEmpty)
          require(!recovering && !orphan,"bootstrap material registry missing; capacity reset forbidden")
          update(c,"INSERT INTO pekko_bootstrap_storage VALUES(?,?,0)",id,hash(definition))
      }
    }
      }finally {require(query(c,"SELECT RELEASE_LOCK(?) AS released",lock).head("released")=="1","bootstrap registry release unknown")}
    }
  }
  private def tx[A](f:Connection=>A):A=transaction {c=>query(c,"SELECT bootstrap_id FROM pekko_bootstrap_storage WHERE bootstrap_id=? FOR UPDATE",id).head;f(c)}
  private def reserve(c:Connection,payload:String):Unit={
    val bytes=payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong+256
    val used=query(c,"SELECT used_bytes FROM pekko_bootstrap_storage WHERE bootstrap_id=?",id).head("used_bytes").toLong
    require(used+bytes<=maxBytes,"bootstrap spool capacity exhausted")
    update(c,"UPDATE pekko_bootstrap_storage SET used_bytes=? WHERE bootstrap_id=?",used+bytes,id)
  }
  def register(window:String,spec:String):Unit=tx {c=>
    val value=canonical(spec.parseJson)
    query(c,"SELECT spec_json,spec_hash FROM pekko_bootstrap_windows WHERE bootstrap_id=? AND window_id=?",id,window).headOption match {
      case Some(r)=>require(r("spec_json")==value && r("spec_hash")==hash(value),"bootstrap window definition conflict")
      case None=>reserve(c,value);update(c,"INSERT INTO pekko_bootstrap_windows(bootstrap_id,window_id,spec_json,spec_hash) VALUES(?,?,?,?)",id,window,value,hash(value))
    }
  }
  def spec(window:String):String=connection {c=>
    val r=query(c,"SELECT spec_json,spec_hash FROM pekko_bootstrap_windows WHERE bootstrap_id=? AND window_id=?",id,window).head
    require(hash(r("spec_json"))==r("spec_hash"),"bootstrap window corrupted");r("spec_json")}
  private def save(c:Connection,window:String,kind:String,index:Int,rows:Vector[JsObject]):String={
    val payload=canonical(JsArray(rows));val digest=hash(payload)
    query(c,"SELECT payload,digest FROM pekko_bootstrap_pages WHERE bootstrap_id=? AND window_id=? AND page_kind=? AND page_index=?",id,window,kind,index).headOption match {
      case Some(r)=>require(r("payload")==payload && r("digest")==digest,"bootstrap page content conflict")
      case None=>reserve(c,payload);update(c,"INSERT INTO pekko_bootstrap_pages VALUES(?,?,?,?,?,?)",id,window,kind,index,payload,digest)
    }
    digest
  }
  def rawPage(window:String,index:Int,rows:Vector[JsObject]):Unit=tx {c=>
    require(query(c,"SELECT manifest_hash FROM pekko_bootstrap_windows WHERE bootstrap_id=? AND window_id=?",id,window).head("manifest_hash")==null,"sealed source window cannot be reread")
    require(index>=0 && (index==0 || query(c,"SELECT page_index FROM pekko_bootstrap_pages WHERE bootstrap_id=? AND window_id=? AND page_kind='RAW' AND page_index=?",id,window,index-1).nonEmpty),"non-contiguous bootstrap pages")
    save(c,window,"RAW",index,rows);()
  }
  def pageCount(window:String,kind:String):Int=connection(c=>query(c,"SELECT COUNT(*) AS total FROM pekko_bootstrap_pages WHERE bootstrap_id=? AND window_id=? AND page_kind=?",id,window,kind).head("total").toInt)
  def page(window:String,kind:String,index:Int):Vector[JsObject]=connection {c=>
    val r=query(c,"SELECT payload,digest FROM pekko_bootstrap_pages WHERE bootstrap_id=? AND window_id=? AND page_kind=? AND page_index=?",id,window,kind,index).head
    require(hash(r("payload"))==r("digest"),"bootstrap page corrupted")
    r("payload").parseJson.asInstanceOf[JsArray].elements.map(_.asJsObject)}
  def manifest(window:String):Option[String]=connection {c=>
    val r=query(c,"SELECT manifest_json,manifest_hash FROM pekko_bootstrap_windows WHERE bootstrap_id=? AND window_id=?",id,window).head
    Option(r("manifest_json")).map {s=>require(hash(s)==r("manifest_hash"),"bootstrap manifest corrupted");s}}
  def seal(window:String,proof:String,rows:Vector[JsObject],pageRows:Int):String={
    require(pageRows>0)
    val hashes=rows.grouped(pageRows).map(r=>hash(canonical(JsArray(r)))).toVector
    val value=canonical(JsObject("version"->JsNumber(1),"window"->JsString(window),"spec"->spec(window).parseJson,
      "proof"->proof.parseJson,"pages"->JsArray(hashes.map(JsString(_))),"rows"->JsNumber(rows.size)))
    tx {c=>
      val old=query(c,"SELECT manifest_json FROM pekko_bootstrap_windows WHERE bootstrap_id=? AND window_id=?",id,window).head("manifest_json")
      if(old!=null)require(old==value,"bootstrap sealed output conflict")else {
        rows.grouped(pageRows).zipWithIndex.foreach {case(p,i)=>save(c,window,"FINAL",i,p)}
        reserve(c,value);update(c,"UPDATE pekko_bootstrap_windows SET manifest_json=?,manifest_hash=? WHERE bootstrap_id=? AND window_id=?",value,hash(value),id,window)
      }
      value
    }
  }
  def verifyFinal(window:String,index:Int,manifest:String):Vector[JsObject]={
    require(this.manifest(window).contains(manifest),"bootstrap manifest reference changed")
    val value=page(window,"FINAL",index)
    require(manifest.parseJson.asJsObject.fields("pages").asInstanceOf[JsArray].elements(index)==JsString(hash(canonical(JsArray(value)))),"bootstrap final page not sealed")
    value
  }
  def complete(index:Long,window:String,parent:String):String=tx {c=>
    val m=query(c,"SELECT manifest_hash FROM pekko_bootstrap_windows WHERE bootstrap_id=? AND window_id=?",id,window).head("manifest_hash")
    require(m!=null,"unsealed completion")
    val root=hash(parent+"|"+index+"|"+window+"|"+m)
    query(c,"SELECT * FROM pekko_bootstrap_done WHERE bootstrap_id=? AND ordinal=?",id,index).headOption match {
      case Some(r)=>require(r("window_id")==window && r("parent_root")==parent && r("root")==root,"completed range conflict")
      case None=>
        require(index==0 && parent.isEmpty || query(c,"SELECT root FROM pekko_bootstrap_done WHERE bootstrap_id=? AND ordinal=?",id,index-1).headOption.exists(_("root")==parent),"broken completion chain")
        update(c,"INSERT INTO pekko_bootstrap_done VALUES(?,?,?,?,?)",id,index,window,parent,root)
    };root
  }
  def completed(after:Long,limit:Int):Vector[(Long,String,String,String)]=connection {c=>
    require(limit>0 && limit<=1000)
    query(c,"SELECT * FROM pekko_bootstrap_done WHERE bootstrap_id=? AND ordinal>? ORDER BY ordinal LIMIT ?",id,after,limit)
      .map(r=>(r("ordinal").toLong,r("window_id"),r("parent_root"),r("root")))
  }
  def evidence:JsObject=connection {c=>
    def json(sql:String)=JsArray(query(c,sql,id).map(r=>JsObject(r.map {case(k,v)=>k->Option(v).fold[JsValue](JsNull)(JsString(_))})))
    JsObject("storage"->json("SELECT * FROM pekko_bootstrap_storage WHERE bootstrap_id=?"),"windows"->json("SELECT * FROM pekko_bootstrap_windows WHERE bootstrap_id=? ORDER BY window_id"),"completed"->json("SELECT * FROM pekko_bootstrap_done WHERE bootstrap_id=? ORDER BY ordinal"),"pages"->json("SELECT window_id,page_kind,page_index,payload,digest FROM pekko_bootstrap_pages WHERE bootstrap_id=? ORDER BY window_id,page_kind,page_index"))
  }
}
