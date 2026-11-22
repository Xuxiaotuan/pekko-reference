package cn.xuyinyin.magic.cdc.savepoint

import cn.xuyinyin.magic.cdc.bootstrap.BootstrapJdbc
import java.sql.Connection

/** Immutable, paged Savepoint manifest storage in the CDC control database. */
final class CdcSavepointStore(open: () => Connection) {
  private val jdbc = new BootstrapJdbc(open, 2)
  import jdbc._

  def initialize(): Unit = connection { c => CdcSavepointStore.initializeOn(c) }

  def create(header: CdcSavepointHeader): Unit = transaction { c =>
    require(header.status == "CREATING" && header.manifestDigest.isEmpty, "new savepoint must be CREATING")
    val existing = query(c, "SELECT status FROM pekko_cdc_savepoints_v1 WHERE savepoint_id=?", header.savepointId).headOption
    existing match {
      case Some(row) if row("status") == "CREATING" => ()
      case Some(row) if row("status") == "SEALED"   => throw new IllegalArgumentException("SAVEPOINT_ALREADY_SEALED")
      case Some(_)                                  => throw new IllegalArgumentException("SAVEPOINT_ID_REUSED")
      case None =>
        update(
          c,
          "INSERT INTO pekko_cdc_savepoints_v1(savepoint_id,capture_id,source_lineage,epoch,sealed_sequence,capture_position,route_digest,table_set_digest,page_count,owner_epoch,protocol_version,status,manifest_digest,created_at,sealed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          header.savepointId,
          header.captureId,
          header.sourceLineage,
          header.epoch.epoch,
          header.epoch.sealedSequence,
          header.epoch.capturePosition.orNull,
          header.epoch.routeDigest,
          header.tableSetDigest,
          header.pageCount,
          header.ownerEpoch,
          header.protocolVersion,
          header.status,
          null,
          System.currentTimeMillis(),
          null)
    }
  }

  def appendPage(page: CdcSavepointPage): Unit = transaction { c =>
    val header = readHeader(c, page.savepointId).getOrElse(throw new IllegalArgumentException("SAVEPOINT_NOT_FOUND"))
    require(header.status == "CREATING", "SAVEPOINT_NOT_WRITABLE")
    require(page.pageNo < header.pageCount, "SAVEPOINT_PAGE_OUT_OF_RANGE")
    val expected = CdcSavepointDigest.page(page.savepointId, page.pageNo, page.items)
    require(page.pageDigest == expected, "SAVEPOINT_PAGE_DIGEST_MISMATCH")
    val existing =
      query(c, "SELECT page_digest FROM pekko_cdc_savepoint_pages_v1 WHERE savepoint_id=? AND page_no=?", page.savepointId, page.pageNo).headOption
    existing match {
      case Some(row) =>
        require(row("page_digest") == page.pageDigest, "SAVEPOINT_PAGE_IMMUTABLE")
        val old = query(
          c,
          "SELECT task_id,generation,phase,checkpoint_ref,journal_ref,target_ref,schema_digest,table_digest,inclusion_state FROM pekko_cdc_savepoint_tables_v1 WHERE savepoint_id=? AND page_no=? ORDER BY task_id,generation",
          page.savepointId,
          page.pageNo).map(rowRef)
        require(old.map(_.canonical) == page.items.sortBy(x => (x.taskId, x.generation)).map(_.canonical), "SAVEPOINT_PAGE_IMMUTABLE")
      case None =>
        update(
          c,
          "INSERT INTO pekko_cdc_savepoint_pages_v1(savepoint_id,page_no,page_digest,item_count,created_at) VALUES(?,?,?,?,?)",
          page.savepointId,
          page.pageNo,
          page.pageDigest,
          page.items.size,
          System.currentTimeMillis())
        page.items.foreach { item =>
          update(
            c,
            "INSERT INTO pekko_cdc_savepoint_tables_v1(savepoint_id,page_no,task_id,generation,phase,checkpoint_ref,journal_ref,target_ref,schema_digest,table_digest,inclusion_state) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            page.savepointId,
            page.pageNo,
            item.taskId,
            item.generation,
            item.phase,
            item.checkpointRef,
            item.journalRef.orNull,
            item.targetRef.orNull,
            item.schemaDigest.orNull,
            item.tableDigest,
            item.inclusionState)
        }
    }
  }

  def seal(savepointId: String): CdcSavepointHeader = transaction { c =>
    val header = readHeader(c, savepointId).getOrElse(throw new IllegalArgumentException("SAVEPOINT_NOT_FOUND"))
    if (header.status == "SEALED") header
    else {
      require(header.status == "CREATING", "SAVEPOINT_NOT_WRITABLE")
      val pages = (0 until header.pageCount).toVector.map { n =>
        val page =
          query(c, "SELECT page_digest,item_count FROM pekko_cdc_savepoint_pages_v1 WHERE savepoint_id=? AND page_no=?", savepointId, n).headOption
            .getOrElse(throw new IllegalArgumentException("SAVEPOINT_PAGE_MISSING"))
        require(page("item_count").toInt > 0, "SAVEPOINT_PAGE_EMPTY")
        val refs = query(
          c,
          "SELECT task_id,generation,phase,checkpoint_ref,journal_ref,target_ref,schema_digest,table_digest,inclusion_state FROM pekko_cdc_savepoint_tables_v1 WHERE savepoint_id=? AND page_no=? ORDER BY task_id,generation",
          savepointId,
          n).map(rowRef)
        require(refs.size == page("item_count").toInt && refs.nonEmpty, "SAVEPOINT_PAGE_COUNT_MISMATCH")
        val digest = CdcSavepointDigest.page(savepointId, n, refs)
        require(digest == page("page_digest"), "SAVEPOINT_PAGE_DIGEST_MISMATCH")
        CdcSavepointPage(savepointId, n, refs, digest)
      }
      val refs = query(
        c,
        "SELECT task_id,generation,phase,checkpoint_ref,journal_ref,target_ref,schema_digest,table_digest,inclusion_state FROM pekko_cdc_savepoint_tables_v1 WHERE savepoint_id=? ORDER BY task_id,generation",
        savepointId).map(rowRef)
      require(refs.map(x => (x.taskId, x.generation)).distinct.size == refs.size, "SAVEPOINT_DUPLICATE_TABLE")
      require(CdcSavepointDigest.tableSet(refs) == header.tableSetDigest, "SAVEPOINT_TABLE_SET_MISMATCH")
      val digest = CdcSavepointDigest.manifest(header.copy(status = "CREATING"), pages)
      update(
        c,
        "UPDATE pekko_cdc_savepoints_v1 SET status='SEALED',manifest_digest=?,sealed_at=?,page_count=? WHERE savepoint_id=? AND status='CREATING'",
        digest,
        System.currentTimeMillis(),
        header.pageCount,
        savepointId)
      readHeader(c, savepointId).get
    }
  }

  def readSealed(savepointId: String): Option[(CdcSavepointHeader, Vector[TableCheckpointRef])] = connection { c =>
    readHeader(c, savepointId).filter(_.status == "SEALED").map { h =>
      val pages =
        query(c, "SELECT page_no,page_digest,item_count FROM pekko_cdc_savepoint_pages_v1 WHERE savepoint_id=? ORDER BY page_no", savepointId)
      require(pages.size == h.pageCount && pages.map(_("page_no").toInt) == (0 until h.pageCount).toVector, "SAVEPOINT_PAGE_MISSING")
      val refs = query(
        c,
        "SELECT task_id,generation,phase,checkpoint_ref,journal_ref,target_ref,schema_digest,table_digest,inclusion_state FROM pekko_cdc_savepoint_tables_v1 WHERE savepoint_id=? ORDER BY task_id,generation",
        savepointId).map(rowRef)
      require(refs.map(x => (x.taskId, x.generation)).distinct.size == refs.size, "SAVEPOINT_DUPLICATE_TABLE")
      require(CdcSavepointDigest.tableSet(refs) == h.tableSetDigest, "SAVEPOINT_TABLE_SET_MISMATCH")
      val rebuilt = pages.map { p =>
        val pageRefs = query(
          c,
          "SELECT task_id,generation,phase,checkpoint_ref,journal_ref,target_ref,schema_digest,table_digest,inclusion_state FROM pekko_cdc_savepoint_tables_v1 WHERE savepoint_id=? AND page_no=? ORDER BY task_id,generation",
          savepointId,
          p("page_no").toInt).map(rowRef)
        require(pageRefs.size == p("item_count").toInt && pageRefs.nonEmpty, "SAVEPOINT_PAGE_COUNT_MISMATCH")
        val digest = CdcSavepointDigest.page(savepointId, p("page_no").toInt, pageRefs)
        require(digest == p("page_digest"), "SAVEPOINT_PAGE_DIGEST_MISMATCH")
        CdcSavepointPage(savepointId, p("page_no").toInt, pageRefs, digest)
      }
      require(h.manifestDigest.contains(CdcSavepointDigest.manifest(h, rebuilt)), "SAVEPOINT_MANIFEST_DIGEST_MISMATCH")
      h -> refs
    }
  }

  def delete(savepointId: String): Unit = transaction { c =>
    require(query(c, "SELECT status FROM pekko_cdc_savepoints_v1 WHERE savepoint_id=?", savepointId).nonEmpty, "SAVEPOINT_NOT_FOUND")
    update(c, "UPDATE pekko_cdc_savepoints_v1 SET status='DELETED' WHERE savepoint_id=? AND status='SEALED'", savepointId)
  }

  def fail(savepointId: String): Unit = transaction { c =>
    update(c, "UPDATE pekko_cdc_savepoints_v1 SET status='FAILED' WHERE savepoint_id=? AND status='CREATING'", savepointId)
  }

  private def rowRef(r: Map[String, String]): TableCheckpointRef = TableCheckpointRef(
    r("task_id"),
    r("generation"),
    r("phase"),
    r("checkpoint_ref"),
    Option(r("journal_ref")),
    Option(r("target_ref")),
    Option(r("schema_digest")),
    r("table_digest"),
    r("inclusion_state"))
  private def readHeader(c: Connection, id: String): Option[CdcSavepointHeader] =
    query(c, "SELECT * FROM pekko_cdc_savepoints_v1 WHERE savepoint_id=?", id).headOption.map { r =>
      val epoch = CaptureEpoch(
        r("capture_id"),
        r("epoch").toLong,
        r("source_lineage"),
        r("sealed_sequence").toLong,
        Option(r("capture_position")),
        r("route_digest"),
        r("table_set_digest"))
      CdcSavepointHeader(
        r("savepoint_id"),
        r("capture_id"),
        r("source_lineage"),
        epoch,
        r("table_set_digest"),
        r("page_count").toInt,
        r("owner_epoch"),
        r("protocol_version").toInt,
        r("status"),
        Option(r("manifest_digest")))
    }
}

object CdcSavepointStore {
  def initializeOn(c: Connection): Unit = {
    val suffix =
      if (c.getMetaData.getDatabaseProductName.toLowerCase(java.util.Locale.ROOT).contains("mysql"))
        " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin"
      else ""
    val sql = Vector(
      "CREATE TABLE IF NOT EXISTS pekko_cdc_savepoints_v1(savepoint_id VARCHAR(128) PRIMARY KEY,capture_id VARCHAR(64) NOT NULL,source_lineage VARCHAR(255) NOT NULL,epoch BIGINT NOT NULL,sealed_sequence BIGINT NOT NULL,capture_position LONGTEXT NULL,route_digest CHAR(64) NOT NULL,table_set_digest CHAR(64) NOT NULL,page_count INT NOT NULL,owner_epoch VARCHAR(255) NOT NULL,protocol_version INT NOT NULL,status VARCHAR(16) NOT NULL,manifest_digest CHAR(64) NULL,created_at BIGINT NOT NULL,sealed_at BIGINT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_savepoint_pages_v1(savepoint_id VARCHAR(128) NOT NULL,page_no INT NOT NULL,page_digest CHAR(64) NOT NULL,item_count INT NOT NULL,created_at BIGINT NOT NULL,PRIMARY KEY(savepoint_id,page_no))",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_savepoint_tables_v1(savepoint_id VARCHAR(128) NOT NULL,page_no INT NOT NULL,task_id CHAR(64) NOT NULL,generation VARCHAR(128) NOT NULL,phase VARCHAR(32) NOT NULL,checkpoint_ref CHAR(64) NOT NULL,journal_ref VARCHAR(255) NULL,target_ref VARCHAR(255) NULL,schema_digest CHAR(64) NULL,table_digest CHAR(64) NOT NULL,inclusion_state VARCHAR(16) NOT NULL,PRIMARY KEY(savepoint_id,task_id,generation),INDEX savepoint_page_v1(savepoint_id,page_no))"
    )
    val q = c.createStatement();
    try sql.foreach(s => q.executeUpdate(s + suffix))
    finally q.close()
  }
}
