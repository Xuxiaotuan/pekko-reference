package cn.xuyinyin.magic.workflow.nodes.cdc

import spray.json._
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** Versioned, streaming-only contract. A generation must outlive ordinary process recovery. */
final case class MySQLCdcGroupProtocol(replicationId: String, generation: String, sourceLineage: String) {
  def json: JsObject = JsObject("version" -> JsNumber(1), "replicationId" -> JsString(replicationId),
    "generation" -> JsString(generation), "sourceLineage" -> JsString(sourceLineage))
}

object MySQLCdcGroupProtocol {
  val CursorKind = "mysql.binlog.groups.v1"
  def parse(value: JsValue): MySQLCdcGroupProtocol = {
    val f = value.asJsObject.fields
    require(f.keySet == Set("version", "replicationId", "generation", "sourceLineage") &&
      f("version") == JsNumber(1), "invalid CDC group protocol version or fields")
    def string(k: String): String = f(k) match {
      case JsString(s) if s.nonEmpty && s.length <= 255 => s
      case _ => throw new IllegalArgumentException(s"invalid CDC group protocol $k")
    }
    MySQLCdcGroupProtocol(string("replicationId"), string("generation"), string("sourceLineage"))
  }
}

final case class MySQLCdcGroup(groupId: String, members: Vector[(String, MySQLCdcEnvelope)]) {
  def canonicalJson: String = MySQLCdcGroup.canonical(JsObject("version" -> JsNumber(1),
    "groupId" -> JsString(groupId), "members" -> JsArray(members.map { case (role, envelope) =>
      JsObject("eventId" -> JsString(MySQLCdcGroup.eventId(groupId, role)), "role" -> JsString(role),
        "envelope" -> envelope.canonicalJson.parseJson)
    })))
}

object MySQLCdcGroup extends DefaultJsonProtocol {
  def id(protocol: MySQLCdcGroupProtocol, e: MySQLCdcEnvelope): String = {
    require(!e.source.snapshot && e.op != CdcOperation.Read, "snapshot is not supported by CDC group protocol")
    val s = e.source
    require(s.file.exists(_.nonEmpty) && s.position.exists(_ >= 0) && s.row.exists(_ >= 0),
      "CDC group requires physical file/pos/row")
    hash(canonical(JsArray(Vector(protocol.json, JsString(s.database), JsString(s.table),
      JsString(s.file.get), JsNumber(s.position.get), JsNumber(s.row.get)))))
  }
  def eventId(groupId: String, role: String): String = hash(canonical(JsArray(JsString(groupId), JsString(role))))

  def parse(json: String, protocol: MySQLCdcGroupProtocol): MySQLCdcGroup = {
    val f = json.parseJson.asJsObject.fields
    require(f.keySet == Set("version", "groupId", "members") && f("version") == JsNumber(1), "invalid CDC group fields")
    val gid = f("groupId").convertTo[String]
    val members = f("members") match {
      case JsArray(values) => values.map { value =>
        val m = value.asJsObject.fields
        require(m.keySet == Set("eventId", "role", "envelope"), "invalid CDC group member fields")
        val role = m("role").convertTo[String]
        val e = MySQLCdcEnvelope.parse(canonical(m("envelope"))).fold(
          error => throw new IllegalArgumentException(error.message), identity)
        require(id(protocol, e) == gid && m("eventId") == JsString(eventId(gid, role)), "CDC group identity mismatch")
        role -> e
      }.toVector
      case _ => throw new IllegalArgumentException("CDC group members must be an array")
    }
    members match {
      case Vector((role, e)) => require(role == "single." + e.op.code, "invalid single CDC group role")
      case Vector(("pk.d", d), ("pk.c", c)) =>
        require(d.op == CdcOperation.Delete && c.op == CdcOperation.Create && d.key != c.key,
          "invalid primary-key CDC group members")
        require(d.source == c.source, "primary-key CDC group source mismatch")
        val oldImage = d.before.getOrElse(throw new IllegalArgumentException("missing PK before image"))
        val newImage = c.after.getOrElse(throw new IllegalArgumentException("missing PK after image"))
        require(oldImage.fields.keySet == newImage.fields.keySet, "primary-key CDC group schema mismatch")
      case _ => throw new IllegalArgumentException("incomplete or unordered CDC group")
    }
    MySQLCdcGroup(gid, members)
  }

  def canonical(value: JsValue): String = value match {
    case JsObject(f) => f.toVector.sortBy(_._1).map { case (k, v) => JsString(k).compactPrint + ":" + canonical(v) }.mkString("{", ",", "}")
    case JsArray(v) => v.map(canonical).mkString("[", ",", "]")
    case v => v.compactPrint
  }
  def hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString
}
