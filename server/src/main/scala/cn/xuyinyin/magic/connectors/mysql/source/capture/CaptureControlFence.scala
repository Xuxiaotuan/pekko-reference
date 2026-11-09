package cn.xuyinyin.magic.connectors.mysql.source.capture

import java.sql.Connection

/** Persisted source-domain fencing identity. It is data, not an isolation proof. */
final case class CaptureFenceBinding(sourceUuid:String,database:String,epoch:Long,ownerToken:String,
  version:Long,operation:String,action:String,table:String,generation:String) {
  override def toString:String=s"CaptureFenceBinding($sourceUuid,$database,$epoch,<redacted>,$version,$operation,$action,$table,$generation)"
}

/** A database marker observation; successful observation alone does not prove guardian isolation. */
final case class CaptureFenceReceipt(binding:CaptureFenceBinding,versionAfter:Long,markerCreated:Boolean)

final case class CaptureFenceObservation(sourceUuid:String,database:String,epoch:Long,ownerToken:String,phase:String,
  version:Long,operation:String,action:String,table:String,generation:String) {
  override def toString:String=s"CaptureFenceObservation($sourceUuid,$database,$epoch,<redacted>,$phase,$version,$operation,$action,$table,$generation)"
}

/** Source transaction primitive. Its result alone is not a guardian or D/Journal proof. */
object CaptureControlFence {
  final class VerifiedSourceFence private[CaptureControlFence](val binding:CaptureFenceBinding,val markerPresent:Boolean,
    val account:String,val endpoint:String,val sourceUuid:String,val database:String,
    active:java.util.concurrent.atomic.AtomicBoolean,check:()=>Unit) {
    def requireLive():Unit={require(active.get(),"source fence scope ended");check();require(active.get(),"source fence scope ended")}
    override def toString:String=s"VerifiedSourceFence(<redacted>,$account,$endpoint)"
  }
  /** Scoped source observation; caller must additionally verify guardian and durable intent. */
  final class VerifiedSourceReceipt private[CaptureControlFence](val receipt:CaptureFenceReceipt,val account:String,
    val endpoint:String,val sourceUuid:String,val database:String,
    active:java.util.concurrent.atomic.AtomicBoolean,check:()=>Unit) {
    def requireLive():Unit={require(active.get(),"source receipt scope ended");check();require(active.get(),"source receipt scope ended")}
    override def toString:String=s"VerifiedSourceReceipt(<redacted>,$account)"
  }
  final class VerifiedSourceRelease private[CaptureControlFence](val receipt:CaptureFenceReceipt,val permitId:String,val stateDigest:String,
    val account:String,val endpoint:String,val sourceUuid:String,val database:String,
    active:java.util.concurrent.atomic.AtomicBoolean,check:()=>Unit) {
    def requireLive():Unit={require(active.get(),"source release scope ended");check();require(active.get(),"source release scope ended")}
    override def toString:String=s"VerifiedSourceRelease(<redacted>,$account,$endpoint)"
  }
  val FenceTable="pekko_capture_control_fence_v1"
  val ReleaseTable="pekko_capture_control_release_v1"
  val ReleaseTableDdl:String="CREATE TABLE pekko_capture_control_release_v1(epoch BIGINT PRIMARY KEY,source_uuid CHAR(36) NOT NULL,database_name VARCHAR(64) NOT NULL,receipt_hash CHAR(64) NOT NULL,version_after BIGINT NOT NULL,marker_created BOOLEAN NOT NULL,permit_id VARCHAR(128) NOT NULL,state_digest CHAR(64) NOT NULL) ENGINE=InnoDB"
  private def releaseHash(r:CaptureFenceReceipt):String={
    val b=r.binding;val bytes=new java.io.ByteArrayOutputStream();val out=new java.io.DataOutputStream(bytes)
    Vector(b.sourceUuid,b.database,b.epoch.toString,b.ownerToken,b.version.toString,b.operation,b.action,b.table,b.generation,
      r.versionAfter.toString,r.markerCreated.toString).foreach {v=>val encoded=v.getBytes(java.nio.charset.StandardCharsets.UTF_8);out.writeInt(encoded.length);out.write(encoded)}
    out.flush();java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray).map(x=>f"${x & 0xff}%02x").mkString
  }
  private def releaseParameters(r:CaptureFenceReceipt,permitId:String,stateDigest:String):Vector[Any]={
    require(r!=null && permitId!=null && permitId.matches("[A-Za-z0-9_-]{1,128}") && stateDigest!=null && stateDigest.matches("[a-f0-9]{64}"),"invalid release receipt identity")
    validateIdentity(r.binding)
    Vector(r.binding.epoch,r.binding.sourceUuid,r.binding.database,releaseHash(r),r.versionAfter,if(r.markerCreated)1 else 0,permitId,stateDigest)
  }
  private def jdbcEndpoint(c:Connection):String={
    val url=c.getMetaData.getURL
    require(url!=null && url.startsWith("jdbc:mysql://"),"unsupported source JDBC endpoint")
    val uri=java.net.URI.create(url.stripPrefix("jdbc:"))
    val host=Option(uri.getHost).filter(_.nonEmpty).getOrElse(throw new IllegalArgumentException("source JDBC host missing"))
    val port=if(uri.getPort<0)3306 else uri.getPort
    require(port>0 && port<=65535,"source JDBC port invalid")
    host.toLowerCase(java.util.Locale.ROOT)+":"+port
  }
  val OpenProcedure="pekko_control_write_open_v1"
  private val OpenProcedureBody="""BEGIN
    DECLARE f_phase VARCHAR(16) DEFAULT NULL; DECLARE existing_rows BIGINT DEFAULT 0; DECLARE exact_rows BIGINT DEFAULT 0;
    SELECT phase INTO f_phase FROM pekko_capture_control_fence_v1 WHERE domain_id='default' FOR UPDATE;
    IF f_phase IS NULL OR f_phase<>'OPEN' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CONTROL_FENCED'; END IF;
    SELECT COUNT(*) INTO existing_rows FROM pekko_capture_control WHERE operation_id=p_operation;
    SELECT COUNT(*) INTO exact_rows FROM pekko_capture_control WHERE operation_id=p_operation AND BINARY operation_id=BINARY p_operation AND BINARY action=BINARY p_action AND BINARY table_name=BINARY p_table AND BINARY generation=BINARY p_generation;
    IF existing_rows>0 AND exact_rows<>existing_rows THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CONTROL_MARKER_CONFLICT'; END IF;
    IF existing_rows=0 THEN INSERT INTO pekko_capture_control VALUES(p_operation,p_action,p_table,p_generation); END IF;
  END"""
  /** Operator-provisioned procedure. A trusted definer and stable grants are required. */
  val OpenProcedureDdl:String="CREATE PROCEDURE "+OpenProcedure+"(IN p_operation VARCHAR(128),IN p_action VARCHAR(32),IN p_table VARCHAR(128),IN p_generation VARCHAR(128)) SQL SECURITY DEFINER "+OpenProcedureBody
  private val InsertBody="""BEGIN
    DECLARE f_phase VARCHAR(16) DEFAULT NULL;
    DECLARE f_op VARCHAR(128); DECLARE f_action VARCHAR(32); DECLARE f_table VARCHAR(128); DECLARE f_generation VARCHAR(128);
    DECLARE existing_rows BIGINT DEFAULT 0; DECLARE exact_rows BIGINT DEFAULT 0;
    IF @@transaction_isolation <> 'READ-COMMITTED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CONTROL_ISOLATION_INVALID'; END IF;
    SELECT phase,operation_id,action,table_name,generation INTO f_phase,f_op,f_action,f_table,f_generation
      FROM pekko_capture_control_fence_v1 WHERE domain_id='default' FOR UPDATE;
    IF f_phase IS NULL OR f_phase NOT IN ('OPEN','FENCED') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CONTROL_FENCE_INVALID'; END IF;
    IF f_phase='FENCED' AND NOT (BINARY NEW.operation_id=BINARY f_op AND BINARY NEW.action=BINARY f_action AND BINARY NEW.table_name=BINARY f_table AND BINARY NEW.generation=BINARY f_generation) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CONTROL_FENCED'; END IF;
    SELECT COUNT(*) INTO existing_rows FROM pekko_capture_control WHERE operation_id=NEW.operation_id;
    SELECT COUNT(*) INTO exact_rows FROM pekko_capture_control WHERE operation_id=NEW.operation_id AND BINARY operation_id=BINARY NEW.operation_id AND BINARY action=BINARY NEW.action AND BINARY table_name=BINARY NEW.table_name AND BINARY generation=BINARY NEW.generation;
    IF existing_rows>0 AND exact_rows<>existing_rows THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CONTROL_MARKER_CONFLICT'; END IF;
    IF existing_rows=0 THEN UPDATE pekko_capture_control_fence_v1 SET version=version+1 WHERE domain_id='default'; END IF;
  END"""
  private val UpdateBody="""BEGIN
    DECLARE f_phase VARCHAR(16) DEFAULT NULL;
    SELECT phase INTO f_phase FROM pekko_capture_control_fence_v1 WHERE domain_id='default' FOR UPDATE;
    IF f_phase IS NULL OR f_phase NOT IN ('OPEN','FENCED') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CONTROL_FENCE_INVALID'; END IF;
    IF NOT (BINARY OLD.operation_id=BINARY NEW.operation_id AND BINARY OLD.action=BINARY NEW.action AND BINARY OLD.table_name=BINARY NEW.table_name AND BINARY OLD.generation=BINARY NEW.generation) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CONTROL_IMMUTABLE'; END IF;
  END"""
  private val DeleteBody="""BEGIN
    DECLARE f_phase VARCHAR(16) DEFAULT NULL;
    SELECT phase INTO f_phase FROM pekko_capture_control_fence_v1 WHERE domain_id='default' FOR UPDATE;
    IF f_phase IS NULL OR f_phase<>'OPEN' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CONTROL_FENCED'; END IF;
    UPDATE pekko_capture_control_fence_v1 SET version=version+1 WHERE domain_id='default';
  END"""
  /** DDL for an operator-controlled migration only; never run automatically on a retained source. */
  val TriggerDdl:Vector[String]=Vector(
    "CREATE TRIGGER pekko_control_fence_bi BEFORE INSERT ON pekko_capture_control FOR EACH ROW "+InsertBody,
    "CREATE TRIGGER pekko_control_fence_bu BEFORE UPDATE ON pekko_capture_control FOR EACH ROW "+UpdateBody,
    "CREATE TRIGGER pekko_control_fence_bd BEFORE DELETE ON pekko_capture_control FOR EACH ROW "+DeleteBody)
  private def normalizeSql(s:String):String=Option(s).getOrElse("").trim.replaceAll("\\s+"," ")
  private[capture] def safeWriterGrants(grants:Vector[String],database:String):Boolean={
    val global=Set("USAGE","SELECT","RELOAD","SHOW DATABASES","REPLICATION SLAVE","REPLICATION CLIENT")
    val schema=Set("SELECT","SHOW VIEW")
    val procedure=Set("EXECUTE")
    grants.nonEmpty && grants.forall {raw=>
      val grant=Option(raw).getOrElse("").trim.toUpperCase(java.util.Locale.ROOT)
      val on=grant.indexOf(" ON ");val to=grant.indexOf(" TO ",math.max(on,0))
      if(!grant.startsWith("GRANT ") || on<0 || to<0 || grant.contains("WITH GRANT OPTION"))false
      else {
        val privileges=grant.substring(6,on).split(",").map(_.trim).toSet
        val scope=grant.substring(on+4,to).replace("`","").trim
        val db=database.toUpperCase(java.util.Locale.ROOT)
        val allowed=if(scope=="*.*")global else if(scope=="PROCEDURE "+db+"."+OpenProcedure.toUpperCase(java.util.Locale.ROOT))procedure
          else if(scope==db+".*")schema else Set("SELECT")
        privileges.nonEmpty && privileges.subsetOf(allowed)
      }
    }
  }
  private[capture] def safeControllerGrants(grants:Vector[String],database:String):Boolean={
    grants.nonEmpty && grants.forall {raw=>
      val grant=Option(raw).getOrElse("").trim.toUpperCase(java.util.Locale.ROOT)
      val on=grant.indexOf(" ON ");val to=grant.indexOf(" TO ",math.max(on,0))
      if(!grant.startsWith("GRANT ") || on<0 || to<0 || grant.contains("WITH GRANT OPTION"))false
      else {
        val privileges=grant.substring(6,on).split(",").map(_.trim).toSet
        val scope=grant.substring(on+4,to).replace("`","").trim
        val db=database.toUpperCase(java.util.Locale.ROOT)
        val allowed=if(scope=="*.*")Set("USAGE") else if(scope==db+".*")Set("SELECT")
          else if(scope==db+".PEKKO_CAPTURE_CONTROL")Set("SELECT","INSERT","UPDATE")
          else if(scope==db+".PEKKO_CAPTURE_CONTROL_FENCE_V1")Set("SELECT","UPDATE")
          else if(scope==db+".PEKKO_CAPTURE_CONTROL_RELEASE_V1")Set("SELECT","INSERT") else Set.empty[String]
        privileges.nonEmpty && privileges.subsetOf(allowed)
      }
    }
  }
  private def query(c:Connection,sql:String,args:Seq[Any]=Seq.empty):Vector[Vector[String]]={
    val q=c.prepareStatement(sql);q.setQueryTimeout(5)
    try {args.zipWithIndex.foreach {case(v,i)=>q.setObject(i+1,v)}
      val rs=q.executeQuery();val result=Vector.newBuilder[Vector[String]]
      try {val n=rs.getMetaData.getColumnCount;while(rs.next())result+=(1 to n).map(rs.getString).toVector}finally rs.close()
      result.result()
    }finally q.close()
  }
  private def update(c:Connection,sql:String,args:Seq[Any]):Int={
    val q=c.prepareStatement(sql);q.setQueryTimeout(5)
    try {args.zipWithIndex.foreach {case(v,i)=>q.setObject(i+1,v)};q.executeUpdate()}finally q.close()
  }
  private def validateIdentity(b:CaptureFenceBinding):Unit={
    require(b!=null && b.sourceUuid!=null && b.sourceUuid.matches("[a-fA-F0-9-]{36}") &&
      b.database!=null && b.database.matches("[A-Za-z_][A-Za-z0-9_]*") && b.epoch>0 && b.version>=0 &&
      b.ownerToken!=null && b.ownerToken.matches("[a-f0-9]{64}") && b.operation!=null && b.operation.matches("[a-f0-9]{64}") &&
      Set("SUBSCRIBE","STOP","LOW","HIGH","F","WAKE")(b.action) && b.table!=null && b.table.nonEmpty && b.table.length<=128 &&
      b.generation!=null && b.generation.nonEmpty && b.generation.length<=128,"invalid source fence binding")
  }
  /** Fail closed if operator-provisioned protection was dropped, altered, or supplemented. */
  def verifyProtection(c:Connection,sourceUuid:String,database:String):Unit={
    require(c!=null && !c.getAutoCommit && database!=null && database.matches("[A-Za-z_][A-Za-z0-9_]*"),"source protection requires a transaction and valid database")
    require(query(c,"SELECT @@server_uuid,DATABASE()")==Vector(Vector(sourceUuid,database)),"source protection identity changed")
    // Hold a metadata lock on the controlled table through the caller's transaction.
    // The caller must use a trusted observer able to inspect trigger definitions.
    query(c,"SELECT operation_id FROM pekko_capture_control LIMIT 1")
    query(c,"SELECT domain_id FROM pekko_capture_control_fence_v1 LIMIT 1")
    query(c,"SELECT epoch FROM pekko_capture_control_release_v1 LIMIT 1")
    val tables=query(c,"SELECT table_name,engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('pekko_capture_control','pekko_capture_control_fence_v1','pekko_capture_control_release_v1') ORDER BY table_name")
    require(tables==Vector(Vector("pekko_capture_control","InnoDB"),Vector("pekko_capture_control_fence_v1","InnoDB"),Vector("pekko_capture_control_release_v1","InnoDB")),"source protection tables missing or not InnoDB")
    val actualColumns=query(c,"SELECT table_name,column_name,column_type,is_nullable,column_key FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('pekko_capture_control','pekko_capture_control_fence_v1','pekko_capture_control_release_v1') ORDER BY table_name,ordinal_position")
    val control=Vector(("operation_id","varchar(128)","PRI"),("action","varchar(32)",""),("table_name","varchar(128)",""),("generation","varchar(128)",""))
    val fence=Vector(("domain_id","varchar(16)","PRI"),("source_uuid","char(36)",""),("database_name","varchar(64)",""),("epoch","bigint",""),("owner_token","char(64)",""),("phase","varchar(16)",""),("version","bigint",""),("operation_id","varchar(128)",""),("action","varchar(32)",""),("table_name","varchar(128)",""),("generation","varchar(128)",""))
    val release=Vector(("epoch","bigint","PRI"),("source_uuid","char(36)",""),("database_name","varchar(64)",""),("receipt_hash","char(64)",""),("version_after","bigint",""),("marker_created","tinyint(1)",""),("permit_id","varchar(128)",""),("state_digest","char(64)",""))
    val expectedColumns=Vector("pekko_capture_control"->control,"pekko_capture_control_fence_v1"->fence,"pekko_capture_control_release_v1"->release).flatMap {case(table,columns)=>
      columns.map {case(name,kind,key)=>Vector(table,name,kind,"NO",key)}}
    require(actualColumns==expectedColumns,"source protection table schema changed")
    val actual=query(c,"SELECT trigger_name,event_manipulation,action_timing,action_statement FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND event_object_table='pekko_capture_control' ORDER BY trigger_name")
    val expected=Vector(Vector("pekko_control_fence_bd","DELETE","BEFORE",DeleteBody),Vector("pekko_control_fence_bi","INSERT","BEFORE",InsertBody),Vector("pekko_control_fence_bu","UPDATE","BEFORE",UpdateBody))
    require(actual.size==expected.size && actual.zip(expected).forall {case(a,e)=>a.take(3)==e.take(3) && normalizeSql(a(3))==normalizeSql(e(3))},"source protection triggers missing or changed")
    require(query(c,"SELECT trigger_name FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND event_object_table='pekko_capture_control_release_v1'").isEmpty,"source release receipt table has unexpected triggers")
    val routine=query(c,"SELECT security_type,routine_definition,definer FROM information_schema.routines WHERE routine_schema=DATABASE() AND routine_type='PROCEDURE' AND routine_name=?",Seq(OpenProcedure))
    val current=query(c,"SELECT CURRENT_USER()")
    require(routine.size==1 && current.size==1 && routine.head.size==3 && routine.head(0)=="DEFINER" &&
      normalizeSql(routine.head(1))==normalizeSql(OpenProcedureBody) && routine.head(2)==current.head.head,
      "source open procedure missing, changed, or untrusted definer")
    val parameters=query(c,"SELECT parameter_mode,parameter_name,dtd_identifier FROM information_schema.parameters WHERE specific_schema=DATABASE() AND specific_name=? ORDER BY ordinal_position",Seq(OpenProcedure))
    require(parameters==Vector(Vector("IN","p_operation","varchar(128)"),Vector("IN","p_action","varchar(32)"),
      Vector("IN","p_table","varchar(128)"),Vector("IN","p_generation","varchar(128)")),"source open procedure parameters changed")
  }
  /** Observer checks direct grants; roles and unrecognized privileges fail closed. */
  def verifyWriterPrivileges(observer:Connection,account:String,database:String):Unit={
    require(safeWriterGrants(accountGrants(observer,account,database),database),"source writer grants do not protect source fence")
  }
  private def accountGrants(observer:Connection,account:String,database:String):Vector[String]={
    require(observer!=null && account!=null && database!=null && database.matches("[A-Za-z_][A-Za-z0-9_]*"),"invalid writer account")
    val at=account.lastIndexOf('@');require(at>0 && at<account.length-1,"writer account identity missing")
    require(account.substring(at+1).matches("[A-Za-z0-9.:-]+"),"source account requires an exact host")
    val quoted=query(observer,"SELECT QUOTE(?),QUOTE(?)",Seq(account.substring(0,at),account.substring(at+1)))
    require(quoted.size==1 && quoted.head.size==2 && quoted.head.forall(_!=null),"writer account quoting failed")
    query(observer,"SHOW GRANTS FOR "+quoted.head(0)+"@"+quoted.head(1)).map(_.head)
  }
  private def requireNoRoles(c:Connection):Unit={
    val rows=query(c,"SELECT CURRENT_ROLE(),@@GLOBAL.mandatory_roles")
    require(rows.size==1 && rows.head.size==2 && rows.head(0)=="NONE" && Option(rows.head(1)).contains(""),"source account roles are not permitted")
  }
  /** Guardian isolation and durable permit/receipt checks remain the caller's responsibility. */
  final class ProtectedController private[CaptureControlFence](observer:Connection,controller:Connection,writer:Connection,
    sourceUuid:String,database:String,observerId:String,controllerId:String,writerId:String,writerAccount:String,val account:String,val endpoint:String) {
    private def check():Unit={
      require(!observer.getAutoCommit && !controller.getAutoCommit && observer.getTransactionIsolation==Connection.TRANSACTION_READ_COMMITTED &&
        controller.getTransactionIsolation==Connection.TRANSACTION_READ_COMMITTED,"controller sessions changed transaction mode")
      require(query(observer,"SELECT @@server_uuid,DATABASE(),CONNECTION_ID()")==Vector(Vector(sourceUuid,database,observerId)),"controller observer session changed")
      require(query(controller,"SELECT @@server_uuid,DATABASE(),CONNECTION_ID(),CURRENT_USER()")==Vector(Vector(sourceUuid,database,controllerId,account)),"controller session changed")
      require(query(writer,"SELECT @@server_uuid,DATABASE(),CONNECTION_ID(),CURRENT_USER()")==Vector(Vector(sourceUuid,database,writerId,writerAccount)),"source writer session changed")
      requireNoRoles(writer);requireNoRoles(controller)
      require(jdbcEndpoint(controller)==endpoint,"controller JDBC endpoint changed")
      verifyProtection(observer,sourceUuid,database)
      verifyWriterPrivileges(observer,writerAccount,database)
      require(safeControllerGrants(accountGrants(observer,account,database),database),"controller grants exceed source fence permissions")
    }
    def acquire(binding:CaptureFenceBinding):CaptureFenceBinding={check();acquireChecked(controller,binding)}
    private def writePrimitive(binding:CaptureFenceBinding):CaptureFenceReceipt={check();writeMarker(controller,binding)}
    def write(binding:CaptureFenceBinding,markerPresent:Boolean,proof:cn.xuyinyin.magic.workflow.cdc.CdcTaskStore.MarkerWritePermit):CaptureFenceReceipt={
      require(proof!=null,"marker write permit required");proof.assertBinding(binding,markerPresent);check()
      val receipt=writeMarker(controller,binding)
      require(receipt.markerCreated!=markerPresent,"marker write does not match prior state")
      proof.requireLive();receipt
    }
    private def matchesFence(binding:CaptureFenceBinding,markerPresent:Boolean):Boolean={
      check();lock(controller,binding)
      val expected=Vector(binding.sourceUuid,binding.database,binding.epoch.toString,binding.ownerToken,"FENCED",binding.version.toString,
        binding.operation,binding.action,binding.table,binding.generation)
      val committed=query(observer,"SELECT source_uuid,database_name,epoch,owner_token,phase,version,operation_id,action,table_name,generation FROM pekko_capture_control_fence_v1 WHERE domain_id='default'")
      val sql="SELECT operation_id,action,table_name,generation FROM pekko_capture_control WHERE operation_id=?"
      val marker=if(markerPresent)Vector(Vector(binding.operation,binding.action,binding.table,binding.generation))else Vector.empty
      committed==Vector(expected) && query(observer,sql,Seq(binding.operation))==marker && query(controller,sql+" FOR UPDATE",Seq(binding.operation))==marker
    }
    def withVerifiedFence[A](binding:CaptureFenceBinding,markerPresent:Boolean)(f:VerifiedSourceFence=>A):A={
      require(matchesFence(binding,markerPresent),"source fence or marker before-image changed")
      val active=new java.util.concurrent.atomic.AtomicBoolean(true)
      val proof=new VerifiedSourceFence(binding,markerPresent,account,endpoint,sourceUuid,database,active,
        ()=>require(matchesFence(binding,markerPresent),"source fence or marker before-image changed"))
      try f(proof) finally active.set(false)
    }
    def observe():CaptureFenceObservation={check();observeChecked(controller,sourceUuid,database)}
    /** Reads exact committed source state; markerCreated cannot be reconstructed from a readback. */
    def matches(receipt:CaptureFenceReceipt):Boolean={
      check();require(receipt!=null,"receipt required")
      val b=receipt.binding.copy(version=receipt.versionAfter);lock(controller,b)
      val expected=Vector(b.sourceUuid,b.database,b.epoch.toString,b.ownerToken,"FENCED",b.version.toString,b.operation,b.action,b.table,b.generation)
      val committed=query(observer,"SELECT source_uuid,database_name,epoch,owner_token,phase,version,operation_id,action,table_name,generation FROM pekko_capture_control_fence_v1 WHERE domain_id='default'")
      val marker=Vector(Vector(b.operation,b.action,b.table,b.generation))
      committed==Vector(expected) && query(observer,"SELECT operation_id,action,table_name,generation FROM pekko_capture_control WHERE operation_id=?",Seq(b.operation))==marker &&
        query(controller,"SELECT operation_id,action,table_name,generation FROM pekko_capture_control WHERE operation_id=? FOR UPDATE",Seq(b.operation))==marker
    }
    /** Callback must not concurrently commit/close either session; the proof expires on return. */
    def withVerifiedReceipt[A](receipt:CaptureFenceReceipt)(f:VerifiedSourceReceipt=>A):A={
      require(matches(receipt),"source receipt does not match")
      val active=new java.util.concurrent.atomic.AtomicBoolean(true)
      val proof=new VerifiedSourceReceipt(receipt,account,endpoint,sourceUuid,database,active,()=>require(matches(receipt),"source receipt changed"))
      try f(proof) finally active.set(false)
    }
    /** Source mutation is authorized only while the state/Journal permit locks remain held. */
    def release(receipt:CaptureFenceReceipt,proof:cn.xuyinyin.magic.workflow.cdc.CdcTaskStore.ConsumedPermit):Unit={
      require(proof!=null,"consumed permit required");proof.assertReceipt(receipt);check()
      releaseChecked(controller,receipt,proof.permitId,proof.receiptDigest)
      proof.assertReceipt(receipt)
    }
    /** Low-level fixture access; never constitutes release authorization. */
    private def releasePrimitive(receipt:CaptureFenceReceipt,permitId:String,stateDigest:String):Unit={check();releaseChecked(controller,receipt,permitId,stateDigest)}
    def matchesReleased(receipt:CaptureFenceReceipt,permitId:String,stateDigest:String):Boolean={
      check();val expected=releaseParameters(receipt,permitId,stateDigest).map(_.toString)
      val current=observeChecked(controller,sourceUuid,database)
      (current.epoch>receipt.binding.epoch || (current.epoch==receipt.binding.epoch && current.phase=="OPEN")) &&
        query(controller,"SELECT epoch,source_uuid,database_name,receipt_hash,version_after,marker_created,permit_id,state_digest FROM pekko_capture_control_release_v1 WHERE epoch=?",Seq(receipt.binding.epoch))==Vector(expected) &&
        query(observer,"SELECT epoch,source_uuid,database_name,receipt_hash,version_after,marker_created,permit_id,state_digest FROM pekko_capture_control_release_v1 WHERE epoch=?",Seq(receipt.binding.epoch))==Vector(expected)
    }
    /** Source observation only; caller also verifies state consume and guardian attestation. */
    def withVerifiedRelease[A](receipt:CaptureFenceReceipt,permitId:String,stateDigest:String)(f:VerifiedSourceRelease=>A):A={
      require(matchesReleased(receipt,permitId,stateDigest),"source release does not match")
      val active=new java.util.concurrent.atomic.AtomicBoolean(true)
      val proof=new VerifiedSourceRelease(receipt,permitId,stateDigest,account,endpoint,sourceUuid,database,active,
        ()=>require(matchesReleased(receipt,permitId,stateDigest),"source release changed"))
      try f(proof) finally active.set(false)
    }
  }
  /** Raw connection identities are not guardian attestation and cannot authorize a permit. */
  def protectController(observer:Connection,controller:Connection,sourceUuid:String,database:String,writer:Connection):ProtectedController={
    require(observer!=null && controller!=null && writer!=null && (observer ne controller) && (observer ne writer) && (controller ne writer) &&
      !observer.getAutoCommit && !controller.getAutoCommit && observer.getTransactionIsolation==Connection.TRANSACTION_READ_COMMITTED &&
      controller.getTransactionIsolation==Connection.TRANSACTION_READ_COMMITTED,"controller requires distinct live transactions")
    val o=query(observer,"SELECT @@server_uuid,DATABASE(),CONNECTION_ID(),CURRENT_USER()")
    val c=query(controller,"SELECT @@server_uuid,DATABASE(),CONNECTION_ID(),CURRENT_USER()")
    val w=query(writer,"SELECT @@server_uuid,DATABASE(),CONNECTION_ID(),CURRENT_USER()")
    require(o.size==1 && c.size==1 && w.size==1 && o.head.take(2)==Vector(sourceUuid,database) && c.head.take(2)==Vector(sourceUuid,database) &&
      w.head.take(2)==Vector(sourceUuid,database) && Set(o.head(2),c.head(2),w.head(2)).size==3 &&
      Set(o.head(3),c.head(3),w.head(3)).size==3,"controller principal or source identity conflict")
    verifyProtection(observer,sourceUuid,database)
    requireNoRoles(writer);requireNoRoles(controller)
    verifyWriterPrivileges(observer,w.head(3),database)
    require(safeControllerGrants(accountGrants(observer,c.head(3),database),database),"controller grants exceed source fence permissions")
    new ProtectedController(observer,controller,writer,sourceUuid,database,o.head(2),c.head(2),w.head(2),w.head(3),c.head(3),jdbcEndpoint(controller))
  }
  /** Lock and observe the domain; observation is not a guardian/drain proof. */
  def observe(c:Connection,sourceUuid:String,database:String):CaptureFenceObservation={
    require(c!=null && !c.getAutoCommit && c.getTransactionIsolation==Connection.TRANSACTION_READ_COMMITTED,"fence observation needs a READ COMMITTED transaction")
    verifyProtection(c,sourceUuid,database)
    observeChecked(c,sourceUuid,database)
  }
  private def observeChecked(c:Connection,sourceUuid:String,database:String):CaptureFenceObservation={
    val rows=query(c,"SELECT source_uuid,database_name,epoch,owner_token,phase,version,operation_id,action,table_name,generation FROM pekko_capture_control_fence_v1 WHERE domain_id='default' FOR UPDATE")
    require(rows.size==1 && rows.head.size==10 && rows.head.forall(_!=null),"source fence observation missing")
    val r=rows.head
    val epoch=try r(2).toLong catch {case _:NumberFormatException=>throw new IllegalArgumentException("invalid source fence epoch")}
    val version=try r(5).toLong catch {case _:NumberFormatException=>throw new IllegalArgumentException("invalid source fence version")}
    require(r(0)==sourceUuid && r(1)==database && epoch>=0 && version>=0 && Set("OPEN","FENCED")(r(4)),"source fence observation invalid")
    if(r(4)=="OPEN")require(Vector(r(3),r(6),r(7),r(8),r(9)).forall(_.isEmpty),"open source fence retains a recovery binding")
    else validateIdentity(CaptureFenceBinding(r(0),r(1),epoch,r(3),version,r(6),r(7),r(8),r(9)))
    CaptureFenceObservation(r(0),r(1),epoch,r(3),r(4),version,r(6),r(7),r(8),r(9))
  }
  /** A live observer/writer pair, bound to actual MySQL session identities. */
  final class ProtectedDomain private[CaptureControlFence] (observer:Connection,writer:Connection,
    sourceUuid:String,database:String,observerId:String,writerId:String,writerAccount:String) {
    private def check():Unit={
      require(!observer.getAutoCommit && !writer.getAutoCommit && writer.getTransactionIsolation==Connection.TRANSACTION_READ_COMMITTED,
        "protected source sessions changed transaction mode")
      require(query(observer,"SELECT @@server_uuid,DATABASE(),CONNECTION_ID()")==Vector(Vector(sourceUuid,database,observerId)),"source observer session changed")
      require(query(writer,"SELECT @@server_uuid,DATABASE(),CONNECTION_ID(),CURRENT_USER()")==Vector(Vector(sourceUuid,database,writerId,writerAccount)),"source writer session changed")
      requireNoRoles(writer)
      verifyProtection(observer,sourceUuid,database)
      verifyWriterPrivileges(observer,writerAccount,database)
    }
    /** The observer's metadata lock must remain held until the writer commits or rolls back. */
    def writeOpen(operation:String,action:String,table:String,generation:String):Unit={
      check()
      val q=writer.prepareCall("CALL "+OpenProcedure+"(?,?,?,?)");q.setQueryTimeout(5)
      try {Vector(operation,action,table,generation).zipWithIndex.foreach {case(value,index)=>q.setString(index+1,value)};q.execute();()}finally q.close()
    }
    def writeFenced(b:CaptureFenceBinding):CaptureFenceReceipt=throw new IllegalStateException("trusted managed controller is not integrated")
  }
  /** The observer must be trusted; source writer must have no trigger/guard-table mutation grants. */
  def protect(observer:Connection,writer:Connection,sourceUuid:String,database:String):ProtectedDomain={
    require(observer!=null && writer!=null && (observer ne writer) && !observer.getAutoCommit && !writer.getAutoCommit &&
      writer.getTransactionIsolation==Connection.TRANSACTION_READ_COMMITTED,"protected source requires distinct live transactions")
    val a=query(observer,"SELECT @@server_uuid,DATABASE(),CONNECTION_ID(),CURRENT_USER()")
    val b=query(writer,"SELECT @@server_uuid,DATABASE(),CONNECTION_ID(),CURRENT_USER()")
    require(a.size==1 && b.size==1 && a.head.take(2)==Vector(sourceUuid,database) && b.head.take(2)==Vector(sourceUuid,database) &&
      a.head(2)!=b.head(2) && a.head(3)!=b.head(3),"source protection session identity conflict")
    verifyProtection(observer,sourceUuid,database);requireNoRoles(writer);verifyWriterPrivileges(observer,b.head(3),database)
    new ProtectedDomain(observer,writer,sourceUuid,database,a.head(2),b.head(2),b.head(3))
  }
  /**
    * CAS an operator-provisioned source domain into FENCED on the caller's
    * transaction. The caller must first prove guardian isolation, database drain,
    * and effective privileges, and must classify/resolve a lost commit reply.
    * This method returns ordinary data, never an independently trusted proof.
    */
  def acquire(c:Connection,b:CaptureFenceBinding):CaptureFenceBinding={
    validateIdentity(b)
    require(c!=null && !c.getAutoCommit && c.getTransactionIsolation==Connection.TRANSACTION_READ_COMMITTED,"fence acquire needs a fresh READ COMMITTED transaction")
    verifyProtection(c,b.sourceUuid,b.database)
    acquireChecked(c,b)
  }
  private def acquireChecked(c:Connection,b:CaptureFenceBinding):CaptureFenceBinding={
    validateIdentity(b)
    require(c!=null && !c.getAutoCommit && c.getTransactionIsolation==Connection.TRANSACTION_READ_COMMITTED,"fence acquire needs a fresh READ COMMITTED transaction")
    require(query(c,"SELECT @@server_uuid,DATABASE()")==Vector(Vector(b.sourceUuid,b.database)),"source fence database identity changed")
    val current=query(c,"SELECT source_uuid,database_name,epoch,phase,version,owner_token,operation_id,action,table_name,generation FROM pekko_capture_control_fence_v1 WHERE domain_id='default' FOR UPDATE")
    require(current.size==1,"source fence missing or ambiguous")
    val same=Vector(b.sourceUuid,b.database,b.epoch.toString,"FENCED",b.version.toString,b.ownerToken,b.operation,b.action,b.table,b.generation)
    if(current.head!=same){
      require(current.head.take(5)==Vector(b.sourceUuid,b.database,(b.epoch-1L).toString,"OPEN",b.version.toString) && current.head.drop(5).forall(_.isEmpty),"source fence acquire conflict")
      val n=update(c,"UPDATE pekko_capture_control_fence_v1 SET epoch=?,owner_token=?,phase='FENCED',operation_id=?,action=?,table_name=?,generation=? WHERE domain_id='default' AND epoch=? AND phase='OPEN' AND version=?",
        Seq(b.epoch,b.ownerToken,b.operation,b.action,b.table,b.generation,b.epoch-1L,b.version))
      require(n==1,"source fence acquire rejected")
    }
    b
  }
  /** Release only after caller has verified the durable Journal/permit boundary. */
  private def releasePrimitive(c:Connection,r:CaptureFenceReceipt,permitId:String,stateDigest:String):Unit={
    require(r!=null,"source fence receipt required")
    verifyProtection(c,r.binding.sourceUuid,r.binding.database)
    releaseChecked(c,r,permitId,stateDigest)
  }
  private def releaseChecked(c:Connection,r:CaptureFenceReceipt,permitId:String,stateDigest:String):Unit={
    require(r!=null,"source fence receipt required")
    val b=r.binding.copy(version=r.versionAfter)
    require(c!=null && !c.getAutoCommit && c.getTransactionIsolation==Connection.TRANSACTION_READ_COMMITTED,"fence release needs a fresh READ COMMITTED transaction")
    lock(c,b)
    val marker=query(c,"SELECT operation_id,action,table_name,generation FROM pekko_capture_control WHERE operation_id=? FOR UPDATE",Seq(b.operation))
    require(marker==Vector(Vector(b.operation,b.action,b.table,b.generation)),"source fence marker missing or changed")
    val values=releaseParameters(r,permitId,stateDigest)
    require(update(c,"INSERT INTO pekko_capture_control_release_v1 VALUES(?,?,?,?,?,?,?,?)",values)==1,"source release receipt insert failed")
    val n=update(c,"UPDATE pekko_capture_control_fence_v1 SET phase='OPEN',owner_token='',operation_id='',action='',table_name='',generation='' WHERE domain_id='default' AND epoch=? AND owner_token=? AND phase='FENCED' AND version=?",
      Seq(b.epoch,b.ownerToken,b.version))
    require(n==1,"source fence release rejected")
  }
  /** OPEN permits normal control writes; FENCED needs a separately verified recovery binding. */
  def writeOpenMarker(c:Connection,sourceUuid:String,database:String,operation:String,action:String,table:String,generation:String):Unit={
    require(c!=null && !c.getAutoCommit && c.getTransactionIsolation==Connection.TRANSACTION_READ_COMMITTED,"source control needs a fresh READ COMMITTED transaction")
    verifyProtection(c,sourceUuid,database)
    writeOpenChecked(c,sourceUuid,database,operation,action,table,generation)
  }
  private def writeOpenChecked(c:Connection,sourceUuid:String,database:String,operation:String,action:String,table:String,generation:String):Unit={
    val domain=query(c,"SELECT source_uuid,database_name,phase FROM pekko_capture_control_fence_v1 WHERE domain_id='default' FOR UPDATE")
    require(domain==Vector(Vector(sourceUuid,database,"OPEN")),"source control domain fenced or missing")
    val row=Vector(operation,action,table,generation)
    val found=query(c,"SELECT operation_id,action,table_name,generation FROM pekko_capture_control WHERE operation_id=? FOR UPDATE",Seq(operation))
    require(found.size<=1 && found.forall(_==row),"source marker identity conflict")
    if(found.isEmpty)require(update(c,"INSERT INTO pekko_capture_control VALUES(?,?,?,?)",row)==1,"source marker insert failed")
  }
  /**
    * Lock and verify the same source-domain row on a caller-owned transaction.
    * This is deliberately not called Verified: trigger integrity, account permissions,
    * guardian isolation, and old-transaction drain must be established separately.
    */
  def lock(c:Connection,b:CaptureFenceBinding):Unit={
    validateIdentity(b);require(c!=null && !c.getAutoCommit,"source fence requires an explicit transaction")
    val identity=query(c,"SELECT @@server_uuid,DATABASE()")
    require(identity.size==1 && identity.head==Vector(b.sourceUuid,b.database),"source fence database identity changed")
    val rows=query(c,"SELECT source_uuid,database_name,epoch,owner_token,phase,version,operation_id,action,table_name,generation FROM pekko_capture_control_fence_v1 WHERE domain_id='default' FOR UPDATE")
    require(rows.size==1 && rows.head==Vector(b.sourceUuid,b.database,b.epoch.toString,b.ownerToken,"FENCED",b.version.toString,b.operation,b.action,b.table,b.generation),
      "source fence identity or generation changed")
  }
  /** Insert using the same locked connection; caller owns commit and uncertain-outcome handling. */
  private def writeMarker(c:Connection,b:CaptureFenceBinding):CaptureFenceReceipt={
    lock(c,b)
    val found=query(c,"SELECT operation_id,action,table_name,generation FROM pekko_capture_control WHERE operation_id=? FOR UPDATE",Seq(b.operation))
    val expected=Vector(b.operation,b.action,b.table,b.generation)
    require(found.size<=1 && found.forall(_==expected),"source marker identity conflict")
    val created=found.isEmpty
    if(created)require(update(c,"INSERT INTO pekko_capture_control VALUES(?,?,?,?)",expected)==1,"source marker insert failed")
    val saved=query(c,"SELECT operation_id,action,table_name,generation FROM pekko_capture_control WHERE operation_id=? FOR UPDATE",Seq(b.operation))
    require(saved==Vector(expected),"source marker identity conflict")
    val row=query(c,"SELECT version,source_uuid,database_name,epoch,owner_token,phase,operation_id,action,table_name,generation FROM pekko_capture_control_fence_v1 WHERE domain_id='default' FOR UPDATE")
    require(row.size==1 && row.head.drop(1)==Vector(b.sourceUuid,b.database,b.epoch.toString,b.ownerToken,"FENCED",b.operation,b.action,b.table,b.generation),"source fence changed while writing")
    val after=try row.head.head.toLong catch {case _:NumberFormatException=>throw new IllegalArgumentException("invalid source fence version")}
    require(after==(if(created)Math.addExact(b.version,1L)else b.version),"source fence version did not advance exactly once")
    CaptureFenceReceipt(b,after,created)
  }
}
