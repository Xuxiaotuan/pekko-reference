package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import cn.xuyinyin.magic.cdc.bootstrap.BootstrapJdbc
import cn.xuyinyin.magic.cdc.bootstrap.{BootstrapGcCheckpoint,BootstrapJournal,BootstrapJournalProgressVerifier}
import java.sql.Connection
import spray.json._

/** Inclusive original-frame requirement; it is not an acknowledged delivery cursor. */
final case class CaptureGcPin(id:String,kind:String,requiredFrom:Long,revision:Long)
final case class CaptureGcRetentionView(epoch:Long,reclaimedBefore:Long,requiredFrom:Long,
  actualAck:Option[Long],blockers:Vector[String]) {
  def eligible:Boolean=blockers.isEmpty
}

/** Durable protection and bounded GROUP reclamation. ACK/control frames and target evidence survive. */
private[magic] final class CaptureGcRetention(open:()=>Connection,store:MySQLCaptureStore,offsetTable:String,namespace:Option[CaptureGcNamespace.Permit]=None) {
  require(offsetTable.matches("[A-Za-z_][A-Za-z0-9_]*"),"invalid SDK offset table")
  private val location=store.location
  private val jdbc=new BootstrapJdbc(open,1)
  import jdbc._
  private val capture=store.captureId
  private def identity(c:Connection):Unit={
    val r=query(c,"SELECT @@server_uuid AS uuid,DATABASE() AS db").head
    require((r("uuid"),r("db"))==location,"GC state database identity changed")
  }
  private def locked[A](f:Connection=>A):A=transaction {c=>
    c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ)
    identity(c)
    update(c,"SET SESSION innodb_lock_wait_timeout=1")
    val h=query(c,"SELECT lineage FROM pekko_capture_readers WHERE capture_id=? FOR UPDATE",capture).head
    require(h("lineage")==store.lineage,"GC source lineage changed")
    try f(c) finally store.clearProofConnection(c)
  }
  def initialize():Unit=connection {c=>
    identity(c)
    new CaptureAckPaging(open,store).initialize()
    Vector(
      "CREATE TABLE IF NOT EXISTS pekko_capture_gc_retention_v1(capture_id VARCHAR(64) PRIMARY KEY,epoch BIGINT NOT NULL,reclaimed_before BIGINT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_capture_gc_pins_v1(capture_id VARCHAR(64) NOT NULL,pin_id VARCHAR(160) NOT NULL,pin_kind VARCHAR(24) NOT NULL,required_from BIGINT NOT NULL,revision BIGINT NOT NULL,PRIMARY KEY(capture_id,pin_id),INDEX oldest_pin(capture_id,required_from))",
      "CREATE TABLE IF NOT EXISTS pekko_capture_gc_checkpoints_v1(capture_id VARCHAR(64) NOT NULL,proof_id CHAR(64) NOT NULL,subscription_id VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL,proof_json LONGTEXT NOT NULL,journal_id VARCHAR(255) NOT NULL,journal_sequence BIGINT NOT NULL,journal_digest CHAR(64) NOT NULL,phase VARCHAR(24) NOT NULL,PRIMARY KEY(capture_id,proof_id),INDEX subscription_proofs(capture_id,subscription_id,generation,phase,journal_sequence))",
      "CREATE TABLE IF NOT EXISTS pekko_capture_gc_frames_v1(capture_id VARCHAR(64) NOT NULL,seq BIGINT NOT NULL,source_key CHAR(64) NOT NULL,kind VARCHAR(16) NOT NULL,table_name VARCHAR(128) NOT NULL,subscription_id VARCHAR(128) NOT NULL,schema_id CHAR(64) NOT NULL,payload LONGTEXT NOT NULL,digest CHAR(64) NOT NULL,point_json TEXT NULL,resume_json LONGTEXT NULL,proof_id CHAR(64) NOT NULL,PRIMARY KEY(capture_id,seq),UNIQUE KEY occurrence(capture_id,source_key),INDEX deliveries(capture_id,subscription_id,seq))",
      "CREATE TABLE IF NOT EXISTS pekko_capture_gc_operations_v1(capture_id VARCHAR(64) NOT NULL,operation_id VARCHAR(128) NOT NULL,from_seq BIGINT NOT NULL,to_exclusive BIGINT NOT NULL,next_seq BIGINT NOT NULL,epoch BIGINT NOT NULL,phase VARCHAR(24) NOT NULL,deleted_rows BIGINT NOT NULL,reclaimed_bytes BIGINT NOT NULL,PRIMARY KEY(capture_id,operation_id))",
      "CREATE TABLE IF NOT EXISTS pekko_capture_gc_actual_d_v1(capture_id VARCHAR(64) PRIMARY KEY,offset_json LONGTEXT NOT NULL,head_seq BIGINT NOT NULL,proof_id CHAR(64) NOT NULL,operation_id VARCHAR(128) NULL)"
    ).foreach(s=>update(c,s+" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin"))
    require(query(c,"SELECT COUNT(*) AS n FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('pekko_capture_gc_retention_v1','pekko_capture_gc_pins_v1','pekko_capture_gc_checkpoints_v1','pekko_capture_gc_frames_v1','pekko_capture_gc_operations_v1','pekko_capture_gc_actual_d_v1') AND engine='InnoDB'").head("n")=="6",
      "GC retention requires InnoDB")
    Vector(("pekko_capture_gc_checkpoints_v1","subscription_proofs","capture_id,subscription_id,generation,phase,journal_sequence"),
      ("pekko_capture_gc_frames_v1","deliveries","capture_id,subscription_id,seq")).foreach {case(table,index,columns)=>
      if(query(c,"SELECT 1 AS n FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name=? LIMIT 1",table,index).isEmpty)
        update(c,s"ALTER TABLE $table ADD INDEX $index($columns)")
    }
    update(c,"INSERT IGNORE INTO pekko_capture_gc_retention_v1 VALUES(?,0,1)",capture)
  }
  /** New or stronger protection only. Advancing/removing protection requires a recovery certificate. */
  def protect(pin:CaptureGcPin):Long=locked {c=>
    require(pin.id.matches("[A-Za-z0-9_:-]{1,160}") && Set("CONSUMER","WINDOW","AUDIT","ARCHIVE","RECOVERY")(pin.kind) &&
      pin.requiredFrom>=1 && pin.revision>=0,"invalid GC retention requirement")
    val state=query(c,"SELECT epoch,reclaimed_before FROM pekko_capture_gc_retention_v1 WHERE capture_id=? FOR UPDATE",capture).head
    require(pin.requiredFrom>=state("reclaimed_before").toLong,"GC requirement precedes protected recovery floor")
    val prior=query(c,"SELECT * FROM pekko_capture_gc_pins_v1 WHERE capture_id=? AND pin_id=? FOR UPDATE",capture,pin.id).headOption
    prior match {
      case Some(p)=>
        require(p("pin_kind")==pin.kind && pin.revision>=p("revision").toLong,"GC pin identity/revision conflict")
        require(pin.requiredFrom<=p("required_from").toLong,"GC pin advancement requires a recovery certificate")
        if(pin.revision==p("revision").toLong)require(pin.requiredFrom==p("required_from").toLong,"GC pin revision reused with different contents")
      case None=>()
    }
    if(!prior.exists(p=>p("required_from").toLong==pin.requiredFrom && p("revision").toLong==pin.revision)){
      update(c,"INSERT INTO pekko_capture_gc_pins_v1 VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE required_from=VALUES(required_from),revision=VALUES(revision)",
        capture,pin.id,pin.kind,pin.requiredFrom,pin.revision)
      update(c,"UPDATE pekko_capture_gc_retention_v1 SET epoch=epoch+1 WHERE capture_id=?",capture)
    }
    query(c,"SELECT epoch FROM pekko_capture_gc_retention_v1 WHERE capture_id=?",capture).head("epoch").toLong
  }
  def pin(id:String):Option[CaptureGcPin]=connection {c=>
    identity(c)
    query(c,"SELECT * FROM pekko_capture_gc_pins_v1 WHERE capture_id=? AND pin_id=?",capture,id).headOption
      .map(r=>CaptureGcPin(r("pin_id"),r("pin_kind"),r("required_from").toLong,r("revision").toLong))
  }
  /** Durable handoff only. Target verification and activation must still precede pin advancement. */
  def rememberCheckpoint(receipt:BootstrapJournal.PersistedProgress,verifier:BootstrapJournalProgressVerifier):String=locked {c=>
    require(receipt!=null && verifier!=null,"GC Journal receipt missing")
    val proof=receipt.state.gcCheckpoint.getOrElse(throw new IllegalArgumentException("GC checkpoint absent from Journal"))
    val f=proof.parseJson.asJsObject.fields
    def text(k:String)=f(k).asInstanceOf[JsString].value
    require(text("capture")==capture && text("lineage")==store.lineage && receipt.state.phase=="STREAMING" && !receipt.state.cancelRequested,
      "GC checkpoint capture or phase conflict")
    BootstrapGcCheckpoint.validate(proof,capture+"-"+text("subscription"),receipt.state,exact=false)
    verifier.verifyOn(c,receipt,capture,text("subscription"),text("definition"))
    val sub=store.subscriptionOn(c,text("subscription")).getOrElse(throw new IllegalArgumentException("GC subscription missing"))
    require(sub.generation==text("generation") && sub.status=="CAPTURE_READY" &&
      sub.bSequence.exists(_<=receipt.state.deltaAfter),"GC checkpoint generation or capture interval conflict")
    val bytes=query(c,"SELECT HEX(event_payload) AS payload FROM event_journal WHERE persistence_id=? AND sequence_number=?",receipt.persistenceId,receipt.sequenceNr).head("payload")
    val digest=MySQLCdcGroup.hash(bytes.toLowerCase(java.util.Locale.ROOT))
    val canonical=MySQLCdcGroup.canonical(proof.parseJson);val proofId=MySQLCdcGroup.hash(canonical)
    query(c,"SELECT * FROM pekko_capture_gc_checkpoints_v1 WHERE capture_id=? AND proof_id=? FOR UPDATE",capture,proofId).headOption match {
      case Some(old)=>require(old("proof_json")==canonical && old("journal_id")==receipt.persistenceId &&
        old("journal_sequence").toLong<=receipt.sequenceNr,"GC checkpoint immutable identity conflict");verifiedCheckpoint(c,proofId)
      case None=>update(c,"INSERT INTO pekko_capture_gc_checkpoints_v1 VALUES(?,?,?,?,?,?,?,?,'PENDING_TARGET')",capture,proofId,text("subscription"),
        text("generation"),canonical,receipt.persistenceId,receipt.sequenceNr,digest)
    }
    proofId
  }
  def checkpointPhase(proofId:String):Option[String]=connection {c=>
    identity(c)
    query(c,"SELECT phase FROM pekko_capture_gc_checkpoints_v1 WHERE capture_id=? AND proof_id=?",capture,proofId).headOption.map(_("phase"))
  }
  private def verifiedCheckpoint(c:Connection,id:String):Map[String,String]={
    val r=query(c,"SELECT * FROM pekko_capture_gc_checkpoints_v1 WHERE capture_id=? AND proof_id=? FOR UPDATE",capture,id).head
    require(MySQLCdcGroup.hash(MySQLCdcGroup.canonical(r("proof_json").parseJson))==id,"GC checkpoint digest changed")
    val j=query(c,"SELECT HEX(event_payload) AS payload,deleted FROM event_journal WHERE persistence_id=? AND sequence_number=? FOR SHARE",
      r("journal_id"),r("journal_sequence").toLong).headOption.getOrElse(throw new IllegalArgumentException("GC checkpoint Journal missing"))
    require(Set("0","false")(j("deleted").toLowerCase(java.util.Locale.ROOT)) &&
      MySQLCdcGroup.hash(j("payload").toLowerCase(java.util.Locale.ROOT))==r("journal_digest"),"GC checkpoint Journal changed")
    r
  }
  private def guarded[A](c:Connection,reader:SharedMySQLCapture,observer:Connection,account:String,definer:String)(f: =>A):A={
    require(reader.store eq store,"GC Reader and L store are not the same capture capability")
    reader.verifyGcLease(c,location)
    val result=CaptureLedgerGuard.withVerified(observer,c,location._1,location._2,account,definer,namespace)(f)
    reader.verifyGcLease(c,location)
    result
  }
  /** Actual target claim, activation receipt and immutable ledger metadata precede pin movement. */
  def activateCheckpoint(id:String,reader:SharedMySQLCapture,observer:Connection,account:String,definer:String):Unit=reader.withGcTransaction {locked {c=>
    guarded(c,reader,observer,account,definer) {
      val r=verifiedCheckpoint(c,id);val p=r("proof_json").parseJson.asJsObject.fields
      def text(k:String)=p(k).asInstanceOf[JsString].value
      val target=text("target");require(target.matches("[A-Za-z_][A-Za-z0-9_]*"))
      val sub=store.subscriptionOn(c,r("subscription_id")).get
      require(sub.status=="CAPTURE_READY" && sub.generation==r("generation"),"GC subscription is not active")
      require(query(c,"SELECT request_id FROM pekko_capture_subscriptions WHERE capture_id=? AND status='B_PENDING' LIMIT 1",capture).isEmpty,
        "unknown B blocks GC activation")
      val claim=query(c,"SELECT bootstrap_id,schema_id FROM pekko_bootstrap_targets WHERE target_key=? FOR SHARE",MySQLCdcGroup.hash(target)).head
      require(claim("bootstrap_id")==r("subscription_id") && claim("schema_id")==sub.schemaId,"GC target claim changed")
      val activation=MySQLCdcGroup.canonical(p("activation"))
      val receipt=query(c,"SELECT payload,digest FROM pekko_bootstrap_receipts WHERE bootstrap_id=? AND operation_id='ACTIVATE' FOR SHARE",r("subscription_id")).head
      require(receipt("payload")==activation && receipt("digest")==MySQLCdcGroup.hash(activation),"GC target activation changed")
      val floor=p("deltaAfter").asInstanceOf[JsNumber].value.toLongExact
      require(floor>0,"GC checkpoint has no accepted position")
      val consumer=CaptureConsumerIdentity.bootstrap(capture,r("subscription_id"),r("generation"))
      val registered=query(c,"SELECT * FROM pekko_capture_consumers_v1 WHERE capture_id=? AND consumer_id=? FOR UPDATE",capture,consumer).head
      require(registered("observed_floor").toLong>=floor && registered("journal_floor").toLong>=floor,"GC consumer progress is not verified")
      if(r("phase")!="ACTIVE"){
      Vector("consumer:"+consumer,"subscription:"+r("subscription_id")).foreach {pin=>
        val old=query(c,"SELECT required_from FROM pekko_capture_gc_pins_v1 WHERE capture_id=? AND pin_id=? FOR UPDATE",capture,pin).head
        require(old("required_from").toLong<=floor,"GC checkpoint would regress protection")
        update(c,"UPDATE pekko_capture_gc_pins_v1 SET required_from=?,revision=revision+1 WHERE capture_id=? AND pin_id=?",floor,capture,pin)
      }
      update(c,"UPDATE pekko_capture_gc_checkpoints_v1 SET phase='ACTIVE' WHERE capture_id=? AND proof_id=?",capture,id)
      update(c,"UPDATE pekko_capture_gc_retention_v1 SET epoch=epoch+1 WHERE capture_id=?",capture)
      }
    }
  }
  }
  private def deletionBoundary(c:Connection):Long={
    val state=query(c,"SELECT discovery_status,barrier_status FROM pekko_capture_gc_state_v1 WHERE capture_id=? FOR UPDATE",capture).head
    require(state("discovery_status")=="COMPLETE" && state("barrier_status")=="OPEN","GC namespace discovery is incomplete or fenced")
    require(query(c,"SELECT request_id FROM pekko_capture_subscriptions WHERE capture_id=? AND status='B_PENDING' LIMIT 1",capture).isEmpty,"unknown B blocks GC")
    require(query(c,"""SELECT s.request_id FROM pekko_capture_subscriptions s WHERE s.capture_id=? AND NOT EXISTS
      (SELECT 1 FROM pekko_capture_gc_pins_v1 p WHERE p.capture_id=s.capture_id AND p.pin_id=CONCAT('subscription:',s.request_id)) LIMIT 1""",capture).isEmpty,
      "GC subscription protection inventory incomplete")
    require(query(c,"""SELECT x.consumer_id FROM pekko_capture_consumers_v1 x WHERE x.capture_id=? AND NOT EXISTS
      (SELECT 1 FROM pekko_capture_gc_pins_v1 p WHERE p.capture_id=x.capture_id AND p.pin_id=CONCAT('consumer:',x.consumer_id)) LIMIT 1""",capture).isEmpty,
      "GC consumer protection inventory incomplete")
    val oldest=query(c,"SELECT MIN(required_from) AS oldest FROM pekko_capture_gc_pins_v1 WHERE capture_id=?",capture).head("oldest")
    require(oldest!=null,"GC has no retention requirements")
    math.min(oldest.toLong,actualAck(c).getOrElse(throw new IllegalArgumentException("actual D unproven")))
  }
  def plan(operation:String):Unit=locked {c=>
    require(operation.matches("[A-Za-z0-9_-]{1,128}"))
    val head=query(c,"SELECT * FROM pekko_capture_gc_retention_v1 WHERE capture_id=? FOR UPDATE",capture).head
    val old=query(c,"SELECT * FROM pekko_capture_gc_operations_v1 WHERE capture_id=? AND operation_id=? FOR UPDATE",capture,operation)
    if(old.isEmpty){
      val boundary=deletionBoundary(c)
      require(boundary>head("reclaimed_before").toLong,"GC prefix is pinned")
      update(c,"INSERT INTO pekko_capture_gc_operations_v1 VALUES(?,?,?,?,?,?,'PLANNED',0,0)",capture,operation,
        head("reclaimed_before").toLong,boundary,head("reclaimed_before").toLong,head("epoch").toLong)
    }
    update(c,"UPDATE pekko_capture_gc_actual_d_v1 SET operation_id=? WHERE capture_id=? AND operation_id IS NULL",operation,capture)
  }

  /** Commit one bounded actual-D proof page independently of operation planning.
    * A partial proof must survive the caller's later BLOCKED decision. */
  def advanceActualProof():Boolean=locked(c=>actualAck(c).nonEmpty)
  def gcAckProofBatch(limit:Int,maxChildRows:Int):CaptureAckGcResult=locked(c=>new CaptureAckPaging(open,store).gcProofsOn(c,limit,maxChildRows))

  /** Delete original GROUP rows only; the minimum frame/ACK/control evidence and T ledger survive. */
  def deleteBatch(operation:String,limit:Int,reader:SharedMySQLCapture,observer:Connection,account:String,definer:String):String=reader.withGcTransaction {locked {c=>
    require(limit>0 && limit<=128,"GC deletion batch exceeds bound")
    guarded(c,reader,observer,account,definer) {
      val op=query(c,"SELECT * FROM pekko_capture_gc_operations_v1 WHERE capture_id=? AND operation_id=? FOR UPDATE",capture,operation).head
      if(op("phase")=="DONE")"DONE" else {
        val head=query(c,"SELECT * FROM pekko_capture_gc_retention_v1 WHERE capture_id=? FOR UPDATE",capture).head
        require(op("to_exclusive").toLong<=deletionBoundary(c),"GC retention changed; fixed plan is no longer protected")
        if(op("epoch")!=head("epoch"))update(c,"UPDATE pekko_capture_gc_operations_v1 SET epoch=? WHERE capture_id=? AND operation_id=?",head("epoch").toLong,capture,operation)
        val candidates=query(c,"SELECT seq,kind,OCTET_LENGTH(payload) AS payload_bytes FROM pekko_capture_log WHERE capture_id=? AND seq>=? AND seq<? ORDER BY seq LIMIT ? FOR UPDATE",
          capture,op("next_seq").toLong,op("to_exclusive").toLong,limit)
        var bytes=0L
        val bounded=candidates.takeWhile {r=>if(r("kind")=="GROUP")bytes+=r("payload_bytes").toLong;bytes<=1024*1024}
        require(candidates.isEmpty || bounded.nonEmpty,"GC frame exceeds one MiB deletion budget; retain original L")
        val rows=bounded.filter(_("kind")=="GROUP").map {r=>query(c,"SELECT * FROM pekko_capture_log WHERE capture_id=? AND seq=? FOR UPDATE",capture,r("seq").toLong).head}
        val prepared=rows.map {row=>
          val sub=store.subscriptionOn(c,row("subscription_id")).get
          val certificate=query(c,"""SELECT proof_id FROM pekko_capture_gc_checkpoints_v1 WHERE capture_id=? AND subscription_id=? AND generation=? AND phase='ACTIVE'
            AND CAST(JSON_UNQUOTE(JSON_EXTRACT(proof_json,'$.deltaAfter')) AS UNSIGNED)>? ORDER BY journal_sequence DESC LIMIT 1""",capture,sub.requestId,sub.generation,row("seq").toLong)
            .headOption.getOrElse(throw new IllegalArgumentException("GC frame has no active recovery certificate"))
          val proof=verifiedCheckpoint(c,certificate("proof_id"))
          val f=proof("proof_json").parseJson.asJsObject.fields;val target=f("target").asInstanceOf[JsString].value
          val identity=new CapturedLogReader(store,f("database").asInstanceOf[JsString].value).identity(sub.requestId)
          val original=store.proofFrame(c,row("seq").toLong)
          val group=MySQLCdcGroup.parse(original("payload"),store.protocol)
          val targetProtocol=cn.xuyinyin.magic.cdc.MySQLCdcGroupProtocol(sub.requestId,sub.generation,store.lineage)
          val targetGroup=MySQLCdcGroup.id(targetProtocol,group.members.head._2)
          val evidence=query(c,"SELECT evidence FROM pekko_sync_cdc_group_ledger WHERE group_id=? FOR SHARE",targetGroup).head("evidence")
          val tombstone=CaptureGroupTombstone.seal(original,store.protocol,sub.generation,target,identity,evidence)
          val body=CaptureGroupTombstone.Prefix+tombstone
          val gain=original("payload").getBytes(java.nio.charset.StandardCharsets.UTF_8).length-body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length-64
          (row,body,gain,proof("proof_id"))
        }
        // A small frame is safe to retain: advancing the durable cursor past it
        // keeps the operation bounded and lets a later, worthwhile frame be
        // reclaimed.  The original L row is never deleted in this branch.
        val reclaimable=prepared.filter(_._3>=128)
        reclaimable.foreach {case (row,body,gain,proofId)=>
          update(c,"INSERT INTO pekko_capture_gc_frames_v1 VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",capture,row("seq").toLong,row("source_key"),row("kind"),
            row("table_name"),row("subscription_id"),row("schema_id"),body,row("digest"),row("point_json"),row("resume_json"),proofId)
          require(update(c,"DELETE FROM pekko_capture_log WHERE capture_id=? AND seq=? AND digest=?",capture,row("seq").toLong,row("digest"))==1,"GC frame disappeared")
        }
        val saving=reclaimable.map(_._3).sum
        if(bounded.nonEmpty){
          require(update(c,"UPDATE pekko_capture_readers SET used_bytes=used_bytes-? WHERE capture_id=? AND used_bytes>=?",saving,capture,saving)==1,"GC byte accounting underflow")
          update(c,"UPDATE pekko_capture_gc_operations_v1 SET phase='DELETING',next_seq=?,deleted_rows=deleted_rows+?,reclaimed_bytes=reclaimed_bytes+? WHERE capture_id=? AND operation_id=?",
            bounded.last("seq").toLong+1,reclaimable.size,saving,capture,operation)
          "DELETING"
        }else {
          update(c,"UPDATE pekko_capture_gc_operations_v1 SET phase='DONE',next_seq=to_exclusive WHERE capture_id=? AND operation_id=?",capture,operation)
          update(c,"UPDATE pekko_capture_gc_retention_v1 SET reclaimed_before=GREATEST(reclaimed_before,?) WHERE capture_id=?",op("to_exclusive").toLong,capture)
          "DONE"
        }
      }
    }
  }
  }
  /** Read actual SDK D under its row lock. A maximum observed ACK is never substituted. */
  private def actualAck(c:Connection):Option[Long]={
    val values=query(c,s"SELECT offset_key,offset_val FROM `$offsetTable` WHERE offset_val IS NOT NULL LIMIT 2 FOR SHARE")
    val d=CaptureCheckpoint.decode(capture,values.map(r=>r("offset_key")->r("offset_val")))
    if(d.status!="COMPLETE")None else {
      val currentOffset=store.proofOffsetKey(d.value.get)
      query(c,"SELECT seq FROM pekko_capture_log WHERE capture_id=? AND kind='ACK' AND table_name=? ORDER BY seq DESC LIMIT 1",capture,MySQLCdcGroup.hash(currentOffset)).headOption.flatMap {r=>
        val currentHead=r("seq").toLong
        val pages=new CaptureAckPaging(open,store)
        val prior=query(c,"SELECT * FROM pekko_capture_gc_actual_d_v1 WHERE capture_id=? FOR UPDATE",capture).headOption
        val activeOperation=query(c,"SELECT operation_id FROM pekko_capture_gc_operations_v1 WHERE capture_id=? AND phase<>'DONE' ORDER BY operation_id LIMIT 1",capture).headOption.map(_("operation_id"))
        val pinned=prior.filter {p=>
          require(currentHead>=p("head_seq").toLong,"actual D ACK head regressed")
          val proof=pages.gcActualIdentity(p("offset_json"),p("head_seq").toLong)
          val pointerOperation=p.get("operation_id").flatMap(Option(_))
          val pointerOperationActive=pointerOperation.exists(id=>query(c,"SELECT 1 FROM pekko_capture_gc_operations_v1 WHERE capture_id=? AND operation_id=? AND phase<>'DONE'",capture,id).nonEmpty)
          pages.statusIfPresentOn(c,proof).exists {state=>
            state.done && p("proof_id")==pages.proofId(proof) &&
              (pointerOperationActive || (pointerOperation.isEmpty && activeOperation.isEmpty))
          }
        }
        val selected=prior match {
          case Some(p) if pinned.nonEmpty=>p
          case Some(p)=>
            val oldHead=p("head_seq").toLong
            val operationDone=p.get("operation_id").flatMap(Option(_)).exists(id=>query(c,"SELECT 1 FROM pekko_capture_gc_operations_v1 WHERE capture_id=? AND operation_id=? AND phase='DONE'",capture,id).nonEmpty)
            if(!operationDone || currentHead<=oldHead)p
            else {
              val proof=pages.gcActualIdentity(currentOffset,currentHead)
              val id=pages.proofId(proof)
              update(c,"UPDATE pekko_capture_gc_actual_d_v1 SET offset_json=?,head_seq=?,proof_id=?,operation_id=NULL WHERE capture_id=?",currentOffset,currentHead,id,capture)
              query(c,"SELECT * FROM pekko_capture_gc_actual_d_v1 WHERE capture_id=? FOR UPDATE",capture).head
            }
          case None=>
            val proof=pages.gcActualIdentity(currentOffset,currentHead)
            val id=pages.proofId(proof)
            update(c,"INSERT INTO pekko_capture_gc_actual_d_v1 VALUES(?,?,?,?,NULL)",capture,currentOffset,currentHead,id)
            query(c,"SELECT * FROM pekko_capture_gc_actual_d_v1 WHERE capture_id=? FOR UPDATE",capture).head
        }
        val state=pages.advanceGcActualOn(c,selected("offset_json"),selected("head_seq").toLong,128)
        if(state.done)Some(selected("head_seq").toLong)else None
      }
    }
  }
  /** Conservative diagnostics; deletion rechecks inventory, proofs and actual D under the same head lock. */
  def inspect():CaptureGcRetentionView=locked {c=>
    val s=query(c,"SELECT epoch,reclaimed_before FROM pekko_capture_gc_retention_v1 WHERE capture_id=? FOR UPDATE",capture).head
    val admission=query(c,"SELECT discovery_status,barrier_status FROM pekko_capture_gc_state_v1 WHERE capture_id=? FOR UPDATE",capture).head
    val blockers=Vector.newBuilder[String]
    if(admission("discovery_status")!="COMPLETE")blockers+="CONSUMER_DISCOVERY_INCOMPLETE"
    if(admission("barrier_status")!="OPEN")blockers+="CAPTURE_ADMISSION_FENCED"
    if(query(c,"SELECT request_id FROM pekko_capture_subscriptions WHERE capture_id=? AND status='B_PENDING' LIMIT 1",capture).nonEmpty)
      blockers+="UNKNOWN_B"
    val missing=query(c,"""SELECT s.request_id FROM pekko_capture_subscriptions s
      WHERE s.capture_id=? AND NOT EXISTS(SELECT 1 FROM pekko_capture_consumers_v1 x
        WHERE x.capture_id=s.capture_id AND x.subscription_id=s.request_id) LIMIT 1""",capture)
    if(missing.nonEmpty)blockers+="SUBSCRIPTION_WITHOUT_CONSUMER"
    val floor=query(c,"SELECT MIN(required_from) AS oldest FROM pekko_capture_gc_pins_v1 WHERE capture_id=?",capture).head.get("oldest").flatMap(Option(_)).map(_.toLong)
    if(floor.isEmpty)blockers+="NO_RETENTION_REQUIREMENTS"
    if(query(c,"""SELECT consumer_id FROM pekko_capture_consumers_v1 x WHERE capture_id=? AND NOT EXISTS
      (SELECT 1 FROM pekko_capture_gc_pins_v1 p WHERE p.capture_id=x.capture_id AND p.pin_id=CONCAT('consumer:',x.consumer_id)) LIMIT 1""",capture).nonEmpty)
      blockers+="CONSUMER_PIN_MISSING"
    if(query(c,"""SELECT request_id FROM pekko_capture_subscriptions x WHERE capture_id=? AND NOT EXISTS
      (SELECT 1 FROM pekko_capture_gc_pins_v1 p WHERE p.capture_id=x.capture_id AND p.pin_id=CONCAT('subscription:',x.request_id)) LIMIT 1""",capture).nonEmpty)
      blockers+="SUBSCRIPTION_PIN_MISSING"
    val ack=actualAck(c)
    if(ack.isEmpty)blockers+="ACTUAL_D_UNPROVEN"
    if(ack.exists(_<=s("reclaimed_before").toLong))blockers+="ACTUAL_D_PINS_PREFIX"
    if(query(c,"SELECT 1 AS n FROM pekko_capture_gc_checkpoints_v1 WHERE capture_id=? AND phase='ACTIVE' LIMIT 1",capture).isEmpty)
      blockers+="RECOVERY_CERTIFICATES_REQUIRED"
    if(floor.exists(_<=s("reclaimed_before").toLong))blockers+="PREFIX_PINNED"
    CaptureGcRetentionView(s("epoch").toLong,s("reclaimed_before").toLong,floor.getOrElse(1L),ack,blockers.result())
  }
}
