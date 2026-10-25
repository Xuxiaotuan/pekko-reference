package cn.xuyinyin.magic.workflow.bootstrap

import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import java.sql.{PreparedStatement,ResultSet,Types}
import java.time.LocalDateTime
import spray.json._

/** Deliberately narrow v1 type profile. Unsupported types fail before reading/writing data. */
final class BootstrapRowCodec(val schema:String) {
  private val fields=schema.parseJson.asJsObject.fields
  private val descriptors=fields("columns").asInstanceOf[JsArray].elements.map(_.asJsObject)
  require(descriptors.forall(c=> !c.fields.get("generated").contains(JsBoolean(true)) && !c.fields.get("typeName").exists(_.toString.toUpperCase(java.util.Locale.ROOT).contains("UNSIGNED"))),"bootstrap forbids generated/unsigned columns")
  val columns:Vector[(String,Int)]=fields("columns").asInstanceOf[JsArray].elements.map {x=>
    val f=x.asJsObject.fields;f("name").asInstanceOf[JsString].value->f("jdbcType").asInstanceOf[JsNumber].value.toInt}.toVector
  val primaryKey=fields("primaryKeyColumnNames").asInstanceOf[JsArray].elements match {
    case Vector(JsString(s))=>s
    case _=>throw new IllegalArgumentException("bootstrap requires a single integral primary key")
  }
  private val integers=Set(Types.BIGINT,Types.INTEGER,Types.SMALLINT,Types.TINYINT)
  private val strings=Set(Types.VARCHAR,Types.CHAR,Types.LONGVARCHAR)
  require(integers.contains(columns.find(_._1==primaryKey).get._2),"bootstrap key must be integral")
  require(columns.forall {case(n,t)=>n.matches("[A-Za-z_][A-Za-z0-9_]*") &&
    (integers.contains(t)||strings.contains(t)||Set(Types.DECIMAL,Types.NUMERIC,Types.TIMESTAMP,Types.BOOLEAN,Types.BIT).contains(t))},"unsupported bootstrap schema type")
  val schemaId=MySQLCdcGroup.hash(MySQLCdcGroup.canonical(schema.parseJson))
  def validateTable(c:java.sql.Connection,table:String):Unit={
    val jdbc=new BootstrapJdbc(()=>throw new IllegalStateException("existing connection only"))
    val actual=jdbc.query(c,"SELECT column_name AS name,is_nullable AS nullable,character_maximum_length AS chars,numeric_precision AS precision_value,numeric_scale AS scale_value,datetime_precision AS temporal_precision,column_type AS type_value,extra AS extra_value,character_set_name AS charset_value FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position",table)
    require(actual.map(_("name"))==columns.map(_._1),"bootstrap columns changed")
    descriptors.zip(actual).foreach {case(d,a)=>
      val f=d.fields;val t=f("jdbcType").asInstanceOf[JsNumber].value.toInt
      def matches(field:String,value:String):Unit=f.get(field).filter(_!=JsNull).foreach(x=>require(x.toString==value,"bootstrap column "+field+" differs"))
      f.get("optional").foreach(v=>require(v==JsBoolean(a("nullable")=="YES"),"bootstrap nullability differs"))
      require(!a("type_value").toUpperCase(java.util.Locale.ROOT).contains("UNSIGNED") && !a("extra_value").contains("GENERATED"),"bootstrap unsupported physical column")
      if(strings(t)){matches("length",a("chars"));f.get("charsetName").filter(_!=JsNull).foreach(v=>require(v==JsString(a("charset_value")),"bootstrap charset differs"))}
      if(t==Types.DECIMAL || t==Types.NUMERIC){matches("length",a("precision_value"));matches("scale",a("scale_value"))}
      if(t==Types.TIMESTAMP){require(f.get("typeName").forall(_==JsString("DATETIME")) && a("type_value").takeWhile(_!='(').equalsIgnoreCase("datetime"),"bootstrap v1 supports DATETIME, not zoned TIMESTAMP");matches("length",a("temporal_precision"))}
      if(t==Types.BIT)require(a("type_value")=="bit(1)","bootstrap supports BIT(1) only")
    }
  }
  def read(r:ResultSet):JsObject=JsObject(columns.zipWithIndex.map {case((n,t),i)=>
    val value:JsValue=if(r.getObject(i+1)==null)JsNull
    else if(integers(t))JsNumber(BigDecimal(r.getBigDecimal(i+1)))
    else if(strings(t))JsString(r.getString(i+1))
    else t match {
      case Types.DECIMAL|Types.NUMERIC=>JsString(r.getBigDecimal(i+1).toPlainString)
      case Types.TIMESTAMP=>JsString(r.getObject(i+1,classOf[LocalDateTime]).toString)
      case Types.BOOLEAN|Types.BIT=>JsBoolean(r.getBoolean(i+1))
    }
    n->value
  }.toMap)
  def bind(q:PreparedStatement,row:JsObject):Unit={
    require(row.fields.keySet==columns.map(_._1).toSet,"bootstrap image must contain every sealed column")
    columns.zipWithIndex.foreach {case((name,t),i)=>row.fields(name) match {
      case JsNull=>q.setNull(i+1,t)
      case value if integers(t)=>val n=BigDecimal(value match {case JsNumber(v)=>v.toString;case JsString(v)=>v;case _=>throw new IllegalArgumentException("invalid integral value")});q.setBigDecimal(i+1,n.bigDecimal)
      case JsString(s) if strings(t)=>q.setString(i+1,s)
      case JsString(s) if t==Types.DECIMAL || t==Types.NUMERIC=>q.setBigDecimal(i+1,new java.math.BigDecimal(s))
      case JsString(s) if t==Types.TIMESTAMP=>q.setObject(i+1,LocalDateTime.parse(s))
      case JsBoolean(v) if t==Types.BOOLEAN || t==Types.BIT=>q.setBoolean(i+1,v)
      case _=>throw new IllegalArgumentException("bootstrap value does not match sealed schema")
    }}
  }
}
