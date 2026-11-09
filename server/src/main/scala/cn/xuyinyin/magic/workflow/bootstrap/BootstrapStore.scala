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
    val tables=Vector(
      "pekko_bootstrap_storage" -> "(bootstrap_id VARCHAR(128) PRIMARY KEY,definition_hash CHAR(64) NOT NULL,used_bytes BIGINT NOT NULL)",
      "pekko_bootstrap_windows" -> "(bootstrap_id VARCHAR(128),window_id VARCHAR(128),spec_json LONGTEXT NOT NULL,spec_hash CHAR(64) NOT NULL,manifest_json LONGTEXT NULL,manifest_hash CHAR(64) NULL,PRIMARY KEY(bootstrap_id,window_id))",
      "pekko_bootstrap_done" -> "(bootstrap_id VARCHAR(128),ordinal BIGINT,window_id VARCHAR(128),parent_root CHAR(64) NOT NULL,root CHAR(64) NOT NULL,PRIMARY KEY(bootstrap_id,ordinal))",
      "pekko_bootstrap_pages" -> "(bootstrap_id VARCHAR(128),window_id VARCHAR(128),page_kind VARCHAR(16),page_index INT,payload LONGTEXT NOT NULL,digest CHAR(64) NOT NULL,PRIMARY KEY(bootstrap_id,window_id,page_kind,page_index))"
    )
    val existing=query(c,"SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('pekko_bootstrap_storage','pekko_bootstrap_windows','pekko_bootstrap_pages','pekko_bootstrap_done')").map(_("table_name")).toSet
    tables.filterNot(t=>existing(t._1)).foreach {case(name,definition)=>
      update(c,"CREATE TABLE IF NOT EXISTS "+name+definition+" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin")
    }
    verifySchema(c)
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
  /** The no-DDL path is safe only for exactly the same durable layout as a fresh store. */
  private def verifySchema(c:Connection):Unit={
    final case class Col(name:String,kind:String,length:Long,nullable:Boolean)
    def v(n:String,l:Long,nullable:Boolean=false)=Col(n,"varchar",l,nullable)
    def ch(n:String,nullable:Boolean=false)=Col(n,"char",64,nullable)
    def text(n:String,nullable:Boolean=false)=Col(n,"longtext",4294967295L,nullable)
    def number(n:String,kind:String)=Col(n,kind,0,false)
    val expected=Map(
      "pekko_bootstrap_storage"->Vector(v("bootstrap_id",128),ch("definition_hash"),number("used_bytes","bigint")),
      "pekko_bootstrap_windows"->Vector(v("bootstrap_id",128),v("window_id",128),text("spec_json"),ch("spec_hash"),text("manifest_json",true),ch("manifest_hash",true)),
      "pekko_bootstrap_done"->Vector(v("bootstrap_id",128),number("ordinal","bigint"),v("window_id",128,true),ch("parent_root"),ch("root")),
      "pekko_bootstrap_pages"->Vector(v("bootstrap_id",128),v("window_id",128),v("page_kind",16),number("page_index","int"),text("payload"),ch("digest")))
    val keys=Map("pekko_bootstrap_storage"->Vector("bootstrap_id"),"pekko_bootstrap_windows"->Vector("bootstrap_id","window_id"),
      "pekko_bootstrap_done"->Vector("bootstrap_id","ordinal"),"pekko_bootstrap_pages"->Vector("bootstrap_id","window_id","page_kind","page_index"))
    val tableList="'pekko_bootstrap_storage','pekko_bootstrap_windows','pekko_bootstrap_pages','pekko_bootstrap_done'"
    val found=query(c,s"SELECT table_name,engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ($tableList)")
    require(found.size==4 && found.forall(r=>expected.contains(r("table_name")) && r("engine")=="InnoDB"),"bootstrap state requires InnoDB tables")
    val columns=query(c,s"SELECT table_name,column_name,data_type,character_maximum_length,is_nullable,character_set_name,collation_name,column_type,extra FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ($tableList) ORDER BY table_name,ordinal_position").groupBy(_("table_name"))
    expected.foreach {case(table,want)=>
      val actual=columns.getOrElse(table,Vector.empty)
      require(actual.size==want.size && actual.zip(want).forall {case(r,x)=>
        val stringType=Set("varchar","char","longtext")(x.kind)
        r("column_name")==x.name && r("data_type")==x.kind &&
          (if(stringType)r("character_maximum_length")!=null && r("character_maximum_length").toLong==x.length && r("character_set_name")=="utf8mb4" && r("collation_name")=="utf8mb4_bin" else r("column_type")!=null && !r("column_type").toLowerCase(java.util.Locale.ROOT).contains("unsigned")) &&
          r("is_nullable")== (if(x.nullable)"YES" else "NO") && Option(r("extra")).forall(_.isEmpty)
      },"bootstrap material schema conflict: "+table)
      val indexes=query(c,"SELECT index_name,non_unique,column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? ORDER BY index_name,seq_in_index",table)
      require(indexes.filter(_("index_name")=="PRIMARY").map(_("column_name"))==keys(table) &&
        !indexes.exists(r=>r("index_name")!="PRIMARY" && r("non_unique")=="0"),"bootstrap material key conflict: "+table)
    }
    require(query(c,s"SELECT constraint_name FROM information_schema.referential_constraints WHERE (constraint_schema=DATABASE() AND table_name IN ($tableList)) OR (unique_constraint_schema=DATABASE() AND referenced_table_name IN ($tableList)) LIMIT 1").isEmpty,
      "bootstrap material foreign keys are unsupported")
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
