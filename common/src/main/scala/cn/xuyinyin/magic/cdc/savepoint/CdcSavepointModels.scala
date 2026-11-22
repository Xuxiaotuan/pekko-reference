package cn.xuyinyin.magic.cdc.savepoint

import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import spray.json._

sealed trait CdcSavepointStatus { def value: String }
object CdcSavepointStatus {
  case object Creating extends CdcSavepointStatus { val value = "CREATING" }
  case object Sealed   extends CdcSavepointStatus { val value = "SEALED"   }
  case object Failed   extends CdcSavepointStatus { val value = "FAILED"   }
  case object Deleted  extends CdcSavepointStatus { val value = "DELETED"  }
}

/** The shared Reader boundary used by a Runtime Savepoint. */
final case class CaptureEpoch(
    captureId: String,
    epoch: Long,
    sourceLineage: String,
    sealedSequence: Long,
    capturePosition: Option[String],
    routeDigest: String,
    tableSetDigest: String) {
  require(captureId.matches("[A-Za-z0-9_-]{1,64}"), "invalid capture id")
  require(epoch >= 0 && sealedSequence >= 0, "invalid capture epoch")
  require(sourceLineage.nonEmpty && routeDigest.matches("[a-f0-9]{64}") && tableSetDigest.matches("[a-f0-9]{64}"), "invalid capture epoch identity")
  def canonical: String = MySQLCdcGroup.canonical(
    JsObject(
      "captureId"       -> JsString(captureId),
      "epoch"           -> JsNumber(epoch),
      "sourceLineage"   -> JsString(sourceLineage),
      "sealedSequence"  -> JsNumber(sealedSequence),
      "capturePosition" -> capturePosition.map(JsString(_)).getOrElse(JsNull),
      "routeDigest"     -> JsString(routeDigest),
      "tableSetDigest"  -> JsString(tableSetDigest)))
  def digest: String = MySQLCdcGroup.hash(canonical)
}

/** A reference to one durable table-level checkpoint; runtime handles are excluded. */
final case class TableCheckpointRef(
    taskId: String,
    generation: String,
    phase: String,
    checkpointRef: String,
    journalRef: Option[String],
    targetRef: Option[String],
    schemaDigest: Option[String],
    tableDigest: String,
    inclusionState: String = "ACTIVE") {
  require(taskId.matches("[a-f0-9]{64}") && generation.nonEmpty && phase.nonEmpty, "invalid table checkpoint identity")
  require(checkpointRef.matches("[a-f0-9]{64}") && tableDigest.matches("[a-f0-9]{64}"), "invalid table checkpoint digest")
  require(Set("ACTIVE", "TOMBSTONE").contains(inclusionState), "invalid savepoint inclusion state")
  def canonical: String = MySQLCdcGroup.canonical(
    JsObject(
      "taskId"         -> JsString(taskId),
      "generation"     -> JsString(generation),
      "phase"          -> JsString(phase),
      "checkpointRef"  -> JsString(checkpointRef),
      "journalRef"     -> journalRef.map(JsString(_)).getOrElse(JsNull),
      "targetRef"      -> targetRef.map(JsString(_)).getOrElse(JsNull),
      "schemaDigest"   -> schemaDigest.map(JsString(_)).getOrElse(JsNull),
      "tableDigest"    -> JsString(tableDigest),
      "inclusionState" -> JsString(inclusionState)))
}

final case class CdcSavepointPage(savepointId: String, pageNo: Int, items: Vector[TableCheckpointRef], pageDigest: String) {
  require(savepointId.matches("[A-Za-z0-9_-]{1,128}") && pageNo >= 0 && items.nonEmpty, "invalid savepoint page")
  require(pageDigest.matches("[a-f0-9]{64}"), "invalid savepoint page digest")
}

final case class CdcSavepointTaskPage(items: Vector[TableCheckpointRef], nextAfter: Option[(String, String)])

final case class CdcSavepointHeader(
    savepointId: String,
    captureId: String,
    sourceLineage: String,
    epoch: CaptureEpoch,
    tableSetDigest: String,
    pageCount: Int,
    ownerEpoch: String,
    protocolVersion: Int,
    status: String,
    manifestDigest: Option[String]) {
  require(
    savepointId.matches("[A-Za-z0-9_-]{1,128}") && captureId == epoch.captureId && sourceLineage == epoch.sourceLineage,
    "savepoint identity mismatch")
  require(tableSetDigest.matches("[a-f0-9]{64}") && pageCount >= 0 && ownerEpoch.nonEmpty && protocolVersion > 0, "invalid savepoint header")
  require(Set("CREATING", "SEALED", "FAILED", "DELETED").contains(status), "invalid savepoint status")
  require((Set("SEALED", "DELETED").contains(status)) == manifestDigest.nonEmpty, "sealed manifest digest mismatch")
}

object CdcSavepointDigest {
  def tableSet(items: Vector[TableCheckpointRef]): String =
    MySQLCdcGroup.hash(items.sortBy(x => (x.taskId, x.generation)).map(_.canonical).mkString("[", ",", "]"))
  def page(savepointId: String, pageNo: Int, items: Vector[TableCheckpointRef]): String = MySQLCdcGroup.hash(
    MySQLCdcGroup.canonical(
      JsObject(
        "savepointId" -> JsString(savepointId),
        "pageNo"      -> JsNumber(pageNo),
        "items"       -> JsArray(items.sortBy(x => (x.taskId, x.generation)).map(x => x.canonical.parseJson)))))
  def manifest(header: CdcSavepointHeader, pages: Vector[CdcSavepointPage]): String = MySQLCdcGroup.hash(
    MySQLCdcGroup.canonical(
      JsObject(
        "savepointId"    -> JsString(header.savepointId),
        "captureId"      -> JsString(header.captureId),
        "epoch"          -> header.epoch.canonical.parseJson,
        "tableSetDigest" -> JsString(header.tableSetDigest),
        "pages" -> JsArray(pages.sortBy(_.pageNo).map(p => JsObject("pageNo" -> JsNumber(p.pageNo), "pageDigest" -> JsString(p.pageDigest)))))))
}
