package cn.xuyinyin.magic.cdc.platform

import cn.xuyinyin.magic.cdc.bootstrap.{BootstrapJdbc,BootstrapJournal,BootstrapProgress}
import cn.xuyinyin.magic.connectors.mysql.source.capture.{CaptureFenceBinding,CaptureFenceReceipt,CapturePinnedProof,CapturePinnedWitness}
import cn.xuyinyin.magic.connectors.mysql.source.capture.CaptureControlFence.VerifiedSourceReceipt
import cn.xuyinyin.magic.connectors.mysql.source.capture.CaptureControlFence.VerifiedSourceRelease
import cn.xuyinyin.magic.connectors.mysql.source.capture.CaptureControlFence.VerifiedSourceFence
import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import cn.xuyinyin.magic.cdc.recovery.{CdcExecutionException, ExecutionFailure}
import cn.xuyinyin.magic.cdc.guardian.SnapshotGuardianWire
import cn.xuyinyin.magic.cdc.guardian.SnapshotGuardianWire._
import java.sql.{Connection,SQLException}
import java.util.UUID
import spray.json._

/** Immutable association recorded only when an owner acquires the platform. */
final case class CdcManagedOwner(bootId:String,deploymentId:String,hostId:String,guardianBoot:String,
  guardianPublicKey:String,pid:Long,startIdentity:String,profileHash:String)
private[cdc] final case class CdcBRecoveryPermit(id:String,oldAttempt:String,taskId:String,captureId:String,offset:String,
  markerPresent:Boolean,table:String,generation:String,operation:String,proof:String,claimedBy:Option[String],consumed:Boolean)
private[cdc] final case class CdcFenceScan(attempt:String,taskId:String,captureId:String,offset:String,markerPresent:Boolean,
  binding:CaptureFenceBinding,previousCursor:String,cursor:String,done:Boolean)
object CdcTaskStore {
  /** One Reader instance, one owner connection, one durable startup attempt. */
  final class ReaderRecoveryGate private[CdcTaskStore](val captureId:String,val releaseEpoch:Long,
    consume0:(Connection,String,String,String)=>Unit,check0:()=>Unit) {
    def consume(owner:Connection,capture:String,oldD:String,task:String):Unit=consume0(owner,capture,oldD,task)
    def requireLive():Unit=check0()
  }
  /** The marker writer is authorized only while its state transaction remains open. */
  final class MarkerWritePermit private[CdcTaskStore] (val permitId:String,expected:CaptureFenceBinding,markerPresent:Boolean,
    active:java.util.concurrent.atomic.AtomicBoolean,check:()=>Unit) {
    def requireLive():Unit={require(active.get(),"marker authorization scope ended");check();require(active.get(),"marker authorization scope ended")}
    def assertBinding(binding:CaptureFenceBinding,present:Boolean):Unit={requireLive();require(binding==expected && present==markerPresent,"marker authorization changed")}
  }
  /** Capability is valid only inside the state transaction which constructed it. */
  final class ConsumedPermit private[CdcTaskStore] (val permitId:String,val receiptDigest:String,
    expected:CaptureFenceReceipt,active:java.util.concurrent.atomic.AtomicBoolean,check:()=>Unit) {
    def requireLive():Unit={require(active.get(),"consumed permit scope ended");check();require(active.get(),"consumed permit scope ended")}
    def assertReceipt(receipt:CaptureFenceReceipt):Unit={requireLive();require(receipt==expected,"consumed permit receipt changed")}
  }
}

/** A new control index. It never edits the kernel Journal, original L, SDK D or target receipts. */
final class CdcTaskStore(open:()=>Connection,val limits:CdcPlatformLimits) {
  private val jdbc=new BootstrapJdbc(open,2)
  import jdbc._
  private val logger=org.slf4j.LoggerFactory.getLogger(getClass)
  def initialize():Unit=connection {c=>
    logger.info("CDC control JDBC endpoint={} database={}", c.getMetaData.getURL.replaceAll("(?i)(password=)[^&]*", "$1<redacted>"), Option(c.getCatalog).getOrElse(""))
    val mysql=c.getMetaData.getDatabaseProductName.toLowerCase(java.util.Locale.ROOT).contains("mysql")
    val suffix=if(mysql)" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin"else""
    Vector(
      "CREATE TABLE IF NOT EXISTS pekko_cdc_owner_v1(platform_id VARCHAR(32) PRIMARY KEY,owner_attempt VARCHAR(128),status VARCHAR(32) NOT NULL,updated_at BIGINT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_clean_owner_v1(owner_attempt VARCHAR(128) PRIMARY KEY,lineage VARCHAR(128) NOT NULL,clean BOOLEAN NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_managed_owner_v1(owner_attempt VARCHAR(128) PRIMARY KEY,boot_id VARCHAR(128) NOT NULL,deployment_id VARCHAR(255) NOT NULL,host_id VARCHAR(128) NOT NULL,guardian_boot VARCHAR(128) NOT NULL,guardian_key TEXT NOT NULL,process_id BIGINT NOT NULL,start_identity VARCHAR(128) NOT NULL,profile_hash VARCHAR(128) NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_managed_manifest_v1(owner_attempt VARCHAR(128) PRIMARY KEY,manifest_json LONGTEXT NOT NULL,created_at BIGINT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_b_recovery_v1(owner_attempt VARCHAR(128) PRIMARY KEY,permit_id VARCHAR(128) NOT NULL,task_id CHAR(64) NOT NULL,capture_id VARCHAR(64) NOT NULL,offset_json LONGTEXT NOT NULL,marker_present BOOLEAN NOT NULL,table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL,operation_id VARCHAR(128) NOT NULL,signed_proof LONGTEXT NOT NULL,created_at BIGINT NOT NULL,claimed_by VARCHAR(128),consumed BOOLEAN NOT NULL DEFAULT FALSE,proof_version INT NOT NULL DEFAULT 1)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_b_fence_v2(permit_id VARCHAR(128) PRIMARY KEY,source_uuid VARCHAR(64) NOT NULL,database_name VARCHAR(128) NOT NULL,epoch BIGINT NOT NULL,owner_token VARCHAR(128) NOT NULL,version BIGINT NOT NULL,operation_id VARCHAR(128) NOT NULL,action VARCHAR(16) NOT NULL,table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_b_fence_intent_v2(owner_attempt VARCHAR(128) PRIMARY KEY,task_id CHAR(64) NOT NULL,capture_id VARCHAR(64) NOT NULL,offset_json LONGTEXT NOT NULL,marker_present BOOLEAN NOT NULL,source_uuid VARCHAR(64) NOT NULL,database_name VARCHAR(128) NOT NULL,epoch BIGINT NOT NULL,owner_token VARCHAR(128) NOT NULL,version BIGINT NOT NULL,operation_id VARCHAR(128) NOT NULL,action VARCHAR(16) NOT NULL,table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL,guardian_operation VARCHAR(128) NOT NULL,signed_proof LONGTEXT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_b_fence_scan_v2(owner_attempt VARCHAR(128) PRIMARY KEY,task_id CHAR(64) NOT NULL,capture_id VARCHAR(64) NOT NULL,offset_json LONGTEXT NOT NULL,marker_present BOOLEAN NOT NULL,source_uuid VARCHAR(64) NOT NULL,database_name VARCHAR(128) NOT NULL,epoch BIGINT NOT NULL,owner_token VARCHAR(128) NOT NULL,version BIGINT NOT NULL,operation_id VARCHAR(128) NOT NULL,action VARCHAR(16) NOT NULL,table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL,previous_cursor VARCHAR(64) NOT NULL,scan_cursor VARCHAR(64) NOT NULL,done BOOLEAN NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_b_receipt_v2(permit_id VARCHAR(128) PRIMARY KEY,task_id CHAR(64) NOT NULL,owner_attempt VARCHAR(128) NOT NULL,binding_digest CHAR(64) NOT NULL,version_after BIGINT NOT NULL,marker_created BOOLEAN NOT NULL,controller_account VARCHAR(255) NOT NULL,manifest_digest CHAR(64) NOT NULL,receipt_digest CHAR(64) NOT NULL,attestation LONGTEXT NOT NULL,nonce VARCHAR(64) NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_b_proof_anchor_v2(permit_id VARCHAR(128) PRIMARY KEY,witness_json LONGTEXT NOT NULL,witness_digest CHAR(64) NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_b_release_v2(permit_id VARCHAR(128) PRIMARY KEY,task_id CHAR(64) NOT NULL,owner_attempt VARCHAR(128) NOT NULL,receipt_digest CHAR(64) NOT NULL,release_digest CHAR(64) NOT NULL,attestation LONGTEXT NOT NULL,nonce VARCHAR(64) NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_reader_start_v2(permit_id VARCHAR(128) PRIMARY KEY,task_id CHAR(64) NOT NULL,capture_id VARCHAR(64) NOT NULL,owner_attempt VARCHAR(128) NOT NULL,token_hash CHAR(64) NOT NULL,reader_connection BIGINT NOT NULL,state_uuid VARCHAR(64) NOT NULL,state_database VARCHAR(128) NOT NULL,old_offset_hash CHAR(64) NOT NULL,journal_sequence BIGINT NOT NULL,source_epoch BIGINT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_owner_freeze_v2(owner_attempt VARCHAR(128) PRIMARY KEY,permit_id VARCHAR(128) NOT NULL,task_id CHAR(64) NOT NULL,capture_id VARCHAR(64) NOT NULL,manifest_digest CHAR(64) NOT NULL,isolation_operation VARCHAR(128) NOT NULL,isolation_proof LONGTEXT NOT NULL,evidence_digest CHAR(64) NOT NULL,created_at BIGINT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_owner_admission_v2(old_attempt VARCHAR(128) PRIMARY KEY,new_attempt VARCHAR(128) NOT NULL UNIQUE,permit_id VARCHAR(128) NOT NULL,freeze_digest CHAR(64) NOT NULL,manifest_digest CHAR(64) NOT NULL,attestation LONGTEXT NOT NULL,nonce VARCHAR(64) NOT NULL,created_at BIGINT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_owner_delegation_v2(old_attempt VARCHAR(128) PRIMARY KEY,new_attempt VARCHAR(128) NOT NULL,permit_id VARCHAR(128) NOT NULL,freeze_digest CHAR(64) NOT NULL,receipt_digest CHAR(64) NOT NULL,journal_pid VARCHAR(255) NOT NULL,journal_sequence BIGINT NOT NULL,journal_revision BIGINT NOT NULL,execution_id VARCHAR(128) NOT NULL,attestation LONGTEXT NOT NULL,nonce VARCHAR(64) NOT NULL,created_at BIGINT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_b_budget_v1(task_id CHAR(64) PRIMARY KEY,attempts INT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_b_rejection_v1(permit_id VARCHAR(128) PRIMARY KEY,task_id CHAR(64) NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_tasks_v1(task_id CHAR(64) PRIMARY KEY,capture_id VARCHAR(64) NOT NULL,request_id VARCHAR(128) NOT NULL,table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL,target_table VARCHAR(128) NOT NULL,definition_hash CHAR(64) NOT NULL,desired_state VARCHAR(32) NOT NULL,control_revision BIGINT NOT NULL,phase VARCHAR(32) NOT NULL,journal_revision BIGINT NOT NULL,execution_id VARCHAR(128),capture_readiness VARCHAR(32) NOT NULL,initialization_evidence LONGTEXT,runtime_health VARCHAR(32) NOT NULL,release_status VARCHAR(32) NOT NULL,failure_code VARCHAR(64),observed_at BIGINT NOT NULL,last_verified_at BIGINT,due_at BIGINT NOT NULL,grant_id VARCHAR(64),grant_owner VARCHAR(128),continuation_json LONGTEXT,continuation_owner VARCHAR(128),cancel_evidence VARCHAR(32),work_pool VARCHAR(16) NOT NULL,schedulable BOOLEAN NOT NULL,delta_after BIGINT NOT NULL DEFAULT 0,wake_seq BIGINT NOT NULL DEFAULT 0,UNIQUE(capture_id,request_id),UNIQUE(capture_id,table_name,generation))",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_dispatch_v1(capture_id VARCHAR(64) PRIMARY KEY,after_seq BIGINT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_grants_v1(grant_id VARCHAR(64) PRIMARY KEY,task_id CHAR(64) NOT NULL,owner_attempt VARCHAR(128) NOT NULL,pool VARCHAR(16) NOT NULL,status VARCHAR(32) NOT NULL,created_at BIGINT NOT NULL,finished_at BIGINT)"
    ).foreach(sql=>update(c,sql+suffix))
    def hasProofVersion:Boolean={
      Vector("pekko_cdc_b_recovery_v1","PEKKO_CDC_B_RECOVERY_V1").exists {table=>
        val rs=c.getMetaData.getColumns(c.getCatalog,null,table,"%");var found=false
        try while(rs.next())if(rs.getString("TABLE_NAME").equalsIgnoreCase("pekko_cdc_b_recovery_v1") && rs.getString("COLUMN_NAME").equalsIgnoreCase("proof_version"))found=true
        finally rs.close()
        found
      }
    }
    if(!hasProofVersion)try update(c,"ALTER TABLE pekko_cdc_b_recovery_v1 ADD COLUMN proof_version INT NOT NULL DEFAULT 1")
    catch {case e:SQLException=>if(!hasProofVersion)throw e}
    def index(table:String,name:String,columns:String):Unit={
      val rs=c.getMetaData.getIndexInfo(c.getCatalog,null,table,false,false);var found=false
      try while(rs.next())if(Option(rs.getString("INDEX_NAME")).exists(_.equalsIgnoreCase(name)))found=true finally rs.close()
      if(!found)try update(c,s"CREATE INDEX $name ON $table($columns)")catch {case e:SQLException=>
        val check=c.getMetaData.getIndexInfo(c.getCatalog,null,table,false,false);var exists=false
        try while(check.next())if(Option(check.getString("INDEX_NAME")).exists(_.equalsIgnoreCase(name)))exists=true finally check.close()
        if(!exists)throw e
      }
    }
    index("pekko_cdc_tasks_v1","cdc_task_ready_v1","capture_id,work_pool,schedulable,due_at,task_id")
    index("pekko_cdc_grants_v1","cdc_grant_budget_v1","pool,status")
    index("pekko_cdc_grants_v1","cdc_grant_state_v1","status,grant_id")
    index("pekko_cdc_b_recovery_v1","cdc_b_recovery_active_v2","capture_id,task_id,claimed_by,consumed")
    index("pekko_cdc_b_recovery_v1","cdc_b_recovery_cancel_v1","task_id,consumed")
    index("pekko_cdc_b_recovery_v1","cdc_b_recovery_claim_v1","claimed_by,consumed")
    index("pekko_cdc_b_recovery_v1","cdc_b_recovery_permit_v2","permit_id")
    index("pekko_cdc_managed_owner_v1","cdc_managed_owner_boot_v1","boot_id")
    if(query(c,"SELECT platform_id FROM pekko_cdc_owner_v1 WHERE platform_id='default'").isEmpty){
      val retained=Vector("pekko_cdc_clean_owner_v1","pekko_cdc_tasks_v1","pekko_cdc_grants_v1","pekko_cdc_dispatch_v1","pekko_cdc_managed_owner_v1","pekko_cdc_managed_manifest_v1","pekko_cdc_b_recovery_v1","pekko_cdc_b_fence_v2","pekko_cdc_b_fence_intent_v2","pekko_cdc_b_fence_scan_v2","pekko_cdc_b_receipt_v2","pekko_cdc_b_proof_anchor_v2","pekko_cdc_b_release_v2","pekko_cdc_reader_start_v2","pekko_cdc_owner_freeze_v2","pekko_cdc_owner_admission_v2","pekko_cdc_owner_delegation_v2","pekko_cdc_b_budget_v1","pekko_cdc_b_rejection_v1").exists(t=>query(c,s"SELECT 1 AS n FROM $t LIMIT 1").nonEmpty)
      if(retained)throw new CdcPlatformException("CONTROL_OWNER_MISSING")
      try update(c,"INSERT INTO pekko_cdc_owner_v1 VALUES('default',NULL,'STOPPED',0)")catch {case _:SQLException=>
        require(query(c,"SELECT platform_id FROM pekko_cdc_owner_v1 WHERE platform_id='default'").nonEmpty,"CDC owner initialization failed")}
    }
  }
  private def validateId(id:String):Unit=if(id==null || !id.matches("[a-f0-9]{64}"))throw new CdcPlatformException("INVALID_TASK_ID")
  private def record(r:Map[String,String]):CdcTaskRecord={
    val request=CdcTaskRequest(r("request_id"),r("capture_id"),r("table_name"),r("generation"),r("target_table"))
    val phase=r("phase");val grant=Option(r("grant_id"))
    val view=CdcTaskView(r("task_id"),request.requestId,request.captureId,request.table,request.generation,request.targetTable,
      r("definition_hash"),r("desired_state"),r("control_revision").toLong,phase,r("journal_revision").toLong,
      Option(r("execution_id")),r("capture_readiness"),Option(r("initialization_evidence")),r("runtime_health"),r("release_status"),
      Option(r("failure_code")),r("observed_at").toLong,Option(r("last_verified_at")).map(_.toLong),
      !Set("PAUSED","CANCELED").contains(phase) && r("release_status")!="UNKNOWN" && !r.get("recovery_pending").exists(Set("1","TRUE","true")),Option(r.getOrElse("cancel_evidence",null)))
    CdcTaskRecord(view,request,grant,Option(r("grant_owner")),r("due_at").toLong,
      Option(r("continuation_json")).map(CdcProgressJson.decode),r("work_pool"),Set("1","TRUE","true")(r("schedulable")),r("wake_seq").toLong)
  }
  private def row(c:Connection,id:String,lock:Boolean=false):Option[CdcTaskRecord]=
    query(c,"SELECT t.*,EXISTS(SELECT 1 FROM pekko_cdc_b_recovery_v1 p WHERE p.task_id=t.task_id AND p.consumed=FALSE) AS recovery_pending FROM pekko_cdc_tasks_v1 t WHERE t.task_id=?"+(if(lock)" FOR UPDATE"else""),id).headOption.map(record)
  private def owner(c:Connection):Map[String,String]=query(c,"SELECT * FROM pekko_cdc_owner_v1 WHERE platform_id='default' FOR UPDATE").head
  private def managedOn(c:Connection,attempt:String):Option[CdcManagedOwner]=
    query(c,"SELECT * FROM pekko_cdc_managed_owner_v1 WHERE owner_attempt=?",attempt).headOption.map {r=>
      CdcManagedOwner(r("boot_id"),r("deployment_id"),r("host_id"),r("guardian_boot"),r("guardian_key"),r("process_id").toLong,r("start_identity"),r("profile_hash"))
    }
  def managedOwner(attempt:String):Option[CdcManagedOwner]=connection(c=>managedOn(c,attempt))
  def managedManifest(attempt:String):Option[String]=connection(c=>query(c,"SELECT manifest_json FROM pekko_cdc_managed_manifest_v1 WHERE owner_attempt=?",attempt).headOption.map(_("manifest_json")))
  private def permit(r:Map[String,String]):CdcBRecoveryPermit=CdcBRecoveryPermit(r("permit_id"),r("owner_attempt"),r("task_id"),r("capture_id"),r("offset_json"),
    Set("1","TRUE","true")(r("marker_present")),r("table_name"),r("generation"),r("operation_id"),r("signed_proof"),Option(r("claimed_by")),Set("1","TRUE","true")(r("consumed")))
  private def fenceBinding(r:Map[String,String]):CaptureFenceBinding=CaptureFenceBinding(r("source_uuid"),r("database_name"),r("epoch").toLong,r("owner_token"),r("version").toLong,
    r("operation_id"),r("action"),r("table_name"),r("generation"))
  private[cdc] def fenceBindingDigest(b:CaptureFenceBinding):String=MySQLCdcGroup.hash(MySQLCdcGroup.canonical(JsObject(
    "version"->JsNumber(1),"sourceUuid"->JsString(b.sourceUuid),"database"->JsString(b.database),"epoch"->JsNumber(b.epoch),
    "ownerTokenHash"->JsString(MySQLCdcGroup.hash(b.ownerToken)),"sourceVersion"->JsNumber(b.version),"operation"->JsString(b.operation),
    "action"->JsString(b.action),"table"->JsString(b.table),"generation"->JsString(b.generation))))
  /** Store receipt only while the source transaction and fresh guardian attestation are valid. */
  private[cdc] def recordFenceReceipt(id:String,task:String,source:VerifiedSourceReceipt,freshAttest:String=>VerifiedWorkerAttestation,
    capture:CdcCaptureFenceVerifier):String=transaction {c=>
    c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ)
    checkedFenceReceipt(c,id,task,source,freshAttest,capture,createIfMissing=true,expectedConsumed=false)
  }
  private def checkedFenceReceipt(c:Connection,id:String,task:String,source:VerifiedSourceReceipt,freshAttest:String=>VerifiedWorkerAttestation,
    capture:CdcCaptureFenceVerifier,createIfMissing:Boolean,expectedConsumed:Boolean):String={
    require(source!=null && freshAttest!=null && capture!=null,"source receipt, guardian and capture proof required")
    source.requireLive()
    def challenge():String={val bytes=new Array[Byte](32);new java.security.SecureRandom().nextBytes(bytes);java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)}
    val nonce=challenge();val att=freshAttest(nonce);require(att!=null && att.nonce==nonce,"guardian challenge mismatch")
    val receipt=source.receipt;val binding=receipt.binding;val context=att.context
    val current=owner(c);val attempt=current("owner_attempt")
    require(current("status")=="ACTIVE" && attempt!=null && context.attempt==attempt,"receipt owner changed")
    val managed=managedOn(c,attempt).getOrElse(throw new IllegalArgumentException("managed owner missing"))
    val manifest=query(c,"SELECT manifest_json FROM pekko_cdc_managed_manifest_v1 WHERE owner_attempt=?",attempt).headOption
      .map(_("manifest_json")).getOrElse(throw new IllegalArgumentException("managed manifest missing"))
    require(att.identity.bootId==managed.bootId && att.identity.deploymentId==managed.deploymentId && att.identity.pid==managed.pid &&
      att.identity.startIdentity==managed.startIdentity && att.profile==managed.profileHash && att.guardian.bootId==managed.guardianBoot &&
      att.guardian.deploymentId==managed.deploymentId && att.guardian.hostId==managed.hostId && att.guardian.publicKey==managed.guardianPublicKey,
      "guardian attestation identity changed")
    SnapshotGuardianWire.verifyWorkerAttestation(att.signedEnvelope,att.guardian,att.identity,att.profile,context,att.nonce)
    val bd=fenceBindingDigest(binding);val md=MySQLCdcGroup.hash(manifest)
    require(context.permitId==id && context.bindingDigest==bd && context.sourceUuid==binding.sourceUuid && context.database==binding.database &&
      source.sourceUuid==binding.sourceUuid && source.database==binding.database && context.endpoint==source.endpoint &&
      context.controllerAccount==source.account && context.manifestDigest==md,"guardian receipt context changed")
    val accounts=manifest.parseJson.asJsObject.fields("accounts").asInstanceOf[JsArray].elements
    val separator=source.account.lastIndexOf('@');require(separator>0 && separator<source.account.length-1,"invalid controller account")
    val user=source.account.substring(0,separator)
    require(accounts.exists {a=>val f=a.asJsObject.fields;f.get("endpoint").contains(JsString(context.endpoint)) &&
      f.get("serverUuid").contains(JsString(binding.sourceUuid)) && f.get("user").contains(JsString(user))},"controller is absent from managed manifest")
    val rows=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE permit_id=? AND task_id=? FOR UPDATE",id,task)
    require(rows.size==1 && rows.head("proof_version")=="2","versioned recovery permit missing")
    val p=permit(rows.head)
    require(p.claimedBy.contains(attempt) && p.consumed==expectedConsumed && row(c,task,true).exists(t=>t.grantOwner.contains(attempt) && t.grantId.nonEmpty && t.view.desiredState=="RUNNING"),"recovery permit is not held")
    val stored=query(c,"SELECT * FROM pekko_cdc_b_fence_v2 WHERE permit_id=? FOR UPDATE",id)
    require(stored.size==1 && fenceBinding(stored.head)==binding && binding.table==p.table && binding.generation==p.generation &&
      binding.operation==MySQLCdcGroup.hash(p.captureId+"|B|"+p.taskId),"source receipt binding changed")
    require(receipt.markerCreated != p.markerPresent && receipt.versionAfter==(if(receipt.markerCreated)Math.addExact(binding.version,1L)else binding.version),"source receipt version or prior marker conflicts")
    val scan=query(c,"SELECT * FROM pekko_cdc_b_fence_scan_v2 WHERE owner_attempt=? FOR UPDATE",p.oldAttempt).headOption.map(scanRecord)
    require(scan.exists(s=>s.done && s.taskId==task && s.captureId==p.captureId && s.offset==p.offset && s.markerPresent==p.markerPresent && s.binding==binding),"source scan proof missing")
    val witness=capture.verifyOn(c,p,binding)
    val anchors=query(c,"SELECT witness_json,witness_digest FROM pekko_cdc_b_proof_anchor_v2 WHERE permit_id=? FOR UPDATE",id)
    if(anchors.nonEmpty){
      require(anchors.size==1 && anchors.head("witness_json")==witness.json && anchors.head("witness_digest")==MySQLCdcGroup.hash(witness.json),"pinned proof anchor changed")
      CapturePinnedProof.verifyExactOn(c,CapturePinnedWitness.parse(anchors.head("witness_json")))
    }else {
      require(createIfMissing,"pinned proof anchor missing")
      update(c,"INSERT INTO pekko_cdc_b_proof_anchor_v2 VALUES(?,?,?)",id,witness.json,MySQLCdcGroup.hash(witness.json))
    }
    val rd=MySQLCdcGroup.hash(MySQLCdcGroup.canonical(JsObject("version"->JsNumber(1),"permit"->JsString(id),"task"->JsString(task),
      "bindingDigest"->JsString(bd),"versionAfter"->JsNumber(receipt.versionAfter),"markerCreated"->JsBoolean(receipt.markerCreated),
      "ownerAttempt"->JsString(attempt),"ownerIdentity"->att.identity.toJson,"profile"->JsString(att.profile),
      "manifestDigest"->JsString(md),"endpoint"->JsString(source.endpoint),"controller"->JsString(source.account),"guardian"->att.guardian.toJson)))
    val prior=query(c,"SELECT * FROM pekko_cdc_b_receipt_v2 WHERE permit_id=? FOR UPDATE",id)
    if(prior.nonEmpty)require(prior.size==1 && prior.head("receipt_digest")==rd && prior.head("task_id")==task && prior.head("owner_attempt")==attempt,"receipt changed")
    else {
      require(createIfMissing,"durable recovery receipt missing")
      update(c,"INSERT INTO pekko_cdc_b_receipt_v2 VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,task,attempt,bd,receipt.versionAfter,receipt.markerCreated,
        source.account,md,rd,att.signedEnvelope,att.nonce)
    }
    source.requireLive()
    val lastNonce=challenge();val last=freshAttest(lastNonce)
    require(last!=null && lastNonce!=nonce && last.nonce==lastNonce && last.identity==att.identity && last.profile==att.profile &&
      last.guardian==att.guardian && last.context==context,"guardian changed during receipt transaction")
    SnapshotGuardianWire.verifyWorkerAttestation(last.signedEnvelope,last.guardian,last.identity,last.profile,last.context,lastNonce)
    source.requireLive()
    rd
  }
  /** Advance only while source and same-schema Journal evidence are locked and revalidated. */
  private[cdc] def consumeFencePermit(id:String,task:String,digest:String,evidence:BootstrapJournal.PersistedRecoveryReceipt,
    source:VerifiedSourceReceipt,freshAttest:String=>VerifiedWorkerAttestation,capture:CdcCaptureFenceVerifier,journal:CdcJournalFenceVerifier):Boolean=transaction {c=>
    require(evidence!=null && journal!=null && digest!=null && digest.matches("[a-f0-9]{64}"),"durable Journal evidence required")
    c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ)
    val checked=checkedFenceReceipt(c,id,task,source,freshAttest,capture,createIfMissing=false,expectedConsumed=false)
    require(checked==digest && evidence.permitId==id && evidence.receiptDigest==digest && evidence.phase=="RECOVERY_PENDING","Journal receipt binding changed")
    val p=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE permit_id=? AND task_id=? FOR UPDATE",id,task)
    require(p.size==1,"versioned recovery permit missing")
    val record=permit(p.head)
    require(evidence.persistenceId=="bootstrap-v1-"+record.captureId+"-"+task && evidence.executionId.nonEmpty,"Journal execution identity changed")
    journal.verifyOn(c,evidence)
    val taskRecord=row(c,task,true).getOrElse(throw new IllegalArgumentException("recovery task missing"))
    require(taskRecord.view.executionId.forall(_==evidence.executionId),"Journal execution identity changed")
    if(taskRecord.view.executionId.isEmpty)
      require(update(c,"UPDATE pekko_cdc_tasks_v1 SET execution_id=? WHERE task_id=? AND execution_id IS NULL",evidence.executionId,task)==1,"Journal execution binding changed")
    source.requireLive()
    require(update(c,"UPDATE pekko_cdc_b_recovery_v1 SET consumed=TRUE WHERE permit_id=? AND task_id=? AND consumed=FALSE",id,task)==1,"permit consumption changed")
    source.requireLive()
    true
  }
  /** Readback only; QUEUED additionally requires a verified source-release record. */
  private[cdc] def consumedFenceReceipt(id:String,task:String,digest:String,attempt:String):Boolean=connection {c=>
    val current=owner(c)
    current("status")=="ACTIVE" && current("owner_attempt")==attempt && managedOn(c,attempt).nonEmpty &&
      query(c,"SELECT p.consumed,p.proof_version,r.receipt_digest,r.owner_attempt FROM pekko_cdc_b_recovery_v1 p JOIN pekko_cdc_b_receipt_v2 r ON r.permit_id=p.permit_id WHERE p.permit_id=? AND p.task_id=?",id,task)
        .exists(r=>r("proof_version")=="2" && Set("1","TRUE","true")(r("consumed")) && r("receipt_digest")==digest && r("owner_attempt")==attempt)
  }
  /** Hint for re-entry after release; it does not authorize Journal activation. */
  private[cdc] def fenceReleaseRecorded(id:String,task:String,digest:String,attempt:String):Boolean=transaction {c=>
    val current=owner(c)
    current("status")=="ACTIVE" && current("owner_attempt")==attempt && managedOn(c,attempt).nonEmpty &&
      query(c,"SELECT p.consumed,p.proof_version,p.claimed_by,r.owner_attempt,r.receipt_digest,x.owner_attempt AS release_owner,x.receipt_digest AS release_receipt FROM pekko_cdc_b_recovery_v1 p JOIN pekko_cdc_b_receipt_v2 r ON r.permit_id=p.permit_id JOIN pekko_cdc_b_release_v2 x ON x.permit_id=p.permit_id WHERE p.permit_id=? AND p.task_id=? FOR UPDATE",id,task)
        .exists(r=>r("proof_version")=="2" && Set("1","TRUE","true")(r("consumed")) && r("claimed_by")==attempt &&
          r("owner_attempt")==attempt && r("receipt_digest")==digest && r("release_owner")==attempt && r("release_receipt")==digest)
  }
  /** Hold state and Journal locks while the controller performs its source release. */
  private[cdc] def withConsumedFencePermit[A](id:String,task:String,digest:String,evidence:BootstrapJournal.PersistedRecoveryReceipt,
    source:VerifiedSourceReceipt,freshAttest:String=>VerifiedWorkerAttestation,capture:CdcCaptureFenceVerifier,journal:CdcJournalFenceVerifier)
    (use:CdcTaskStore.ConsumedPermit=>A):A=transaction {c=>
    require(evidence!=null && journal!=null && use!=null,"consumed permit proof required")
    c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ)
    val actual=checkedFenceReceipt(c,id,task,source,freshAttest,capture,createIfMissing=false,expectedConsumed=true)
    require(actual==digest && evidence.permitId==id && evidence.receiptDigest==digest && evidence.phase=="RECOVERY_PENDING","consumed Journal binding changed")
    val p=permit(query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE permit_id=? AND task_id=? FOR UPDATE",id,task).head)
    require(evidence.persistenceId=="bootstrap-v1-"+p.captureId+"-"+task && row(c,task,true).exists(_.view.executionId.contains(evidence.executionId)),"consumed Journal execution changed")
    journal.verifyOn(c,evidence);source.requireLive()
    val current=owner(c);val attempt=current("owner_attempt");val managed=managedOn(c,attempt).get
    val manifest=query(c,"SELECT manifest_json FROM pekko_cdc_managed_manifest_v1 WHERE owner_attempt=?",attempt).head("manifest_json")
    val expected=source.receipt;val binding=expected.binding;val bd=fenceBindingDigest(binding);val md=MySQLCdcGroup.hash(manifest)
    val active=new java.util.concurrent.atomic.AtomicBoolean(true)
    def check():Unit={
      val bytes=new Array[Byte](32);new java.security.SecureRandom().nextBytes(bytes)
      val nonce=java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(bytes);val a=freshAttest(nonce)
      require(a!=null && a.nonce==nonce && a.identity.bootId==managed.bootId && a.identity.deploymentId==managed.deploymentId &&
        a.identity.pid==managed.pid && a.identity.startIdentity==managed.startIdentity && a.profile==managed.profileHash &&
        a.guardian.bootId==managed.guardianBoot && a.guardian.deploymentId==managed.deploymentId && a.guardian.hostId==managed.hostId &&
        a.guardian.publicKey==managed.guardianPublicKey,"release guardian identity changed")
      val x=a.context
      require(x.attempt==attempt && x.manifestDigest==md && x.sourceUuid==binding.sourceUuid && x.database==binding.database &&
        x.endpoint==source.endpoint && x.controllerAccount==source.account && x.permitId==id && x.bindingDigest==bd,"release guardian context changed")
      SnapshotGuardianWire.verifyWorkerAttestation(a.signedEnvelope,a.guardian,a.identity,a.profile,x,nonce)
      val currentOwner=owner(c)
      require(currentOwner("status")=="ACTIVE" && currentOwner("owner_attempt")==attempt &&
        row(c,task,true).exists(t=>t.view.desiredState=="RUNNING" && t.grantOwner.contains(attempt) && t.grantId.nonEmpty),"release owner changed")
      val status=query(c,"SELECT p.consumed,r.receipt_digest,r.owner_attempt FROM pekko_cdc_b_recovery_v1 p JOIN pekko_cdc_b_receipt_v2 r ON r.permit_id=p.permit_id WHERE p.permit_id=? AND p.task_id=? FOR UPDATE",id,task)
      require(status.size==1 && Set("1","TRUE","true")(status.head("consumed")) && status.head("receipt_digest")==digest && status.head("owner_attempt")==attempt,"consumed permit changed")
      capture.verifyOn(c,p,binding);journal.verifyOn(c,evidence)
    }
    val proof=new CdcTaskStore.ConsumedPermit(id,digest,expected,active,()=>check())
    try {proof.requireLive();use(proof)} finally active.set(false)
  }
  /** Authorize a marker write only while its committed source pre-state and state proof are locked. */
  private[cdc] def withFenceMarkerWriteAuthorization[A](id:String,task:String,source:VerifiedSourceFence,
    freshAttest:String=>VerifiedWorkerAttestation,capture:CdcCaptureFenceVerifier)(use:CdcTaskStore.MarkerWritePermit=>A):A=transaction {c=>
    require(source!=null && freshAttest!=null && capture!=null && use!=null,"marker authorization proof required")
    c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);source.requireLive()
    val current=owner(c);val attempt=current("owner_attempt")
    require(current("status")=="ACTIVE" && attempt!=null,"marker owner changed")
    val managed=managedOn(c,attempt).getOrElse(throw new IllegalArgumentException("managed owner missing"))
    val manifest=query(c,"SELECT manifest_json FROM pekko_cdc_managed_manifest_v1 WHERE owner_attempt=?",attempt).headOption
      .map(_("manifest_json")).getOrElse(throw new IllegalArgumentException("managed manifest missing"))
    val records=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE permit_id=? AND task_id=? FOR UPDATE",id,task)
    require(records.size==1 && records.head("proof_version")=="2","versioned recovery permit missing")
    val p=permit(records.head);val binding=source.binding
    require(!p.consumed && p.claimedBy.contains(attempt) && p.markerPresent==source.markerPresent &&
      source.sourceUuid==binding.sourceUuid && source.database==binding.database,"marker source pre-state changed")
    val f=query(c,"SELECT * FROM pekko_cdc_b_fence_v2 WHERE permit_id=? FOR UPDATE",id)
    require(f.size==1 && fenceBinding(f.head)==binding && binding.table==p.table && binding.generation==p.generation &&
      binding.operation==MySQLCdcGroup.hash(p.captureId+"|B|"+task),"marker binding changed")
    val scan=query(c,"SELECT * FROM pekko_cdc_b_fence_scan_v2 WHERE owner_attempt=? FOR UPDATE",p.oldAttempt).headOption.map(scanRecord)
    require(scan.exists(s=>s.done && s.taskId==task && s.captureId==p.captureId && s.offset==p.offset &&
      s.markerPresent==p.markerPresent && s.binding==binding),"source scan proof missing")
    val bd=fenceBindingDigest(binding);val md=MySQLCdcGroup.hash(manifest)
    val sep=source.account.lastIndexOf('@');require(sep>0 && sep<source.account.length-1,"invalid controller account")
    val user=source.account.substring(0,sep);val accounts=manifest.parseJson.asJsObject.fields("accounts").asInstanceOf[JsArray].elements
    require(accounts.exists {entry=>val fields=entry.asJsObject.fields;fields.get("endpoint").contains(JsString(source.endpoint)) &&
      fields.get("serverUuid").contains(JsString(binding.sourceUuid)) && fields.get("user").contains(JsString(user))},"controller absent from manifest")
    val active=new java.util.concurrent.atomic.AtomicBoolean(true)
    def check():Unit={
      val bytes=new Array[Byte](32);new java.security.SecureRandom().nextBytes(bytes)
      val nonce=java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(bytes);val a=freshAttest(nonce)
      require(a!=null && a.nonce==nonce && a.identity.bootId==managed.bootId && a.identity.deploymentId==managed.deploymentId &&
        a.identity.pid==managed.pid && a.identity.startIdentity==managed.startIdentity && a.profile==managed.profileHash &&
        a.guardian.bootId==managed.guardianBoot && a.guardian.deploymentId==managed.deploymentId && a.guardian.hostId==managed.hostId &&
        a.guardian.publicKey==managed.guardianPublicKey,"marker guardian identity changed")
      val x=a.context
      require(x.attempt==attempt && x.manifestDigest==md && x.sourceUuid==binding.sourceUuid && x.database==binding.database &&
        x.endpoint==source.endpoint && x.controllerAccount==source.account && x.permitId==id && x.bindingDigest==bd,"marker guardian context changed")
      SnapshotGuardianWire.verifyWorkerAttestation(a.signedEnvelope,a.guardian,a.identity,a.profile,x,nonce)
      val o=owner(c)
      require(o("status")=="ACTIVE" && o("owner_attempt")==attempt && row(c,task,true).exists(t=>
        t.view.desiredState=="RUNNING" && t.grantOwner.contains(attempt) && t.grantId.nonEmpty),"marker owner or grant changed")
      val checkPermit=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE permit_id=? AND task_id=? FOR UPDATE",id,task).map(permit)
      require(checkPermit.size==1 && !checkPermit.head.consumed && checkPermit.head.claimedBy.contains(attempt),"marker permit changed")
      capture.verifyOn(c,p,binding)
    }
    val proof=new CdcTaskStore.MarkerWritePermit(id,binding,p.markerPresent,active,()=>check())
    try {source.requireLive();proof.assertBinding(binding,p.markerPresent);use(proof)} finally active.set(false)
  }
  /** Persist source release confirmation while its immutable source record and Journal are locked. */
  private[cdc] def recordFenceReleased(id:String,task:String,digest:String,evidence:BootstrapJournal.PersistedRecoveryReceipt,
    source:VerifiedSourceRelease,freshAttest:String=>VerifiedWorkerAttestation,capture:CdcCaptureFenceVerifier,journal:CdcJournalFenceVerifier):String=transaction {c=>
    require(source!=null && freshAttest!=null && capture!=null && journal!=null && evidence!=null && digest!=null && digest.matches("[a-f0-9]{64}"),"release proof required")
    c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);source.requireLive()
    val current=owner(c);val attempt=current("owner_attempt")
    require(current("status")=="ACTIVE" && attempt!=null && managedOn(c,attempt).nonEmpty,"release owner changed")
    val managed=managedOn(c,attempt).get
    val manifest=query(c,"SELECT manifest_json FROM pekko_cdc_managed_manifest_v1 WHERE owner_attempt=?",attempt).headOption
      .map(_("manifest_json")).getOrElse(throw new IllegalArgumentException("managed manifest missing"))
    val pRows=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE permit_id=? AND task_id=? FOR UPDATE",id,task)
    require(pRows.size==1 && pRows.head("proof_version")=="2","versioned recovery permit missing")
    val p=permit(pRows.head);val binding=source.receipt.binding;val bd=fenceBindingDigest(binding);val md=MySQLCdcGroup.hash(manifest)
    require(p.consumed && p.claimedBy.contains(attempt) && source.permitId==id && source.stateDigest==digest &&
      source.sourceUuid==binding.sourceUuid && source.database==binding.database,"release source identity changed")
    require(row(c,task,true).exists(t=>t.grantOwner.contains(attempt) && t.grantId.nonEmpty && t.view.desiredState=="RUNNING" &&
      t.view.executionId.contains(evidence.executionId)),"release grant or execution changed")
    val fences=query(c,"SELECT * FROM pekko_cdc_b_fence_v2 WHERE permit_id=? FOR UPDATE",id)
    val receipt=query(c,"SELECT * FROM pekko_cdc_b_receipt_v2 WHERE permit_id=? FOR UPDATE",id)
    require(fences.size==1 && fenceBinding(fences.head)==binding && receipt.size==1 && receipt.head("receipt_digest")==digest &&
      receipt.head("owner_attempt")==attempt && receipt.head("task_id")==task && receipt.head("binding_digest")==bd &&
      receipt.head("controller_account")==source.account && receipt.head("manifest_digest")==md &&
      receipt.head("version_after").toLong==source.receipt.versionAfter &&
      Set("1","TRUE","true")(receipt.head("marker_created"))==source.receipt.markerCreated,"release receipt changed")
    require(evidence.persistenceId=="bootstrap-v1-"+p.captureId+"-"+task && evidence.permitId==id && evidence.receiptDigest==digest &&
      evidence.phase=="RECOVERY_PENDING","release Journal evidence changed")
    capture.verifyOn(c,p,binding);journal.verifyOn(c,evidence)
    def attest():VerifiedWorkerAttestation={
      val bytes=new Array[Byte](32);new java.security.SecureRandom().nextBytes(bytes)
      val nonce=java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(bytes);val a=freshAttest(nonce)
      require(a!=null && a.nonce==nonce && a.identity.bootId==managed.bootId && a.identity.deploymentId==managed.deploymentId &&
        a.identity.pid==managed.pid && a.identity.startIdentity==managed.startIdentity && a.profile==managed.profileHash &&
        a.guardian.bootId==managed.guardianBoot && a.guardian.deploymentId==managed.deploymentId && a.guardian.hostId==managed.hostId &&
        a.guardian.publicKey==managed.guardianPublicKey,"release guardian identity changed")
      val x=a.context
      require(x.attempt==attempt && x.manifestDigest==md && x.sourceUuid==binding.sourceUuid && x.database==binding.database &&
        x.endpoint==source.endpoint && x.controllerAccount==source.account && x.permitId==id && x.bindingDigest==bd,"release guardian context changed")
      SnapshotGuardianWire.verifyWorkerAttestation(a.signedEnvelope,a.guardian,a.identity,a.profile,x,nonce);a
    }
    val a=attest();source.requireLive()
    val rd=MySQLCdcGroup.hash(MySQLCdcGroup.canonical(JsObject("version"->JsNumber(1),"permit"->JsString(id),"task"->JsString(task),
      "ownerAttempt"->JsString(attempt),"receiptDigest"->JsString(digest),"bindingDigest"->JsString(bd),
      "versionAfter"->JsNumber(source.receipt.versionAfter),"markerCreated"->JsBoolean(source.receipt.markerCreated),
      "endpoint"->JsString(source.endpoint),"controller"->JsString(source.account),"guardian"->a.guardian.toJson)))
    val prior=query(c,"SELECT * FROM pekko_cdc_b_release_v2 WHERE permit_id=? FOR UPDATE",id)
    if(prior.nonEmpty)require(prior.size==1 && prior.head("owner_attempt")==attempt && prior.head("task_id")==task &&
      prior.head("receipt_digest")==digest && prior.head("release_digest")==rd,"release confirmation changed")
    else update(c,"INSERT INTO pekko_cdc_b_release_v2 VALUES(?,?,?,?,?,?,?)",id,task,attempt,digest,rd,a.signedEnvelope,a.nonce)
    val last=attest();require(last.nonce!=a.nonce && last.identity==a.identity && last.guardian==a.guardian && last.context==a.context,"release guardian changed")
    source.requireLive();rd
  }
  /** Build a single-use Reader gate from the claimed permit, never from a Boolean callback. */
  private[cdc] def readerRecoveryGate(id:String,task:String,captureId:String,digest:String,evidence:BootstrapJournal.PersistedRecoveryReceipt,
    freshAttest:String=>VerifiedWorkerAttestation,capture:CdcCaptureFenceVerifier,journal:CdcJournalFenceVerifier,
    withRelease:(VerifiedSourceRelease=>Unit)=>Unit):CdcTaskStore.ReaderRecoveryGate={
    require(evidence!=null && freshAttest!=null && capture!=null && journal!=null && withRelease!=null,"reader startup proof required")
    val claimed=claimedFencePermit(captureId,task).filter(_._1.id==id).getOrElse(throw new IllegalArgumentException("claimed reader permit missing"))
    val (p,b,manifestDigest)=claimed
    require(p.consumed && p.claimedBy.nonEmpty && evidence.permitId==id && evidence.receiptDigest==digest &&
      evidence.phase=="RECOVERY_PENDING","reader startup permit is not consumed")
    val attempt=p.claimedBy.get
    val bytes=new Array[Byte](32);new java.security.SecureRandom().nextBytes(bytes)
    val tokenHash=MySQLCdcGroup.hash(bytes.map(x=>f"${x & 0xff}%02x").mkString)
    var ownerConnection:Connection=null
    var ownerId:Long=0L
    var sourceAccount:String=null
    var sourceEndpoint:String=null
    def identity(connection:Connection):(String,String,Long,Long)={
      require(connection!=null && !connection.isClosed,"reader owner connection unavailable")
      val q=connection.prepareStatement("SELECT @@server_uuid,DATABASE(),CONNECTION_ID(),IS_USED_LOCK(?)")
      q.setQueryTimeout(5);q.setString(1,"pekko_capture_"+p.captureId)
      try {val r=q.executeQuery();try {require(r.next());(r.getString(1),r.getString(2),r.getLong(3),r.getLong(4))}finally r.close()}finally q.close()
    }
    def challenge(c:Connection,source:Option[VerifiedSourceRelease]):Unit={
      val current=owner(c);require(current("status")=="ACTIVE" && current("owner_attempt")==attempt,"reader startup owner changed")
      val managed=managedOn(c,attempt).getOrElse(throw new IllegalArgumentException("managed reader owner missing"))
      val manifest=query(c,"SELECT manifest_json FROM pekko_cdc_managed_manifest_v1 WHERE owner_attempt=?",attempt).headOption
        .map(_("manifest_json")).getOrElse(throw new IllegalArgumentException("reader manifest missing"))
      require(MySQLCdcGroup.hash(manifest)==manifestDigest,"reader manifest changed")
      val nonceBytes=new Array[Byte](32);new java.security.SecureRandom().nextBytes(nonceBytes)
      val nonce=java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(nonceBytes);val a=freshAttest(nonce)
      require(a!=null && a.nonce==nonce && a.identity.bootId==managed.bootId && a.identity.deploymentId==managed.deploymentId &&
        a.identity.pid==managed.pid && a.identity.startIdentity==managed.startIdentity && a.profile==managed.profileHash &&
        a.guardian.bootId==managed.guardianBoot && a.guardian.deploymentId==managed.deploymentId &&
        a.guardian.hostId==managed.hostId && a.guardian.publicKey==managed.guardianPublicKey,"reader guardian changed")
      val x=a.context
      require(x.attempt==attempt && x.manifestDigest==manifestDigest && x.sourceUuid==b.sourceUuid && x.database==b.database &&
        x.permitId==id && x.bindingDigest==fenceBindingDigest(b) && x.controllerAccount==sourceAccount && x.endpoint==sourceEndpoint,
        "reader guardian context changed")
      SnapshotGuardianWire.verifyWorkerAttestation(a.signedEnvelope,a.guardian,a.identity,a.profile,x,nonce)
      source.foreach(_.requireLive())
    }
    def checkState(c:Connection,connection:Connection,source:Option[VerifiedSourceRelease],insert:Boolean):Unit={
      require(!c.getAutoCommit && c.getTransactionIsolation==Connection.TRANSACTION_REPEATABLE_READ,"reader state transaction invalid")
      val i=identity(connection);require(i._1!=null && i._2!=null && i._3>0 && i._3==i._4,"reader capture lock is not held")
      val q=c.createStatement();q.setQueryTimeout(5)
      try {val r=q.executeQuery("SELECT @@server_uuid,DATABASE()");try {require(r.next() && r.getString(1)==i._1 && r.getString(2)==i._2,"reader owner database changed")}finally r.close()}finally q.close()
      val locked=owner(c);require(locked("status")=="ACTIVE" && locked("owner_attempt")==attempt,"reader owner changed")
      val permits=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE permit_id=? AND task_id=? FOR UPDATE",id,task)
      require(permits.size==1 && permits.head("proof_version")=="2","reader permit missing")
      val current=permit(permits.head)
      require(current.consumed && current.claimedBy.contains(attempt) && current.captureId==p.captureId && current.offset==p.offset &&
        row(c,task,true).exists(t=>t.grantOwner.contains(attempt) && t.grantId.nonEmpty && t.view.desiredState=="RUNNING" &&
          t.view.executionId.contains(evidence.executionId)),"reader recovery grant changed")
      val release=query(c,"SELECT * FROM pekko_cdc_b_release_v2 WHERE permit_id=? FOR UPDATE",id)
      require(release.size==1 && release.head("task_id")==task && release.head("owner_attempt")==attempt &&
        release.head("receipt_digest")==digest,"reader release confirmation missing")
      capture.verifyOn(c,current,b);journal.verifyOn(c,evidence);challenge(c,source)
      val values=Vector[Any](id,task,p.captureId,attempt,tokenHash,i._3,i._1,i._2,MySQLCdcGroup.hash(p.offset),evidence.sequenceNr,b.epoch)
      val previous=query(c,"SELECT * FROM pekko_cdc_reader_start_v2 WHERE permit_id=? FOR UPDATE",id)
      if(previous.isEmpty){require(insert,"reader startup token missing");update(c,"INSERT INTO pekko_cdc_reader_start_v2 VALUES(?,?,?,?,?,?,?,?,?,?,?)",values:_*)}
      else {val r=previous.head;require(previous.size==1 && r("task_id")==task && r("capture_id")==p.captureId && r("owner_attempt")==attempt &&
        r("token_hash")==tokenHash && r("reader_connection").toLong==i._3 && r("state_uuid")==i._1 && r("state_database")==i._2 &&
        r("old_offset_hash")==MySQLCdcGroup.hash(p.offset) && r("journal_sequence").toLong==evidence.sequenceNr && r("source_epoch").toLong==b.epoch,
        "reader startup token or connection changed")}
      require(identity(connection)==i,"reader capture lock changed")
      source.foreach(_.requireLive())
      ownerId=i._3
    }
    def tx(connection:Connection,source:Option[VerifiedSourceRelease],insert:Boolean):Unit=transaction {c=>
      c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);checkState(c,connection,source,insert)
    }
    new CdcTaskStore.ReaderRecoveryGate(p.captureId,b.epoch,(connection,captureId,offset,pending)=>synchronized {
      require(captureId==p.captureId && offset==p.offset && pending==task && (ownerConnection==null || (ownerConnection eq connection)),"reader startup identity changed")
      withRelease {proof=>
        require(proof.permitId==id && proof.stateDigest==digest && proof.receipt.binding==b,"source release proof changed")
        sourceAccount=proof.account;sourceEndpoint=proof.endpoint
        recordFenceReleased(id,task,digest,evidence,proof,freshAttest,capture,journal)
        try tx(connection,Some(proof),insert=true)
        catch {case e:CdcExecutionException if e.failure.code==ExecutionFailure.CommitUnknown && e.failure.releaseConfirmed=>
          tx(connection,Some(proof),insert=true)
        }
        ownerConnection=connection
      }
    },()=>synchronized {
      require(ownerConnection!=null && ownerId>0,"reader startup was not consumed")
      tx(ownerConnection,None,insert=false)
    })
  }
  /** Hold the real current D and immutable old pin while a new proof page is verified. */
  private[cdc] def withReadyProofState[A](id:String,task:String,digest:String,execution:String,capture:CdcCaptureFenceVerifier)
    (use:(Connection,CapturePinnedWitness,String)=>A):A=transaction {c=>
    require(capture!=null && use!=null,"capture proof required");c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ)
    val current=owner(c);val attempt=current("owner_attempt")
    val ready=current("status")=="ACTIVE" && attempt!=null && managedOn(c,attempt).nonEmpty &&
      row(c,task,true).exists(t=>t.view.desiredState=="RUNNING" && t.view.executionId.contains(execution) && t.grantOwner.contains(attempt) && t.grantId.nonEmpty) &&
      query(c,"SELECT p.consumed,p.proof_version,p.claimed_by,r.owner_attempt,r.receipt_digest,x.owner_attempt AS release_owner,x.receipt_digest AS release_receipt FROM pekko_cdc_b_recovery_v1 p JOIN pekko_cdc_b_receipt_v2 r ON r.permit_id=p.permit_id JOIN pekko_cdc_b_release_v2 x ON x.permit_id=p.permit_id WHERE p.permit_id=? AND p.task_id=?",id,task)
        .exists(r=>r("proof_version")=="2" && Set("1","TRUE","true")(r("consumed")) && r("claimed_by")==attempt &&
          r("owner_attempt")==attempt && r("receipt_digest")==digest && r("release_owner")==attempt && r("release_receipt")==digest)
    require(ready,"recovery release or owner not ready")
    val p=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE permit_id=? AND task_id=? FOR UPDATE",id,task).map(permit)
    val f=query(c,"SELECT * FROM pekko_cdc_b_fence_v2 WHERE permit_id=? FOR UPDATE",id)
    val anchors=query(c,"SELECT * FROM pekko_cdc_b_proof_anchor_v2 WHERE permit_id=? FOR UPDATE",id)
    require(p.size==1 && f.size==1 && anchors.size==1 && anchors.head("witness_digest")==MySQLCdcGroup.hash(anchors.head("witness_json")),"recovery proof missing")
    val old=CapturePinnedWitness.parse(anchors.head("witness_json"));val binding=fenceBinding(f.head)
    require(old.capture==p.head.captureId && old.identity.task==task && old.identity.guardianAttempt==p.head.oldAttempt &&
      old.identity.sourceFence==fenceBindingDigest(binding),"old recovery proof identity changed")
    CapturePinnedProof.verifyExactOn(c,old)
    val d=capture.currentDOn(c,p.head,binding)
    use(c,old,d)
  }
  /** Journal enters QUEUED only when current D covers both the original head and B. */
  private[cdc] def fenceRecoveryReady(id:String,task:String,digest:String,execution:String,capture:CdcCaptureFenceVerifier):Boolean={
    withReadyProofState(id,task,digest,execution,capture) { (c,old,d)=>
      CapturePinnedProof.advancedIfCompleteOn(c,old,d).exists {advanced=>
        advanced.mode=="COVERED" && advanced.identity.priorHead.contains(old.identity.head) && advanced.identity.priorDigest.contains(old.headDigest)
      }
    }
  }
  private def intentRow(c:Connection,attempt:String):Option[Map[String,String]]=
    query(c,"SELECT * FROM pekko_cdc_b_fence_intent_v2 WHERE owner_attempt=?",attempt).headOption
  private[cdc] def fenceIntent(attempt:String):Option[CaptureFenceBinding]=connection(c=>intentRow(c,attempt).map(fenceBinding))
  private def scanRecord(r:Map[String,String]):CdcFenceScan=CdcFenceScan(r("owner_attempt"),r("task_id"),r("capture_id"),r("offset_json"),
    Set("1","TRUE","true")(r("marker_present")),fenceBinding(r),r("previous_cursor"),r("scan_cursor"),Set("1","TRUE","true")(r("done")))
  private[cdc] def scanProgress(attempt:String):Option[CdcFenceScan]=connection(c=>
    query(c,"SELECT * FROM pekko_cdc_b_fence_scan_v2 WHERE owner_attempt=?",attempt).headOption.map(scanRecord))
  /** Single state-store transaction for binding a scan and its L/ACK pin. The caller owns the source fence lock. */
  private[cdc] def withFenceState[A](attempt:String,proof:CdcManagedIsolation.Verified,b:CdcManagedPendingB.Verified,
    binding:CaptureFenceBinding)(use:Connection=>A):A=transaction {c=>
    val current=owner(c)
    require(current("owner_attempt")==attempt && Set("ACTIVE","UNKNOWN")(current("status")) && managedOn(c,attempt).contains(proof.managed),"scan owner or guardian changed")
    require(query(c,"SELECT manifest_json FROM pekko_cdc_managed_manifest_v1 WHERE owner_attempt=?",attempt).headOption.exists(_("manifest_json")==proof.manifest),"scan manifest changed")
    val intent=query(c,"SELECT * FROM pekko_cdc_b_fence_intent_v2 WHERE owner_attempt=? FOR UPDATE",attempt).headOption
      .getOrElse(throw new IllegalArgumentException("source fence intent missing"))
    require(fenceBinding(intent)==binding && intent("task_id")==b.taskId && intent("capture_id")==b.captureId && intent("offset_json")==b.offset &&
      Set("1","TRUE","true")(intent("marker_present"))==b.markerPresent && intent("guardian_operation")==proof.operationId && intent("signed_proof")==proof.signedProof,"source scan intent changed")
    query(c,"SELECT * FROM pekko_cdc_b_fence_scan_v2 WHERE owner_attempt=? FOR UPDATE",attempt).headOption.map(scanRecord).foreach {s=>
      require(s.taskId==b.taskId && s.captureId==b.captureId && s.offset==b.offset && s.markerPresent==b.markerPresent && s.binding==binding,"source scan binding changed")
    }
    use(c)
  }
  /** Caller must hold and verify the source fence for the entire page read and commit. */
  private[cdc] def advanceFenceScan(attempt:String,proof:CdcManagedIsolation.Verified,b:CdcManagedPendingB.Verified,
    binding:CaptureFenceBinding,expectedCursor:String,nextCursor:String,done:Boolean):CdcFenceScan=
    withFenceState(attempt,proof,b,binding)(c=>advanceFenceScanOn(c,attempt,proof,b,binding,expectedCursor,nextCursor,done))
  /** The connection belongs to withFenceState; the caller must not commit or close it. */
  private[cdc] def advanceFenceScanOn(c:Connection,attempt:String,proof:CdcManagedIsolation.Verified,b:CdcManagedPendingB.Verified,
    binding:CaptureFenceBinding,expectedCursor:String,nextCursor:String,done:Boolean):CdcFenceScan={
    require(c!=null && !c.getAutoCommit,"source scan requires state transaction")
    require(expectedCursor!=null && nextCursor!=null && (expectedCursor.isEmpty || expectedCursor.matches("[a-f0-9]{64}")) &&
      (nextCursor.isEmpty || nextCursor.matches("[a-f0-9]{64}")),"invalid source scan cursor")
    require(nextCursor.compareTo(expectedCursor)>0 || (done && nextCursor==expectedCursor),"source scan must advance or finish")
    val current=owner(c)
    require(current("owner_attempt")==attempt && Set("ACTIVE","UNKNOWN")(current("status")) && managedOn(c,attempt).contains(proof.managed),"scan owner or guardian changed")
    require(query(c,"SELECT manifest_json FROM pekko_cdc_managed_manifest_v1 WHERE owner_attempt=?",attempt).headOption.exists(_("manifest_json")==proof.manifest),"scan manifest changed")
    val intent=intentRow(c,attempt).getOrElse(throw new IllegalArgumentException("source fence intent missing"))
    require(fenceBinding(intent)==binding && intent("task_id")==b.taskId && intent("capture_id")==b.captureId && intent("offset_json")==b.offset &&
      Set("1","TRUE","true")(intent("marker_present"))==b.markerPresent && intent("guardian_operation")==proof.operationId && intent("signed_proof")==proof.signedProof,"source scan intent changed")
    val existing=query(c,"SELECT * FROM pekko_cdc_b_fence_scan_v2 WHERE owner_attempt=? FOR UPDATE",attempt).headOption.map(scanRecord)
    existing.foreach(s=>require(s.taskId==b.taskId && s.captureId==b.captureId && s.offset==b.offset && s.markerPresent==b.markerPresent && s.binding==binding,"source scan binding changed"))
    existing match {
      case Some(s) if s.previousCursor==expectedCursor && s.cursor==nextCursor && s.done==done=>s
      case Some(s)=>
        require(!s.done && s.cursor==expectedCursor,"source scan cursor changed")
        update(c,"UPDATE pekko_cdc_b_fence_scan_v2 SET previous_cursor=?,scan_cursor=?,done=? WHERE owner_attempt=?",expectedCursor,nextCursor,done,attempt)
        scanRecord(query(c,"SELECT * FROM pekko_cdc_b_fence_scan_v2 WHERE owner_attempt=?",attempt).head)
      case None=>
        require(expectedCursor.isEmpty,"source scan must begin at the first page")
        update(c,"INSERT INTO pekko_cdc_b_fence_scan_v2 VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",attempt,b.taskId,b.captureId,b.offset,b.markerPresent,
          binding.sourceUuid,binding.database,binding.epoch,binding.ownerToken,binding.version,binding.operation,binding.action,binding.table,binding.generation,expectedCursor,nextCursor,done)
        scanRecord(query(c,"SELECT * FROM pekko_cdc_b_fence_scan_v2 WHERE owner_attempt=?",attempt).head)
    }
  }
  /** Immutable intent must commit before attempting the source-side CAS. */
  private[cdc] def recordFenceIntent(attempt:String,proof:CdcManagedIsolation.Verified,b:CdcManagedPendingB.Verified,f:CaptureFenceBinding):CaptureFenceBinding=transaction {c=>
    val current=owner(c)
    require(current("owner_attempt")==attempt && Set("ACTIVE","UNKNOWN")(current("status")),"old CDC owner changed")
    require(managedOn(c,attempt).contains(proof.managed),"guardian proof is not bound to CDC owner")
    require(query(c,"SELECT manifest_json FROM pekko_cdc_managed_manifest_v1 WHERE owner_attempt=?",attempt).headOption.exists(_("manifest_json")==proof.manifest),"resource manifest changed")
    require(f.sourceUuid==b.sourceUuid && f.database==b.sourceDatabase && f.action=="SUBSCRIBE" && f.table==b.table && f.generation==b.generation &&
      f.operation==MySQLCdcGroup.hash(b.captureId+"|B|"+b.taskId) && f.epoch>0 && f.version>=0 && f.ownerToken!=null && f.ownerToken.matches("[a-f0-9]{64}"),"source fence intent does not match pending B")
    val task=row(c,b.taskId,true).getOrElse(throw new IllegalStateException("pending B task missing"))
    require(task.view.captureId==b.captureId && task.view.table==b.table && task.view.generation==b.generation && task.view.desiredState=="RUNNING" &&
      task.grantOwner.contains(attempt) && task.grantId.nonEmpty,"pending B task or grant changed")
    val grants=query(c,"SELECT * FROM pekko_cdc_grants_v1 WHERE grant_id=? FOR UPDATE",task.grantId.get)
    require(grants.size==1 && grants.head("owner_attempt")==attempt && grants.head("task_id")==b.taskId && grants.head("pool")=="SNAPSHOT" &&
      Set("ACTIVE","UNKNOWN")(grants.head("status")),"pending B grant changed")
    intentRow(c,attempt) match {
      case Some(r)=>
        require(fenceBinding(r)==f && r("task_id")==b.taskId && r("capture_id")==b.captureId && r("offset_json")==b.offset &&
          Set("1","TRUE","true")(r("marker_present"))==b.markerPresent && r("guardian_operation")==proof.operationId && r("signed_proof")==proof.signedProof,"source fence intent changed")
      case None=>update(c,"INSERT INTO pekko_cdc_b_fence_intent_v2 VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",attempt,b.taskId,b.captureId,b.offset,b.markerPresent,
        f.sourceUuid,f.database,f.epoch,f.ownerToken,f.version,f.operation,f.action,f.table,f.generation,proof.operationId,proof.signedProof)
    }
    f
  }
  /** V2 is not usable until source fencing can be checked through Journal and consumption. */
  private def requireLegacyPermit(c:Connection,id:String):Unit={
    val versions=query(c,"SELECT proof_version FROM pekko_cdc_b_recovery_v1 WHERE permit_id=? LIMIT 2",id)
    val sidecar=query(c,"SELECT permit_id FROM pekko_cdc_b_fence_v2 WHERE permit_id=?",id).nonEmpty
    if(versions.size>1 || versions.exists(_("proof_version")!="1") || sidecar)
      throw new CdcPlatformException("SOURCE_FENCE_V2_UNAVAILABLE")
  }
  private[cdc] def pendingBPermit(capture:String,task:String):Option[CdcBRecoveryPermit]=connection {c=>
    val current=owner(c)
    if(current("status")!="ACTIVE")None else {
      val entries=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE capture_id=? AND task_id=? AND claimed_by=? AND consumed=FALSE LIMIT 2",capture,task,current("owner_attempt")).map(permit)
      require(entries.size<=1,"duplicate pending B permits");entries.foreach(p=>requireLegacyPermit(c,p.id));entries.headOption
    }
  }
  private[cdc] def permitForOldOwner(attempt:String):Option[CdcBRecoveryPermit]=connection(c=>
    query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE owner_attempt=?",attempt).headOption.map {r=>val p=permit(r);requireLegacyPermit(c,p.id);p})
  /** Read both records in one transaction; a missing or mismatched v2 sidecar is never legacy. */
  private[cdc] def versionedPermitForOldOwner(attempt:String):Option[(CdcBRecoveryPermit,CaptureFenceBinding)]=transaction {c=>
    query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE owner_attempt=? FOR UPDATE",attempt).headOption.map {r=>
      if(r("proof_version")!="2")throw new CdcPlatformException("SOURCE_FENCE_VERSION_MISMATCH")
      val p=permit(r)
      if(query(c,"SELECT owner_attempt FROM pekko_cdc_b_recovery_v1 WHERE permit_id=? LIMIT 2 FOR UPDATE",p.id).size!=1)
        throw new CdcPlatformException("SOURCE_FENCE_EVIDENCE_MISMATCH")
      val entries=query(c,"SELECT * FROM pekko_cdc_b_fence_v2 WHERE permit_id=? FOR UPDATE",p.id)
      if(entries.size!=1)throw new CdcPlatformException("SOURCE_FENCE_EVIDENCE_MISSING")
      val f=entries.head
      val binding=CaptureFenceBinding(f("source_uuid"),f("database_name"),f("epoch").toLong,f("owner_token"),f("version").toLong,
        f("operation_id"),f("action"),f("table_name"),f("generation"))
      if(binding.action!="SUBSCRIBE" || binding.table!=p.table || binding.generation!=p.generation ||
        binding.operation!=MySQLCdcGroup.hash(p.captureId+"|B|"+p.taskId))throw new CdcPlatformException("SOURCE_FENCE_EVIDENCE_MISMATCH")
      (p,binding)
    }
  }
  /** Read the current owner's v2 context; this never authorizes a marker or advances D. */
  private[cdc] def claimedFencePermit(capture:String,task:String):Option[(CdcBRecoveryPermit,CaptureFenceBinding,String)]=transaction {c=>
    val current=owner(c);val attempt=current("owner_attempt")
    if(current("status")!="ACTIVE" || attempt==null || managedOn(c,attempt).isEmpty)None
    else {
      // Include a consumed v2 permit: its owner may have crashed before source release or Journal activation.
      val records=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE capture_id=? AND task_id=? AND claimed_by=? AND proof_version=2 LIMIT 2 FOR UPDATE",capture,task,attempt)
      require(records.size<=1,"duplicate pending B permits")
      records.headOption.map {r=>
        val p=permit(r)
        require(row(c,task,true).exists(t=>t.grantOwner.contains(attempt) && t.grantId.nonEmpty && t.view.desiredState=="RUNNING"),"recovery grant missing")
        val fences=query(c,"SELECT * FROM pekko_cdc_b_fence_v2 WHERE permit_id=? FOR UPDATE",p.id)
        require(fences.size==1,"source fence evidence missing")
        val f=fenceBinding(fences.head)
        require(f.sourceUuid.nonEmpty && f.table==p.table && f.generation==p.generation && f.action=="SUBSCRIBE" &&
          f.operation==MySQLCdcGroup.hash(p.captureId+"|B|"+p.taskId),"source fence evidence mismatch")
        if(p.consumed)require(query(c,"SELECT 1 FROM pekko_cdc_b_receipt_v2 WHERE permit_id=? AND task_id=? AND owner_attempt=?",p.id,task,attempt).size==1,
          "consumed recovery receipt missing")
        val manifest=query(c,"SELECT manifest_json FROM pekko_cdc_managed_manifest_v1 WHERE owner_attempt=?",attempt).headOption
          .map(_("manifest_json")).getOrElse(throw new IllegalArgumentException("managed manifest missing"))
        (p,f,MySQLCdcGroup.hash(manifest))
      }
    }
  }
  /** A readback is only a hint for retry; authorization requires fresh source and guardian checks. */
  private[cdc] def receiptDigestForPermit(id:String,task:String):Option[String]=connection {c=>
    val current=owner(c);val attempt=current("owner_attempt")
    if(current("status")!="ACTIVE" || attempt==null || managedOn(c,attempt).isEmpty)None
    else {
      val p=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE permit_id=? AND task_id=? LIMIT 2",id,task)
      require(p.size<=1,"duplicate recovery permit")
      if(!p.headOption.exists(r=>r("proof_version")=="2" && Option(r("claimed_by")).contains(attempt)))None
      else query(c,"SELECT receipt_digest FROM pekko_cdc_b_receipt_v2 WHERE permit_id=? AND task_id=? AND owner_attempt=?",id,task,attempt).headOption.map(_("receipt_digest"))
    }
  }
  /** Original ACK anchor is immutable; absence or corruption never implies an empty proof. */
  private[cdc] def proofAnchorForPermit(id:String,task:String):Option[CapturePinnedWitness]=transaction {c=>
    val current=owner(c);val attempt=current("owner_attempt")
    if(current("status")!="ACTIVE" || attempt==null || managedOn(c,attempt).isEmpty)None
    else {
      val permits=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE permit_id=? AND task_id=? AND claimed_by=? FOR UPDATE",id,task,attempt)
      require(permits.size<=1,"duplicate recovery permit")
      permits.headOption.filter(_("proof_version")=="2").map {r=>
        val p=permit(r);val anchors=query(c,"SELECT * FROM pekko_cdc_b_proof_anchor_v2 WHERE permit_id=? FOR UPDATE",id)
        require(anchors.size==1 && anchors.head("witness_digest")==MySQLCdcGroup.hash(anchors.head("witness_json")),"pinned proof anchor missing")
        val w=CapturePinnedWitness.parse(anchors.head("witness_json"))
        val raw=p.offset.parseJson.asJsObject.fields.getOrElse("offset",p.offset.parseJson).asJsObject.fields
        val offset=MySQLCdcGroup.canonical(JsObject(raw.filter {case(k,_)=>Set("file","pos","event","row","gtids","snapshot")(k)}))
        require(w.capture==p.captureId && w.identity.task==task && w.identity.offset==offset &&
          w.identity.guardianAttempt==p.oldAttempt,"pinned proof identity changed")
        CapturePinnedProof.verifyExactOn(c,w);w
      }
    }
  }
  private[cdc] def authorizedBPermits(capture:String,offset:String,ids:Vector[String]):Boolean=connection {c=>
    val current=owner(c)
    if(current("status")!="ACTIVE" || managedOn(c,current("owner_attempt")).isEmpty || ids.size!=1)false
    else {
      val entries=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE capture_id=? AND task_id=? AND claimed_by=? AND consumed=FALSE LIMIT 2",capture,ids.head,current("owner_attempt")).map(permit)
      require(entries.size<=1,"duplicate pending B permits")
      entries.foreach(p=>requireLegacyPermit(c,p.id))
      ids.forall(id=>entries.exists(p=>p.taskId==id && p.offset==offset && p.claimedBy.contains(current("owner_attempt")) && !p.consumed &&
        row(c,id).exists(t=>t.view.captureId==capture && t.view.table==p.table && t.view.generation==p.generation && t.view.desiredState=="RUNNING")))
    }
  }
  private[cdc] def authorizedBPermit(id:String,task:String):Boolean=connection {c=>
    val current=owner(c)
    requireLegacyPermit(c,id)
    current("status")=="ACTIVE" && managedOn(c,current("owner_attempt")).nonEmpty &&
      row(c,task).exists(t=>t.view.desiredState=="RUNNING" && t.grantOwner.contains(current("owner_attempt")) && t.grantId.nonEmpty) &&
      query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE permit_id=? AND task_id=?",id,task).map(permit).exists(p=>p.claimedBy.contains(current("owner_attempt")) && !p.consumed)
  }
  private[cdc] def consumeBPermit(id:String,task:String):Boolean=transaction {c=>
    val current=owner(c)
    val attempt=current("owner_attempt")
    val records=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE permit_id=? AND task_id=? FOR UPDATE",id,task).map(permit)
    records.foreach(p=>requireLegacyPermit(c,p.id))
    if(current("status")!="ACTIVE" || managedOn(c,attempt).isEmpty || records.size!=1 || !records.head.claimedBy.contains(attempt) ||
      !row(c,task,true).exists(t=>t.grantOwner.contains(attempt) && t.grantId.nonEmpty && t.view.desiredState=="RUNNING"))false
    else {if(!records.head.consumed)update(c,"UPDATE pekko_cdc_b_recovery_v1 SET consumed=TRUE WHERE permit_id=? AND task_id=?",id,task);true}
  }
  private[cdc] def consumedBPermit(id:String,task:String):Boolean=connection {c=>
    val current=owner(c);val attempt=current("owner_attempt")
    requireLegacyPermit(c,id)
    current("status")=="ACTIVE" && managedOn(c,attempt).nonEmpty &&
      row(c,task).exists(t=>t.grantOwner.contains(attempt) && t.grantId.nonEmpty && t.view.desiredState=="RUNNING") &&
      query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE permit_id=? AND task_id=?",id,task).map(permit).exists(p=>p.claimedBy.contains(attempt) && p.consumed)
  }
  /** Explicit Journal rejection is terminal and does not spend an automatic-recovery attempt. */
  private[cdc] def rejectBPermit(id:String,task:String):Boolean=transaction {c=>
    val current=owner(c);val attempt=current("owner_attempt")
    val previous=query(c,"SELECT task_id FROM pekko_cdc_b_rejection_v1 WHERE permit_id=?",id).headOption
    if(previous.nonEmpty)previous.exists(_("task_id")==task)
    else {
      val p=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE permit_id=? AND task_id=? FOR UPDATE",id,task).map(permit)
      p.foreach(value=>requireLegacyPermit(c,value.id))
      if(current("status")!="ACTIVE" || managedOn(c,attempt).isEmpty || p.size!=1 || p.head.consumed || !p.head.claimedBy.contains(attempt) ||
        !row(c,task,true).exists(t=>t.grantOwner.contains(attempt) && t.grantId.nonEmpty && t.view.desiredState=="RUNNING"))false
      else {
        val budget=query(c,"SELECT attempts FROM pekko_cdc_b_budget_v1 WHERE task_id=? FOR UPDATE",task).headOption.map(_("attempts").toInt).getOrElse(0)
        require(budget>0,"rejected permit budget missing")
        update(c,"INSERT INTO pekko_cdc_b_rejection_v1 VALUES(?,?)",id,task)
        update(c,"UPDATE pekko_cdc_b_recovery_v1 SET consumed=TRUE WHERE permit_id=? AND task_id=?",id,task)
        update(c,"UPDATE pekko_cdc_b_budget_v1 SET attempts=? WHERE task_id=?",budget-1,task);true
      }
    }
  }
  private[cdc] def rejectedBPermit(id:String,task:String):Boolean=connection(c=>
    query(c,"SELECT task_id FROM pekko_cdc_b_rejection_v1 WHERE permit_id=?",id).exists(_("task_id")==task))
  private[cdc] def ownerAttempt:Option[(String,String)]=connection(c=>query(c,"SELECT owner_attempt,status FROM pekko_cdc_owner_v1 WHERE platform_id='default'").headOption.flatMap(r=>Option(r("owner_attempt")).map(_->r("status"))))
  /** A claimed v2 permit requires authenticated owner delegation, not a new B proof. */
  private[cdc] def hasClaimedVersionedFence(attempt:String):Boolean=connection {c=>
    query(c,"SELECT 1 FROM pekko_cdc_b_recovery_v1 p WHERE p.claimed_by=? AND (p.proof_version<>1 OR EXISTS (SELECT 1 FROM pekko_cdc_b_fence_v2 f WHERE f.permit_id=p.permit_id)) LIMIT 1",attempt).nonEmpty
  }
  /** Seal the retired owner's exact evidence without releasing its grants or changing its receipt. */
  private[cdc] def freezeVersionedOwner(attempt:String,proof:CdcManagedIsolation.Verified,now:Long):Unit=transaction {c=>
    val current=owner(c)
    require(current("owner_attempt")==attempt && Set("ACTIVE","UNKNOWN")(current("status")),"old owner changed")
    require(proof!=null && managedOn(c,attempt).contains(proof.managed),"guardian owner changed")
    require(query(c,"SELECT manifest_json FROM pekko_cdc_managed_manifest_v1 WHERE owner_attempt=? FOR UPDATE",attempt)
      .headOption.exists(_("manifest_json")==proof.manifest),"guardian manifest changed")
    val records=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE claimed_by=? AND proof_version<>1 LIMIT 2 FOR UPDATE",attempt)
    require(records.size==1 && records.head("proof_version")=="2","versioned permit is not unique")
    val p=records.head
    val fence=query(c,"SELECT * FROM pekko_cdc_b_fence_v2 WHERE permit_id=? FOR UPDATE",p("permit_id"))
    val scan=query(c,"SELECT * FROM pekko_cdc_b_fence_scan_v2 WHERE owner_attempt=? FOR UPDATE",p("owner_attempt"))
    val anchor=query(c,"SELECT * FROM pekko_cdc_b_proof_anchor_v2 WHERE permit_id=? FOR UPDATE",p("permit_id"))
    val receipt=query(c,"SELECT * FROM pekko_cdc_b_receipt_v2 WHERE permit_id=? FOR UPDATE",p("permit_id"))
    val release=query(c,"SELECT * FROM pekko_cdc_b_release_v2 WHERE permit_id=? FOR UPDATE",p("permit_id"))
    val token=query(c,"SELECT * FROM pekko_cdc_reader_start_v2 WHERE permit_id=? FOR UPDATE",p("permit_id"))
    require(fence.size==1 && scan.size==1 && anchor.size<=1 && receipt.size<=1 && release.size<=1 && token.size<=1,"versioned evidence is not unique")
    def canonical(rows:Vector[Map[String,String]]):JsValue=JsArray(rows.map(r=>JsObject(r.toSeq.map {case(k,v)=>k->Option(v).map(JsString(_)).getOrElse(JsNull)}:_*)))
    val digest=MySQLCdcGroup.hash(MySQLCdcGroup.canonical(JsObject("permit"->canonical(records),"fence"->canonical(fence),"scan"->canonical(scan),"anchor"->canonical(anchor),"receipt"->canonical(receipt),"release"->canonical(release),"token"->canonical(token))))
    val md=MySQLCdcGroup.hash(proof.manifest)
    val old=query(c,"SELECT * FROM pekko_cdc_owner_freeze_v2 WHERE owner_attempt=? FOR UPDATE",attempt)
    if(old.isEmpty)update(c,"INSERT INTO pekko_cdc_owner_freeze_v2 VALUES(?,?,?,?,?,?,?,?,?)",attempt,p("permit_id"),p("task_id"),p("capture_id"),md,proof.operationId,proof.signedProof,digest,now)
    else require(old.size==1 && old.head("permit_id")==p("permit_id") && old.head("task_id")==p("task_id") && old.head("capture_id")==p("capture_id") &&
      old.head("manifest_digest")==md && old.head("isolation_operation")==proof.operationId && old.head("isolation_proof")==proof.signedProof && old.head("evidence_digest")==digest,
      "frozen owner evidence changed")
    update(c,"UPDATE pekko_cdc_grants_v1 SET status='UNKNOWN' WHERE owner_attempt=? AND status='ACTIVE'",attempt)
    update(c,"UPDATE pekko_cdc_owner_v1 SET status='UNKNOWN',updated_at=? WHERE platform_id='default'",now)
  }
  /** Register a successor for recovery only. This does not transfer a claim or activate an owner. */
  private[cdc] def admitFrozenSuccessor(oldAttempt:String,newAttempt:String,next:CdcManagedOwner,manifest:String,
    fresh:(WorkerAttestationContext,String)=>VerifiedWorkerAttestation,now:Long):Unit=transaction {c=>
    require(newAttempt!=null && newAttempt.matches("[A-Za-z0-9_-]{1,128}") && newAttempt!=oldAttempt && next!=null && fresh!=null,
      "invalid successor identity")
    val current=owner(c)
    require(current("owner_attempt")==oldAttempt && current("status")=="UNKNOWN","frozen owner changed")
    val frozen=query(c,"SELECT * FROM pekko_cdc_owner_freeze_v2 WHERE owner_attempt=? FOR UPDATE",oldAttempt)
    require(frozen.size==1,"owner isolation not frozen")
    val old=managedOn(c,oldAttempt).getOrElse(throw new IllegalStateException("old managed owner missing"))
    require(next.deploymentId==old.deploymentId && next.hostId==old.hostId && next.guardianBoot==old.guardianBoot &&
      next.guardianPublicKey==old.guardianPublicKey && next.bootId!=old.bootId && next.pid>0 &&
      Vector(next.bootId,next.startIdentity,next.profileHash).forall(s=>s!=null && s.nonEmpty),"successor guardian or identity changed")
    val permitId=frozen.head("permit_id")
    val permits=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE permit_id=? AND claimed_by=? AND proof_version=2 FOR UPDATE",permitId,oldAttempt)
    val fences=query(c,"SELECT * FROM pekko_cdc_b_fence_v2 WHERE permit_id=? FOR UPDATE",permitId)
    require(permits.size==1 && fences.size==1,"frozen permit or fence missing")
    val scans=query(c,"SELECT * FROM pekko_cdc_b_fence_scan_v2 WHERE owner_attempt=? FOR UPDATE",permits.head("owner_attempt"))
    val anchors=query(c,"SELECT * FROM pekko_cdc_b_proof_anchor_v2 WHERE permit_id=? FOR UPDATE",permitId)
    val receipts=query(c,"SELECT * FROM pekko_cdc_b_receipt_v2 WHERE permit_id=? FOR UPDATE",permitId)
    require(scans.size==1 && anchors.size<=1 && receipts.size==1,"frozen scan or receipt missing")
    val releases=query(c,"SELECT * FROM pekko_cdc_b_release_v2 WHERE permit_id=? FOR UPDATE",permitId)
    val tokens=query(c,"SELECT * FROM pekko_cdc_reader_start_v2 WHERE permit_id=? FOR UPDATE",permitId)
    def canonical(rows:Vector[Map[String,String]]):JsValue=JsArray(rows.map(r=>JsObject(r.toSeq.map {case(k,v)=>k->Option(v).map(JsString(_)).getOrElse(JsNull)}:_*)))
    val digest=MySQLCdcGroup.hash(MySQLCdcGroup.canonical(JsObject("permit"->canonical(permits),"fence"->canonical(fences),"scan"->canonical(scans),"anchor"->canonical(anchors),"receipt"->canonical(receipts),"release"->canonical(releases),"token"->canonical(tokens))))
    require(digest==frozen.head("evidence_digest"),"frozen owner evidence changed")
    val b=fenceBinding(fences.head);val account=receipts.head("controller_account");val separator=account.lastIndexOf('@')
    require(separator>0,"invalid controller account")
    val fields=manifest.parseJson.asJsObject.fields
    require(fields.keySet==Set("version","accounts") && fields("version")==JsNumber(1),"invalid successor manifest")
    val accounts=fields("accounts").asInstanceOf[JsArray].elements.map(_.asJsObject.fields).filter(f=>
      f.get("serverUuid").contains(JsString(b.sourceUuid)) && f.get("user").contains(JsString(account.substring(0,separator))))
    require(accounts.size==1 && accounts.head.get("endpoint").exists(_.isInstanceOf[JsString]),"successor controller inventory missing or ambiguous")
    val endpoint=accounts.head("endpoint").asInstanceOf[JsString].value
    val context=WorkerAttestationContext(newAttempt,MySQLCdcGroup.hash(manifest),b.sourceUuid,b.database,endpoint,account,permitId,fenceBindingDigest(b))
    def attest():VerifiedWorkerAttestation={
      val bytes=new Array[Byte](32);new java.security.SecureRandom().nextBytes(bytes)
      val nonce=java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
      val a=fresh(context,nonce)
      require(a!=null && a.nonce==nonce && a.context==context && a.profile==next.profileHash &&
        a.identity.bootId==next.bootId && a.identity.deploymentId==next.deploymentId && a.identity.pid==next.pid &&
        a.identity.startIdentity==next.startIdentity && a.guardian.bootId==next.guardianBoot && a.guardian.hostId==next.hostId &&
        a.guardian.deploymentId==next.deploymentId && a.guardian.publicKey==next.guardianPublicKey,"successor attestation changed")
      SnapshotGuardianWire.verifyWorkerAttestation(a.signedEnvelope,a.guardian,a.identity,a.profile,context,nonce)
    }
    val a=attest()
    val existing=query(c,"SELECT * FROM pekko_cdc_owner_admission_v2 WHERE old_attempt=? FOR UPDATE",oldAttempt)
    if(existing.isEmpty){
      require(managedOn(c,newAttempt).isEmpty,"successor attempt already used")
      update(c,"INSERT INTO pekko_cdc_managed_owner_v1 VALUES(?,?,?,?,?,?,?,?,?)",newAttempt,next.bootId,next.deploymentId,next.hostId,next.guardianBoot,next.guardianPublicKey,next.pid,next.startIdentity,next.profileHash)
      update(c,"INSERT INTO pekko_cdc_managed_manifest_v1 VALUES(?,?,?)",newAttempt,manifest,now)
      update(c,"INSERT INTO pekko_cdc_owner_admission_v2 VALUES(?,?,?,?,?,?,?,?)",oldAttempt,newAttempt,permitId,frozen.head("evidence_digest"),context.manifestDigest,a.signedEnvelope,a.nonce,now)
    }else require(existing.size==1 && existing.head("new_attempt")==newAttempt && existing.head("permit_id")==permitId &&
      existing.head("freeze_digest")==frozen.head("evidence_digest") && existing.head("manifest_digest")==context.manifestDigest &&
      managedOn(c,newAttempt).contains(next) && query(c,"SELECT manifest_json FROM pekko_cdc_managed_manifest_v1 WHERE owner_attempt=?",newAttempt)
        .headOption.exists(_("manifest_json")==manifest),"successor admission changed")
    val again=attest();require(again.identity==a.identity && again.guardian==a.guardian && again.context==a.context,"successor changed during admission")
  }
  /** Record a delegation candidate only while source, actual D/ACK, Journal and guardian proofs are live.
    * Recording this evidence does not transfer a claim, grant or Reader token. */
  private[cdc] def recordFrozenDelegation(oldAttempt:String,newAttempt:String,evidence:BootstrapJournal.PersistedRecoveryReceipt,
    source:VerifiedSourceReceipt,fresh:(WorkerAttestationContext,String)=>VerifiedWorkerAttestation,
    capture:CdcCaptureFenceVerifier,journal:CdcJournalFenceVerifier,now:Long):Unit=transaction {c=>
    require(evidence!=null && source!=null && fresh!=null && capture!=null && journal!=null,"delegation proof missing")
    c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ)
    source.requireLive()
    val current=owner(c)
    require(current("owner_attempt")==oldAttempt && current("status")=="UNKNOWN","frozen owner changed")
    val frozen=query(c,"SELECT * FROM pekko_cdc_owner_freeze_v2 WHERE owner_attempt=? FOR UPDATE",oldAttempt)
    val admitted=query(c,"SELECT * FROM pekko_cdc_owner_admission_v2 WHERE old_attempt=? FOR UPDATE",oldAttempt)
    require(frozen.size==1 && admitted.size==1 && admitted.head("new_attempt")==newAttempt &&
      admitted.head("freeze_digest")==frozen.head("evidence_digest"),"successor admission missing")
    val managed=managedOn(c,newAttempt).getOrElse(throw new IllegalArgumentException("successor identity missing"))
    val manifest=query(c,"SELECT manifest_json FROM pekko_cdc_managed_manifest_v1 WHERE owner_attempt=? FOR UPDATE",newAttempt)
    require(manifest.size==1 && MySQLCdcGroup.hash(manifest.head("manifest_json"))==admitted.head("manifest_digest"),"successor manifest changed")
    val id=frozen.head("permit_id")
    val records=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE permit_id=? AND claimed_by=? AND proof_version=2 FOR UPDATE",id,oldAttempt)
    require(records.size==1,"old permit changed")
    val p=permit(records.head)
    val fences=query(c,"SELECT * FROM pekko_cdc_b_fence_v2 WHERE permit_id=? FOR UPDATE",id)
    val scans=query(c,"SELECT * FROM pekko_cdc_b_fence_scan_v2 WHERE owner_attempt=? FOR UPDATE",p.oldAttempt)
    val anchors=query(c,"SELECT * FROM pekko_cdc_b_proof_anchor_v2 WHERE permit_id=? FOR UPDATE",id)
    val receipts=query(c,"SELECT * FROM pekko_cdc_b_receipt_v2 WHERE permit_id=? FOR UPDATE",id)
    val releases=query(c,"SELECT * FROM pekko_cdc_b_release_v2 WHERE permit_id=? FOR UPDATE",id)
    val tokens=query(c,"SELECT * FROM pekko_cdc_reader_start_v2 WHERE permit_id=? FOR UPDATE",id)
    require(fences.size==1 && scans.size==1 && anchors.size==1 && receipts.size==1 && releases.isEmpty && tokens.size<=1,
      "frozen source receipt state changed")
    def canonical(rows:Vector[Map[String,String]]):JsValue=JsArray(rows.map(r=>JsObject(r.toSeq.map {case(k,v)=>k->Option(v).map(JsString(_)).getOrElse(JsNull)}:_*)))
    val digest=MySQLCdcGroup.hash(MySQLCdcGroup.canonical(JsObject("permit"->canonical(records),"fence"->canonical(fences),
      "scan"->canonical(scans),"anchor"->canonical(anchors),"receipt"->canonical(receipts),"release"->canonical(releases),"token"->canonical(tokens))))
    require(digest==frozen.head("evidence_digest"),"frozen evidence changed")
    val b=fenceBinding(fences.head);val r=source.receipt;val receipt=receipts.head
    require(p.consumed && r.binding==b && r.markerCreated!=p.markerPresent && r.versionAfter==(if(r.markerCreated)Math.addExact(b.version,1L)else b.version) &&
      source.sourceUuid==b.sourceUuid && source.database==b.database && receipt("task_id")==p.taskId && receipt("owner_attempt")==oldAttempt &&
      source.account==receipt("controller_account") &&
      receipt("binding_digest")==fenceBindingDigest(b) && receipt("version_after").toLong==r.versionAfter &&
      Set("1","TRUE","true")(receipt("marker_created"))==r.markerCreated,"source or receipt binding changed")
    val sep=source.account.lastIndexOf('@');require(sep>0,"invalid successor controller account")
    val accounts=manifest.head("manifest_json").parseJson.asJsObject.fields("accounts").asInstanceOf[JsArray].elements
    require(accounts.exists {v=>val f=v.asJsObject.fields;f.get("endpoint").contains(JsString(source.endpoint)) &&
      f.get("serverUuid").contains(JsString(b.sourceUuid)) && f.get("user").contains(JsString(source.account.substring(0,sep)))},"delegation controller inventory changed")
    val rd=receipt("receipt_digest")
    require(evidence.persistenceId=="bootstrap-v1-"+p.captureId+"-"+p.taskId && evidence.permitId==id && evidence.receiptDigest==rd &&
      evidence.phase=="RECOVERY_PENDING" && evidence.executionId.nonEmpty &&
      row(c,p.taskId,true).exists(_.view.executionId.contains(evidence.executionId)),"delegation Journal identity changed")
    capture.verifyOn(c,p,b);journal.verifyOn(c,evidence)
    val context=WorkerAttestationContext(newAttempt,admitted.head("manifest_digest"),b.sourceUuid,b.database,source.endpoint,source.account,id,fenceBindingDigest(b))
    def attest():VerifiedWorkerAttestation={
      val bytes=new Array[Byte](32);new java.security.SecureRandom().nextBytes(bytes)
      val nonce=java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(bytes);val a=fresh(context,nonce)
      require(a!=null && a.nonce==nonce && a.context==context && a.profile==managed.profileHash &&
        a.identity.bootId==managed.bootId && a.identity.deploymentId==managed.deploymentId && a.identity.pid==managed.pid &&
        a.identity.startIdentity==managed.startIdentity && a.guardian.bootId==managed.guardianBoot && a.guardian.hostId==managed.hostId &&
        a.guardian.deploymentId==managed.deploymentId && a.guardian.publicKey==managed.guardianPublicKey,"delegation guardian changed")
      SnapshotGuardianWire.verifyWorkerAttestation(a.signedEnvelope,a.guardian,a.identity,a.profile,context,nonce)
    }
    val a=attest();source.requireLive()
    val previous=query(c,"SELECT * FROM pekko_cdc_owner_delegation_v2 WHERE old_attempt=? FOR UPDATE",oldAttempt)
    if(previous.isEmpty)update(c,"INSERT INTO pekko_cdc_owner_delegation_v2 VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",oldAttempt,newAttempt,id,digest,rd,
      evidence.persistenceId,evidence.sequenceNr,evidence.revision,evidence.executionId,a.signedEnvelope,a.nonce,now)
    else require(previous.size==1 && previous.head("new_attempt")==newAttempt && previous.head("permit_id")==id && previous.head("freeze_digest")==digest &&
      previous.head("receipt_digest")==rd && previous.head("journal_pid")==evidence.persistenceId && previous.head("journal_sequence").toLong==evidence.sequenceNr &&
      previous.head("journal_revision").toLong==evidence.revision && previous.head("execution_id")==evidence.executionId,"delegation evidence changed")
    val again=attest();require(again.identity==a.identity && again.guardian==a.guardian && again.context==a.context,"delegation guardian changed")
    source.requireLive()
  }
  def managedOwnerNeedsIsolation(boot:String):Boolean=connection {c=>
    val current=query(c,"SELECT owner_attempt,status FROM pekko_cdc_owner_v1 WHERE platform_id='default'").headOption
    current.exists(r=>r("status")=="UNKNOWN" && Option(r("owner_attempt")).flatMap(a=>managedOn(c,a)).exists(_.bootId==boot))
  }
  def managedOwnerNeedsIsolation(expected:CdcManagedOwner):Boolean=connection {c=>
    val current=query(c,"SELECT owner_attempt,status FROM pekko_cdc_owner_v1 WHERE platform_id='default'").headOption
    current.exists {r=>
      val actual=Option(r("owner_attempt")).flatMap(a=>managedOn(c,a))
      if(r("status")=="UNKNOWN" && actual.exists(_.bootId==expected.bootId)){
        require(actual.contains(expected),"CDC owner identity differs from guardian launch")
        true
      }else false
    }
  }
  def managedOwnerIs(boot:String):Boolean=connection {c=>
    val current=query(c,"SELECT owner_attempt,status FROM pekko_cdc_owner_v1 WHERE platform_id='default'").headOption
    current.exists(r=>Set("ACTIVE","UNKNOWN")(r("status")) && Option(r("owner_attempt")).flatMap(a=>managedOn(c,a)).exists(_.bootId==boot))
  }
  private[cdc] def soleOutstandingTask(attempt:String):Option[CdcTaskView]=connection {c=>
    val grants=query(c,"SELECT task_id,owner_attempt,pool,status FROM pekko_cdc_grants_v1 WHERE status IN ('ACTIVE','UNKNOWN') ORDER BY grant_id LIMIT 129")
    val unknown=grants.filter(_("status")=="UNKNOWN")
    val snapshots=grants.filter(_("pool")=="SNAPSHOT")
    val candidate=if(unknown.size==1 && unknown.head("pool")=="SNAPSHOT")unknown.headOption
      else if(unknown.isEmpty && snapshots.size==1)snapshots.headOption else None
    if(grants.nonEmpty && grants.size<=128 && grants.forall(_("owner_attempt")==attempt))candidate.flatMap(g=>row(c,g("task_id")).map(_.view))
    else if(grants.isEmpty){
      val waiting=query(c,"SELECT p.task_id FROM pekko_cdc_b_recovery_v1 p JOIN pekko_cdc_tasks_v1 t ON p.task_id=t.task_id WHERE p.claimed_by=? AND t.phase='QUEUED' AND t.grant_id IS NULL LIMIT 2",attempt)
      if(waiting.size==1)row(c,waiting.head("task_id")).filter(_.grantId.isEmpty).map(_.view)else None
    } else None
  }
  /** Caller must supply independently verified guardian/drain and actual source/D evidence. */
  private[cdc] def releaseForPendingB(attempt:String,proof:CdcManagedIsolation.Verified,b:CdcManagedPendingB.Verified,now:Long):CdcBRecoveryPermit=
    releaseForPendingBInternal(attempt,proof,b,now,None)
  /** Persist the source binding atomically with the permit; its authority must be proven by the caller. */
  private[cdc] def releaseForPendingB(attempt:String,proof:CdcManagedIsolation.Verified,b:CdcManagedPendingB.Verified,now:Long,fence:CaptureFenceBinding):CdcBRecoveryPermit=
    releaseForPendingBInternal(attempt,proof,b,now,Some(fence))
  private def releaseForPendingBInternal(attempt:String,proof:CdcManagedIsolation.Verified,b:CdcManagedPendingB.Verified,now:Long,fence:Option[CaptureFenceBinding]):CdcBRecoveryPermit=transaction {c=>
    require(b.sourceFence==fence,"source fence proof cannot be omitted or substituted")
    val intent=intentRow(c,attempt)
    require(intent.map(fenceBinding)==fence,"source fence intent cannot be omitted or substituted")
    intent.foreach {r=>
      require(r("task_id")==b.taskId && r("capture_id")==b.captureId && r("offset_json")==b.offset &&
        r("guardian_operation")==proof.operationId && r("signed_proof")==proof.signedProof,"source fence intent evidence changed")
    }
    fence.foreach {f=>
      require(b.sourceFence.contains(f) && f.sourceUuid==b.sourceUuid && f.database==b.sourceDatabase &&
        f.action=="SUBSCRIBE" && f.table==b.table && f.generation==b.generation &&
        f.operation==MySQLCdcGroup.hash(b.captureId+"|B|"+b.taskId) && f.epoch>0 && f.version>=0 &&
        f.ownerToken!=null && f.ownerToken.matches("[a-f0-9]{64}"),"source fence proof does not match pending B")
    }
    def storedFence(permitId:String):Option[CaptureFenceBinding]=query(c,"SELECT * FROM pekko_cdc_b_fence_v2 WHERE permit_id=?",permitId).headOption.map {r=>
      CaptureFenceBinding(r("source_uuid"),r("database_name"),r("epoch").toLong,r("owner_token"),r("version").toLong,
        r("operation_id"),r("action"),r("table_name"),r("generation"))
    }
    val old=owner(c)
    val previous=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE owner_attempt=?",attempt).headOption
    def matches(p:CdcBRecoveryPermit)=p.taskId==b.taskId && p.captureId==b.captureId && p.offset==b.offset && p.markerPresent==b.markerPresent &&
      p.table==b.table && p.generation==b.generation && p.operation==proof.operationId && p.proof==proof.signedProof
    previous match {
      case Some(r)=>val p=permit(r);require(matches(p) && r("proof_version")== (if(fence.isDefined)"2" else "1") && storedFence(p.id)==fence,"recovery permit evidence changed");p
      case None=>
        require(old("owner_attempt")==attempt && Set("ACTIVE","UNKNOWN")(old("status")),"old CDC owner changed")
        require(managedOn(c,attempt).contains(proof.managed),"guardian proof is not bound to CDC owner")
        require(query(c,"SELECT manifest_json FROM pekko_cdc_managed_manifest_v1 WHERE owner_attempt=?",attempt).headOption.exists(_("manifest_json")==proof.manifest),"resource manifest changed")
        val grants=query(c,"SELECT * FROM pekko_cdc_grants_v1 WHERE status IN ('ACTIVE','UNKNOWN') ORDER BY grant_id LIMIT 129 FOR UPDATE")
        require(grants.size<=128 && grants.forall(_("owner_attempt")==attempt),"other outstanding work prevents recovery")
        val candidateGrants=grants.filter(_("task_id")==b.taskId)
        val otherGrants=grants.filterNot(_("task_id")==b.taskId)
        require(candidateGrants.size<=1 && candidateGrants.forall(_("pool")=="SNAPSHOT") && otherGrants.forall(_("status")=="ACTIVE"),"other uncertain work prevents recovery")
        val otherTasks=otherGrants.map {g=>
          val t=row(c,g("task_id"),true).getOrElse(throw new IllegalStateException("outstanding task missing"))
          require(t.grantId.contains(g("grant_id")) && t.grantOwner.contains(attempt) && t.view.captureId==b.captureId,"other task or grant changed")
          require(g("pool")=="TAIL" && t.view.phase=="STREAMING" && t.view.desiredState=="RUNNING" &&
            t.view.executionId.exists(_.nonEmpty) && t.view.initializationEvidence.exists(_.nonEmpty) &&
            query(c,"SELECT 1 FROM pekko_cdc_b_recovery_v1 WHERE task_id=? AND consumed=FALSE LIMIT 1",t.view.taskId).isEmpty,
            "other task is not an independently recoverable streaming tail")
          (g,t)
        }
        val claimed=query(c,"SELECT task_id FROM pekko_cdc_b_recovery_v1 WHERE claimed_by=? AND consumed=FALSE LIMIT 2 FOR UPDATE",attempt)
        require(claimed.size<=1 && claimed.forall(_("task_id")==b.taskId),"other claimed recovery permits prevent recovery")
        val task=row(c,b.taskId,true).getOrElse(throw new IllegalStateException("pending B task missing"))
        val grantMatches=if(candidateGrants.nonEmpty)task.grantId.contains(candidateGrants.head("grant_id")) && task.grantOwner.contains(attempt)
          else task.grantId.isEmpty && task.view.phase=="QUEUED" && query(c,"SELECT permit_id FROM pekko_cdc_b_recovery_v1 WHERE claimed_by=? AND task_id=? LIMIT 2",attempt,b.taskId).size==1
        require(task.view.captureId==b.captureId && task.view.table==b.table && task.view.generation==b.generation && task.view.desiredState=="RUNNING" && grantMatches,"pending B task or grant changed")
        val budgetRow=query(c,"SELECT attempts FROM pekko_cdc_b_budget_v1 WHERE task_id=? FOR UPDATE",b.taskId).headOption
        val budget=budgetRow.map(_("attempts").toInt).getOrElse(0)
        if(budget>=3)throw new CdcPlatformException("AUTOMATIC_RECOVERY_EXHAUSTED")
        if(budgetRow.isEmpty)update(c,"INSERT INTO pekko_cdc_b_budget_v1 VALUES(?,1)",b.taskId)
        else update(c,"UPDATE pekko_cdc_b_budget_v1 SET attempts=? WHERE task_id=?",budget+1,b.taskId)
        val id=UUID.randomUUID().toString
        update(c,"INSERT INTO pekko_cdc_b_recovery_v1(owner_attempt,permit_id,task_id,capture_id,offset_json,marker_present,table_name,generation,operation_id,signed_proof,created_at,proof_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",attempt,id,b.taskId,b.captureId,b.offset,b.markerPresent,b.table,b.generation,proof.operationId,proof.signedProof,now,if(fence.isDefined)2 else 1)
        fence.foreach {f=>update(c,"INSERT INTO pekko_cdc_b_fence_v2 VALUES(?,?,?,?,?,?,?,?,?,?)",id,f.sourceUuid,f.database,f.epoch,f.ownerToken,f.version,f.operation,f.action,f.table,f.generation)}
        grants.foreach(g=>update(c,"UPDATE pekko_cdc_grants_v1 SET status='RELEASED',finished_at=? WHERE grant_id=?",now,g("grant_id")))
        otherTasks.foreach {case(_,t)=>
          update(c,"UPDATE pekko_cdc_tasks_v1 SET runtime_health='WAITING',release_status='RELEASED',grant_id=NULL,grant_owner=NULL,continuation_json=NULL,continuation_owner=NULL,due_at=?,schedulable=TRUE,observed_at=? WHERE task_id=?",now,now,t.view.taskId)
        }
        update(c,"UPDATE pekko_cdc_b_recovery_v1 SET consumed=TRUE WHERE claimed_by=? AND task_id=? AND consumed=FALSE",attempt,b.taskId)
        update(c,"UPDATE pekko_cdc_tasks_v1 SET phase='QUEUED',runtime_health='WAITING',release_status='RELEASED',grant_id=NULL,grant_owner=NULL,continuation_json=NULL,continuation_owner=NULL,due_at=?,schedulable=TRUE,observed_at=? WHERE task_id=?",now,now,b.taskId)
        update(c,"UPDATE pekko_cdc_owner_v1 SET status='STOPPED',updated_at=? WHERE platform_id='default'",now)
        permit(query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE owner_attempt=?",attempt).head)
    }
  }
  private[cdc] def releaseEmptyDrainedOwner(attempt:String,proof:CdcManagedIsolation.Verified,now:Long):Boolean=transaction {c=>
    val old=owner(c)
    val noGrants=query(c,"SELECT 1 FROM pekko_cdc_grants_v1 WHERE status IN ('ACTIVE','UNKNOWN') LIMIT 1").isEmpty
    val noPending=query(c,"SELECT 1 FROM pekko_cdc_b_recovery_v1 WHERE claimed_by=? AND consumed=FALSE LIMIT 1",attempt).isEmpty
    // A consumed v2 permit still owns immutable receipt/Journal/startup evidence.
    // Until an authenticated owner-delegation protocol exists it is not an empty owner.
    val noVersionedClaim=query(c,"SELECT 1 FROM pekko_cdc_b_recovery_v1 p WHERE p.claimed_by=? AND (p.proof_version<>1 OR EXISTS (SELECT 1 FROM pekko_cdc_b_fence_v2 f WHERE f.permit_id=p.permit_id)) LIMIT 1",attempt).isEmpty
    if(old("owner_attempt")!=attempt || !Set("ACTIVE","UNKNOWN")(old("status")) || !noGrants || !noPending || !noVersionedClaim ||
      !managedOn(c,attempt).contains(proof.managed) || !query(c,"SELECT manifest_json FROM pekko_cdc_managed_manifest_v1 WHERE owner_attempt=?",attempt).headOption.exists(_("manifest_json")==proof.manifest))false
    else {update(c,"UPDATE pekko_cdc_owner_v1 SET status='STOPPED',updated_at=? WHERE platform_id='default'",now);true}
  }
  private[cdc] def recordManagedManifest(attempt:String,manifest:String,now:Long):Unit=transaction {c=>
    val current=owner(c)
    require(current("status")=="ACTIVE" && current("owner_attempt")==attempt && managedOn(c,attempt).nonEmpty,"managed owner is not active")
    require(manifest.nonEmpty && manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=65536,"invalid managed manifest")
    val old=query(c,"SELECT manifest_json FROM pekko_cdc_managed_manifest_v1 WHERE owner_attempt=?",attempt).headOption
    old match {
      case Some(r)=>require(r("manifest_json")==manifest,"managed manifest changed")
      case None=>update(c,"INSERT INTO pekko_cdc_managed_manifest_v1 VALUES(?,?,?)",attempt,manifest,now)
    }
  }
  def beginOwner(attempt:String,now:Long,managed:Option[CdcManagedOwner]=None):Boolean=try transaction {c=>
    require(attempt.matches("[A-Za-z0-9_-]{1,128}"))
    val old=owner(c)
    val active=query(c,"SELECT COUNT(*) AS n FROM pekko_cdc_grants_v1 WHERE status IN ('ACTIVE','UNKNOWN')").head("n").toLong
    val pending=query(c,"SELECT permit_id FROM pekko_cdc_b_recovery_v1 WHERE consumed=FALSE LIMIT 1").nonEmpty
    val claimed=query(c,"SELECT permit_id FROM pekko_cdc_b_recovery_v1 WHERE consumed=FALSE AND claimed_by IS NOT NULL LIMIT 1").nonEmpty
    // A previously stopped owner can still hold consumed v2 evidence. Never
    // start a replacement through the ordinary admission path in that case.
    val versionedClaim=query(c,"SELECT p.permit_id FROM pekko_cdc_b_recovery_v1 p WHERE p.claimed_by IS NOT NULL AND (p.proof_version<>1 OR EXISTS (SELECT 1 FROM pekko_cdc_b_fence_v2 f WHERE f.permit_id=p.permit_id)) LIMIT 1").nonEmpty
    if(old("status")=="STOPPED" && active==0 && !claimed && !versionedClaim && (managed.nonEmpty || !pending)){
      require(managedOn(c,attempt).isEmpty,"owner attempt cannot be reused")
      managed.foreach {m=>
        require(Vector(m.bootId,m.deploymentId,m.hostId,m.guardianBoot,m.guardianPublicKey,m.startIdentity,m.profileHash).forall(_.nonEmpty) && m.pid>0,"invalid managed owner identity")
        update(c,"INSERT INTO pekko_cdc_managed_owner_v1 VALUES(?,?,?,?,?,?,?,?,?)",attempt,m.bootId,m.deploymentId,m.hostId,m.guardianBoot,m.guardianPublicKey,m.pid,m.startIdentity,m.profileHash)
      }
      if(managed.nonEmpty)update(c,"UPDATE pekko_cdc_b_recovery_v1 SET claimed_by=? WHERE claimed_by IS NULL AND consumed=FALSE",attempt)
      val lineage=Option(old("owner_attempt")).flatMap {previous=>
        query(c,"SELECT lineage FROM pekko_cdc_clean_owner_v1 WHERE owner_attempt=? AND clean=TRUE",previous).headOption.map(_("lineage"))
      }.getOrElse(UUID.randomUUID().toString)
      update(c,"INSERT INTO pekko_cdc_clean_owner_v1(owner_attempt,lineage,clean) VALUES(?,?,FALSE)",attempt,lineage)
      update(c,"UPDATE pekko_cdc_owner_v1 SET owner_attempt=?,status='ACTIVE',updated_at=? WHERE platform_id='default'",attempt,now);true
    } else if(old("owner_attempt")==attempt && old("status")=="ACTIVE") {
      require(managedOn(c,attempt)==managed,"managed owner binding changed");true
    } else false
  } catch {
    case e:CdcExecutionException if e.failure.code==ExecutionFailure.CommitUnknown && e.failure.releaseConfirmed && managed.nonEmpty=>
      val exact=try transaction {c=>
        val current=owner(c)
        current("status")=="ACTIVE" && current("owner_attempt")==attempt && managedOn(c,attempt)==managed
      } catch {case scala.util.control.NonFatal(_)=>false}
      if(exact)true else throw e
  }
  def ownerStatus:String=connection(c=>query(c,"SELECT status FROM pekko_cdc_owner_v1 WHERE platform_id='default'").head("status"))
  def endOwner(attempt:String,confirmed:Boolean,now:Long):Boolean=transaction {c=>
    val old=owner(c)
    if(old("owner_attempt")!=attempt)false
    else {
      val active=query(c,"SELECT COUNT(*) AS n FROM pekko_cdc_grants_v1 WHERE status IN ('ACTIVE','UNKNOWN')").head("n").toLong
      val pending=query(c,"SELECT COUNT(*) AS n FROM pekko_cdc_b_recovery_v1 WHERE claimed_by=? AND consumed=FALSE",attempt).head("n").toLong
      // A consumed versioned claim still binds owner-specific receipt and Reader evidence.
      val versionedClaim=query(c,"SELECT 1 FROM pekko_cdc_b_recovery_v1 p WHERE p.claimed_by=? AND (p.proof_version<>1 OR EXISTS (SELECT 1 FROM pekko_cdc_b_fence_v2 f WHERE f.permit_id=p.permit_id)) LIMIT 1",attempt).nonEmpty
      val clean=confirmed && active==0 && pending==0 && !versionedClaim
      update(c,"UPDATE pekko_cdc_clean_owner_v1 SET clean=? WHERE owner_attempt=?",clean,attempt)
      update(c,"UPDATE pekko_cdc_owner_v1 SET status=?,updated_at=? WHERE platform_id='default'",if(clean)"STOPPED"else"UNKNOWN",now)
      clean
    }
  }
  def submit(request:CdcTaskRequest,now:Long):CdcTaskAcceptance={
    request.validate()
    try transaction {c=>
      // Serialize request identity against admission/cancel, including previously unexecuted rows.
      owner(c)
      row(c,request.taskId,true) match {
        case Some(existing)=>
          if(existing.view.definitionFingerprint!=request.fingerprint)throw new CdcPlatformException("CONFLICT")
          CdcTaskAcceptance(existing.view,false)
        case None=>
          if(query(c,"SELECT task_id FROM pekko_cdc_tasks_v1 WHERE capture_id=? AND table_name=? AND generation=?",request.captureId,request.table,request.generation).nonEmpty)
            throw new CdcPlatformException("CONFLICT")
          update(c,"INSERT INTO pekko_cdc_tasks_v1(task_id,capture_id,request_id,table_name,generation,target_table,definition_hash,desired_state,control_revision,phase,journal_revision,capture_readiness,runtime_health,release_status,observed_at,due_at,work_pool,schedulable) VALUES(?,?,?,?,?,?,?,'RUNNING',1,'QUEUED',0,'UNKNOWN','WAITING','RELEASED',?,?,'SNAPSHOT',TRUE)",
            request.taskId,request.captureId,request.requestId,request.table,request.generation,request.targetTable,request.fingerprint,now,now)
          CdcTaskAcceptance(row(c,request.taskId).get.view,true)
      }
    }catch {case e:SQLException if Option(e.getSQLState).exists(_.startsWith("23"))=>throw new CdcPlatformException("CONFLICT")}
  }
  def get(id:String):Option[CdcTaskView]={validateId(id);connection(c=>row(c,id).map(_.view))}
  private[cdc] def getRecord(id:String):Option[CdcTaskRecord]={validateId(id);connection(c=>row(c,id))}
  def listAfter(after:Option[String],limit:Int):CdcTaskPage={
    if(limit<1 || limit>100 || after.exists(x=> !x.matches("[a-f0-9]{64}")))throw new CdcPlatformException("INVALID_PAGE")
    connection {c=>val rows=query(c,"SELECT t.*,EXISTS(SELECT 1 FROM pekko_cdc_b_recovery_v1 p WHERE p.task_id=t.task_id AND p.consumed=FALSE) AS recovery_pending FROM pekko_cdc_tasks_v1 t WHERE t.task_id>? ORDER BY t.task_id LIMIT ?",after.getOrElse(""),limit+1).map(record).map(_.view)
      CdcTaskPage(rows.take(limit),if(rows.size>limit)rows.take(limit).lastOption.map(_.taskId)else None)}
  }
  def cancel(id:String,now:Long):CdcTaskView={validateId(id);transaction {c=>
    owner(c)
    val task=row(c,id,true).getOrElse(throw new CdcPlatformException("NOT_FOUND"));val v=task.view
    if(v.phase=="PAUSED" || v.releaseStatus=="UNKNOWN")throw new CdcPlatformException("CANCEL_NOT_PERMITTED")
    if(v.desiredState!="CANCELED" && v.phase!="CANCELED"){
      if(query(c,"SELECT permit_id FROM pekko_cdc_b_recovery_v1 WHERE task_id=? AND consumed=FALSE LIMIT 1",id).nonEmpty)
        throw new CdcPlatformException("CANCEL_NOT_PERMITTED")
      val preexec=v.executionId.isEmpty && task.grantId.isEmpty && v.phase=="QUEUED"
      update(c,"UPDATE pekko_cdc_tasks_v1 SET desired_state='CANCELED',control_revision=control_revision+1,phase=?,cancel_evidence=?,observed_at=?,due_at=?,schedulable=? WHERE task_id=?",
        if(preexec)"CANCELED"else"CANCELING",if(preexec)"PREEXEC_CANCEL"else v.cancellationEvidence.orNull,now,now,!preexec && task.grantId.isEmpty,id)
    }
    row(c,id).get.view
  }}
  /** All retained versioned permits fence configuration downgrade, across owner changes. */
  private[cdc] def persistedFenceCaptures():Set[String]=connection {c=>
    val orphan=query(c,"SELECT f.permit_id FROM pekko_cdc_b_fence_v2 f LEFT JOIN pekko_cdc_b_recovery_v1 p ON p.permit_id=f.permit_id WHERE p.permit_id IS NULL LIMIT 1")
    require(orphan.isEmpty,"orphaned source fence evidence")
    val records=query(c,"SELECT DISTINCT p.capture_id FROM pekko_cdc_b_recovery_v1 p LEFT JOIN pekko_cdc_b_fence_v2 f ON f.permit_id=p.permit_id WHERE p.proof_version<>1 OR f.permit_id IS NOT NULL LIMIT 33")
    require(records.size<=32,"too many retained fenced captures")
    records.map(_("capture_id")).toSet
  }
  /** Persisted v2 captures must never silently restart under a legacy configuration. */
  private[cdc] def outstandingFenceCaptures(attempt:String):Set[String]=transaction {c=>
    val current=owner(c)
    require(current("status")=="ACTIVE" && current("owner_attempt")==attempt && managedOn(c,attempt).nonEmpty,"managed recovery owner changed")
    val records=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE claimed_by=? AND proof_version=2 LIMIT 2 FOR UPDATE",attempt)
    require(records.size<=1,"multiple fenced recoveries require separate admission")
    records.map {r=>
      val p=permit(r);val f=query(c,"SELECT * FROM pekko_cdc_b_fence_v2 WHERE permit_id=? FOR UPDATE",p.id)
      require(f.size==1 && fenceBinding(f.head).table==p.table && fenceBinding(f.head).generation==p.generation &&
        fenceBinding(f.head).operation==MySQLCdcGroup.hash(p.captureId+"|B|"+p.taskId),"fenced recovery evidence missing")
      p.captureId
    }.toSet
  }
  /** Reader may pause only for the single managed owner's not-yet-released v2 permit. */
  private[cdc] def recoveryOnlyCaptures(attempt:String):Map[String,String]=transaction {c=>
    val current=owner(c)
    require(current("status")=="ACTIVE" && current("owner_attempt")==attempt && managedOn(c,attempt).nonEmpty,"managed recovery owner changed")
    val records=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE claimed_by=? AND proof_version=2 LIMIT 2 FOR UPDATE",attempt)
    require(records.size<=1,"multiple fenced recoveries require separate admission")
    records.flatMap {r=>
      val p=permit(r);val fences=query(c,"SELECT * FROM pekko_cdc_b_fence_v2 WHERE permit_id=? FOR UPDATE",p.id)
      require(fences.size==1 && fenceBinding(fences.head).table==p.table && fenceBinding(fences.head).generation==p.generation &&
        fenceBinding(fences.head).operation==MySQLCdcGroup.hash(p.captureId+"|B|"+p.taskId),"fenced recovery evidence missing")
      val released=query(c,"SELECT * FROM pekko_cdc_b_release_v2 WHERE permit_id=? FOR UPDATE",p.id)
      if(released.isEmpty)Some(p.captureId->p.taskId)
      else {
        val receipt=query(c,"SELECT * FROM pekko_cdc_b_receipt_v2 WHERE permit_id=? FOR UPDATE",p.id)
        require(p.consumed && released.size==1 && receipt.size==1 && released.head("owner_attempt")==attempt && receipt.head("owner_attempt")==attempt &&
          released.head("task_id")==p.taskId && receipt.head("task_id")==p.taskId && released.head("receipt_digest")==receipt.head("receipt_digest"),"release confirmation changed")
        None
      }
    }.toMap
  }
  /** Admit only the claimed v2 recovery task while its capture reader is paused. */
  private[cdc] def claimFenceRecovery(attempt:String,captures:Set[String],now:Long):Option[CdcTaskGrant]=transaction {c=>
    val o=owner(c)
    if(o("status")!="ACTIVE" || o("owner_attempt")!=attempt || managedOn(c,attempt).isEmpty || captures.isEmpty)None
    else {
      val count=query(c,"SELECT COUNT(*) AS n FROM pekko_cdc_grants_v1 WHERE pool='SNAPSHOT' AND status IN ('ACTIVE','UNKNOWN')").head("n").toInt
      if(count>=limits.snapshotSteps)None else {
        val permits=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE claimed_by=? AND proof_version=2 ORDER BY task_id LIMIT 2 FOR UPDATE",attempt)
        require(permits.size<=1,"multiple fenced recoveries require separate admission")
        permits.headOption.map(permit).filter(p=>captures(p.captureId)).flatMap {p=>
          val fences=query(c,"SELECT * FROM pekko_cdc_b_fence_v2 WHERE permit_id=? FOR UPDATE",p.id)
          require(fences.size==1,"source fence evidence missing")
          val f=fenceBinding(fences.head)
          require(f.table==p.table && f.generation==p.generation && f.action=="SUBSCRIBE" && f.operation==MySQLCdcGroup.hash(p.captureId+"|B|"+p.taskId),"source fence evidence mismatch")
          if(p.consumed)require(query(c,"SELECT 1 FROM pekko_cdc_b_receipt_v2 WHERE permit_id=? AND task_id=? AND owner_attempt=?",p.id,p.taskId,attempt).size==1,"consumed recovery receipt missing")
          val released=query(c,"SELECT 1 FROM pekko_cdc_b_release_v2 WHERE permit_id=?",p.id).nonEmpty
          row(c,p.taskId,true).filter(t=> !released && t.view.captureId==p.captureId && t.view.desiredState=="RUNNING" &&
            t.grantId.isEmpty && t.workPool=="SNAPSHOT" && t.dueAt<=now && !Set("PAUSED","CANCELED")(t.view.phase)).flatMap {task=>
            val eligible=query(c,"SELECT 1 FROM pekko_cdc_tasks_v1 WHERE task_id=? AND schedulable=TRUE",task.view.taskId).nonEmpty
            if(!eligible)None else {
              val id=UUID.randomUUID().toString
              update(c,"INSERT INTO pekko_cdc_grants_v1 VALUES(?,?,?,?,'ACTIVE',?,NULL)",id,task.view.taskId,attempt,"SNAPSHOT",now)
              update(c,"UPDATE pekko_cdc_tasks_v1 SET grant_id=?,grant_owner=?,release_status='IN_USE',runtime_health='WORKING',observed_at=?,schedulable=FALSE WHERE task_id=?",id,attempt,now,task.view.taskId)
              Some(CdcTaskGrant(id,attempt,"SNAPSHOT",row(c,task.view.taskId).get))
            }
          }
        }
      }
    }
  }
  private[cdc] def claim(attempt:String,pool:String,ready:Set[String],now:Long,onlyTasks:Option[Set[String]]=None):Option[CdcTaskGrant]=transaction {c=>
    require(pool=="SNAPSHOT" || pool=="TAIL")
    val o=owner(c)
    if(o("status")!="ACTIVE" || o("owner_attempt")!=attempt || ready.isEmpty || onlyTasks.exists(_.isEmpty))None
    else {
      val count=query(c,"SELECT COUNT(*) AS n FROM pekko_cdc_grants_v1 WHERE pool=? AND status IN ('ACTIVE','UNKNOWN')",pool).head("n").toInt
      val maximum=if(pool=="TAIL")limits.tailSteps else limits.snapshotSteps
      if(count>=maximum)None else {
        val captureIds=ready.toVector.sorted
        require(captureIds.size<=32,"too many configured capture domains")
        val candidates=onlyTasks match {
          case Some(ids)=>
            require(ids.size<=32,"too many pinned validation tasks")
            ids.toVector.sorted.flatMap {id=>
              query(c,"SELECT * FROM pekko_cdc_tasks_v1 WHERE task_id=? AND work_pool=? AND schedulable=TRUE AND due_at<=? FOR UPDATE",id,pool,now).map(record)
            }.filter(t=>ready(t.view.captureId))
          case None=>
            // Equality predicates match the scheduling index prefix. At most one head row
            // per configured capture is locked; terminal and unready domains are never scanned.
            captureIds.flatMap {capture=>
              query(c,"SELECT * FROM pekko_cdc_tasks_v1 WHERE capture_id=? AND work_pool=? AND schedulable=TRUE AND due_at<=? ORDER BY due_at,task_id LIMIT 1 FOR UPDATE",capture,pool,now).map(record)
            }
        }
        candidates.sortBy(t=>(t.dueAt,t.view.taskId)).headOption.map {task=>
          val id=UUID.randomUUID().toString
          update(c,"INSERT INTO pekko_cdc_grants_v1 VALUES(?,?,?,?,'ACTIVE',?,NULL)",id,task.view.taskId,attempt,pool,now)
          update(c,"UPDATE pekko_cdc_tasks_v1 SET grant_id=?,grant_owner=?,release_status='IN_USE',runtime_health='WORKING',observed_at=?,schedulable=FALSE WHERE task_id=?",id,attempt,now,task.view.taskId)
          CdcTaskGrant(id,attempt,pool,row(c,task.view.taskId).get)
        }
      }
    }
  }
  private def requireGrant(c:Connection,grant:CdcTaskGrant):CdcTaskRecord={
    val r=row(c,grant.task.view.taskId,true).getOrElse(throw new CdcPlatformException("NOT_FOUND"))
    if(!r.grantId.contains(grant.id) || !r.grantOwner.contains(grant.owner))throw new CdcPlatformException("STALE_GRANT")
    r
  }
  private[cdc] def continuation(grant:CdcTaskGrant):Option[BootstrapProgress]=continuationWithProvenance(grant)._1
  /** Cross-owner evidence remains a candidate until Runner compares it with persisted Journal. */
  private[cdc] def continuationWithProvenance(grant:CdcTaskGrant):(Option[BootstrapProgress],Boolean)=connection {c=>
    val r=query(c,"SELECT continuation_json,continuation_owner FROM pekko_cdc_tasks_v1 WHERE task_id=? AND grant_id=? AND grant_owner=?",grant.task.view.taskId,grant.id,grant.owner).head
    val previous=Option(r("continuation_owner"))
    val same=previous.contains(grant.owner)
    val inherited=previous.exists {p=>
      query(c,"SELECT previous.lineage FROM pekko_cdc_clean_owner_v1 previous JOIN pekko_cdc_clean_owner_v1 current_owner ON previous.lineage=current_owner.lineage WHERE previous.owner_attempt=? AND previous.clean=TRUE AND current_owner.owner_attempt=?",p,grant.owner).nonEmpty
    }
    (if(same || inherited)Option(r("continuation_json")).map(CdcProgressJson.decode)else None,same)
  }
  private[cdc] def finish(grant:CdcTaskGrant,progress:BootstrapProgress,initialization:Option[String],readiness:String,
    didWork:Boolean,validationComplete:Boolean,delay:Long,now:Long):Unit=transaction {c=>
    val old=requireGrant(c,grant)
    val canceled=old.view.desiredState=="CANCELED"
    val phase=if(canceled && !Set("PAUSED","CANCELED").contains(progress.phase))"CANCELING"else progress.phase
    update(c,"UPDATE pekko_cdc_grants_v1 SET status='RELEASED',finished_at=? WHERE grant_id=? AND status='ACTIVE'",now,grant.id)
    update(c,"UPDATE pekko_cdc_tasks_v1 SET phase=?,journal_revision=?,execution_id=?,capture_readiness=?,initialization_evidence=?,runtime_health=?,release_status='RELEASED',failure_code=?,observed_at=?,last_verified_at=?,due_at=?,grant_id=NULL,grant_owner=NULL,continuation_json=?,continuation_owner=?,work_pool=?,schedulable=?,delta_after=? WHERE task_id=?",
      phase,progress.revision,Option(progress.executionId).filter(_.nonEmpty).orNull,readiness,initialization.orElse(old.view.initializationEvidence).orNull,
      if(phase=="PAUSED")"PAUSED"else if(phase=="CANCELED")"STOPPED"else if(validationComplete && progress.phase=="STREAMING")"HEALTHY"else"VERIFYING",
      progress.failure.orNull,now,if(validationComplete)Long.box(now)else old.view.lastVerifiedAt.map(Long.box).orNull,now+math.max(0L,delay),
      CdcProgressJson.encode(progress),grant.owner,if(progress.phase=="STREAMING" || (phase=="CANCELING" && old.workPool=="TAIL"))"TAIL"else"SNAPSHOT",!Set("PAUSED","CANCELED").contains(phase) && (phase!="STREAMING" || !validationComplete || canceled || old.wakeSequence>progress.deltaAfter),progress.deltaAfter,old.view.taskId)
  }
  private[cdc] def defer(grant:CdcTaskGrant,delay:Long,now:Long):Unit=transaction {c=>
    val old=requireGrant(c,grant)
    update(c,"UPDATE pekko_cdc_grants_v1 SET status='RELEASED',finished_at=? WHERE grant_id=?",now,grant.id)
    update(c,"UPDATE pekko_cdc_tasks_v1 SET grant_id=NULL,grant_owner=NULL,release_status='RELEASED',runtime_health='WAITING',due_at=?,schedulable=TRUE WHERE task_id=?",now+delay,old.view.taskId)
  }
  private[cdc] def finishFailureReleased(grant:CdcTaskGrant,code:String,now:Long):Unit=transaction {c=>
    requireGrant(c,grant)
    update(c,"UPDATE pekko_cdc_grants_v1 SET status='RELEASED',finished_at=? WHERE grant_id=?",now,grant.id)
    update(c,"UPDATE pekko_cdc_tasks_v1 SET phase='PAUSED',runtime_health='PAUSED',release_status='RELEASED',failure_code=?,observed_at=?,schedulable=FALSE,grant_id=NULL,grant_owner=NULL WHERE task_id=?",code,now,grant.task.view.taskId)
  }
  private[cdc] def finishUnknown(grant:CdcTaskGrant,code:String,now:Long):Unit=transaction {c=>
    val current=owner(c)
    requireGrant(c,grant)
    update(c,"UPDATE pekko_cdc_grants_v1 SET status='UNKNOWN' WHERE grant_id=?",grant.id)
    update(c,"UPDATE pekko_cdc_tasks_v1 SET phase='PAUSED',runtime_health='UNKNOWN',release_status='UNKNOWN',failure_code=?,observed_at=?,schedulable=FALSE WHERE task_id=?",code,now,grant.task.view.taskId)
    if(current("owner_attempt")==grant.owner && managedOn(c,grant.owner).nonEmpty)
      update(c,"UPDATE pekko_cdc_owner_v1 SET status='UNKNOWN',updated_at=? WHERE platform_id='default'",now)
  }
  /** Bounded scan of new immutable L frames, not a poll of every subscribed table. */
  def dispatch(attempt:String,captures:Set[String],maxFrames:Int,now:Long):CdcDispatchResult=transaction {c=>
    require(captures.size<=32 && maxFrames>=math.max(1,captures.size) && maxFrames<=128)
    val own=owner(c)
    if(own("owner_attempt")!=attempt || own("status")!="ACTIVE")throw new CdcPlatformException("OWNER_RELEASE_UNPROVEN")
    var frames=0;var caughtUp=true
    val budget=math.max(1,maxFrames/math.max(1,captures.size))
    captures.toVector.sorted.foreach {capture=>
      val previous=query(c,"SELECT after_seq FROM pekko_cdc_dispatch_v1 WHERE capture_id=? FOR UPDATE",capture).headOption
      val after=previous.map(_("after_seq").toLong).getOrElse(0L)
      if(previous.isEmpty)update(c,"INSERT INTO pekko_cdc_dispatch_v1 VALUES(?,0)",capture)
      val entries=query(c,"SELECT seq,kind,subscription_id FROM pekko_capture_log WHERE capture_id=? AND seq>? ORDER BY seq LIMIT ?",capture,after,budget)
      frames+=entries.size
      if(entries.size==budget)caughtUp=false
      entries.filter(_("kind")=="GROUP").foreach {entry=>
        val sequence=entry("seq").toLong
        update(c,"UPDATE pekko_cdc_tasks_v1 SET wake_seq=CASE WHEN wake_seq<? THEN ? ELSE wake_seq END,schedulable=CASE WHEN phase='STREAMING' AND grant_id IS NULL AND delta_after<? THEN TRUE ELSE schedulable END,due_at=CASE WHEN phase='STREAMING' AND delta_after<? THEN ? ELSE due_at END WHERE task_id=? AND capture_id=? AND phase NOT IN ('PAUSED','CANCELED')",
          sequence,sequence,sequence,sequence,now,entry("subscription_id"),capture)
      }
      entries.lastOption.foreach(e=>update(c,"UPDATE pekko_cdc_dispatch_v1 SET after_seq=? WHERE capture_id=?",e("seq").toLong,capture))
    }
    CdcDispatchResult(frames,caughtUp)
  }
  /** Controlled owner restart: activate only a bounded metadata page, never all Actors. */
  def recoverColdPage(attempt:String,after:Option[String],limit:Int,now:Long):Option[String]=transaction {c=>
    require(limit>0 && limit<=128 && after.forall(_.matches("[a-f0-9]{64}")))
    val own=owner(c)
    if(own("owner_attempt")!=attempt || own("status")!="ACTIVE")throw new CdcPlatformException("OWNER_RELEASE_UNPROVEN")
    val page=query(c,"SELECT task_id FROM pekko_cdc_tasks_v1 WHERE task_id>? ORDER BY task_id LIMIT ?",after.getOrElse(""),limit+1)
    val ids=page.take(limit).map(_("task_id"))
    if(ids.nonEmpty)update(c,"UPDATE pekko_cdc_tasks_v1 SET schedulable=TRUE,due_at=?,runtime_health='VERIFYING' WHERE task_id IN ("+ids.map(_=>"?").mkString(",")+") AND grant_id IS NULL AND phase NOT IN ('PAUSED','CANCELED')",(Vector[Any](now)++ids):_*)
    if(page.size>limit)ids.lastOption else None
  }
  def grants:Vector[(String,String,String)]=connection(c=>query(c,"SELECT grant_id,owner_attempt,status FROM pekko_cdc_grants_v1 WHERE status IN ('ACTIVE','UNKNOWN') ORDER BY grant_id LIMIT 128").map(r=>(r("grant_id"),r("owner_attempt"),r("status"))))
  def counts:Map[String,Long]=connection(c=>query(c,"SELECT status,COUNT(*) AS n FROM pekko_cdc_grants_v1 WHERE status IN ('ACTIVE','UNKNOWN') GROUP BY status").map(r=>r("status")->r("n").toLong).toMap)
}
