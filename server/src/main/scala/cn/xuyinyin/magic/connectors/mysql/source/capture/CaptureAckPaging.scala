package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import java.sql.{Connection, PreparedStatement}
import spray.json._

/** Identity of one immutable recovery proof. The caller must independently validate the fence. */
final case class CaptureAckPageIdentity(task:String, offset:String, head:Long, anchor:Long,
  intent:String, sourceFence:String, guardianAttempt:String, priorHead:Option[Long]=None,priorDigest:Option[String]=None)
final case class CaptureAckPage(revision:Long, done:Boolean, anchorMatchesOffset:Boolean)
private[magic] final case class CapturePinnedWitness(capture:String,lineage:String,identity:CaptureAckPageIdentity,
  proofId:String,intentSeq:Long,headDigest:String,mode:String) {
  def json:String=CapturePinnedWitness.encode(this)
}

private[magic] object CapturePinnedWitness {
  def encode(w:CapturePinnedWitness):String=MySQLCdcGroup.canonical(JsObject("version"->JsNumber(1),
    "capture"->JsString(w.capture),"lineage"->JsString(w.lineage),"task"->JsString(w.identity.task),
    "offset"->w.identity.offset.parseJson,"head"->JsNumber(w.identity.head),"anchor"->JsNumber(w.identity.anchor),
    "intent"->JsString(w.identity.intent),"fence"->JsString(w.identity.sourceFence),"guardian"->JsString(w.identity.guardianAttempt),
    "priorHead"->w.identity.priorHead.map(JsNumber(_)).getOrElse(JsNull),"priorDigest"->w.identity.priorDigest.map(JsString(_)).getOrElse(JsNull),
    "proofId"->JsString(w.proofId),"intentSeq"->JsNumber(w.intentSeq),"headDigest"->JsString(w.headDigest),"mode"->JsString(w.mode)))
  def parse(s:String):CapturePinnedWitness={
    val f=s.parseJson.asJsObject.fields
    require(f.keySet==Set("version","capture","lineage","task","offset","head","anchor","intent","fence","guardian","priorHead","priorDigest","proofId","intentSeq","headDigest","mode") && f("version")==JsNumber(1),"invalid pinned witness version or fields")
    def str(k:String)=f(k).asInstanceOf[JsString].value
    def num(k:String)=f(k).asInstanceOf[JsNumber].value.toLongExact
    val previous=f("priorHead") match {case JsNull=>None;case JsNumber(n)=>Some(n.toLongExact);case _=>throw new IllegalArgumentException("invalid prior ACK head")}
    val previousDigest=f("priorDigest") match {case JsNull=>None;case JsString(x)=>Some(x);case _=>throw new IllegalArgumentException("invalid prior ACK digest")}
    val i=CaptureAckPageIdentity(str("task"),MySQLCdcGroup.canonical(f("offset")),num("head"),num("anchor"),str("intent"),str("fence"),str("guardian"),previous,previousDigest)
    val w=CapturePinnedWitness(str("capture"),str("lineage"),i,str("proofId"),num("intentSeq"),str("headDigest"),str("mode"))
    require(Set("PENDING","COVERED","REPLAY")(w.mode) && w.intentSeq>0 && w.proofId.matches("[a-f0-9]{64}") &&
      w.headDigest.matches("[a-f0-9]{64}") && previous.isDefined==previousDigest.isDefined && previousDigest.forall(_.matches("[a-f0-9]{64}")),"invalid pinned witness")
    w
  }
}

/** Same-transaction validation for a receipt/permit. Caller owns source and state locks. */
private[magic] object CapturePinnedProof {
  def verifyOn(c:Connection,capture:String,sourceLineage:String,task:String,offset:String,
    bindingDigest:String,oldAttempt:String):CapturePinnedWitness={
    require(!c.getAutoCommit && bindingDigest.matches("[a-f0-9]{64}") && oldAttempt.nonEmpty,"invalid pinned proof identity")
    val unavailable:()=>Connection=()=>throw new IllegalStateException("proof verification must use caller transaction")
    val store=new MySQLCaptureStore(unavailable,capture,sourceLineage,Long.MaxValue)
    val descriptor=store.pendingBDescriptorOn(c,offset,task).getOrElse(throw new IllegalArgumentException("B descriptor missing"))
    val identity=CaptureAckPageIdentity(task,offset,descriptor.head,descriptor.anchor,descriptor.intentDigest,bindingDigest,oldAttempt)
    val pages=new CaptureAckPaging(unavailable,store)
    val mode=store.completeModeOn(c,task,descriptor,identity,pages)
    try CapturePinnedWitness(capture,sourceLineage,identity.copy(offset=store.proofOffsetKey(offset)),pages.proofId(identity),
      descriptor.intentSeq,store.proofFrame(c,descriptor.head)("digest"),mode)
    finally store.clearProofConnection(c)
  }
  def verifyExactOn(c:Connection,w:CapturePinnedWitness):Unit={
    require(!c.getAutoCommit,"pinned proof requires caller transaction")
    val unavailable:()=>Connection=()=>throw new IllegalStateException("proof verification must use caller transaction")
    val store=new MySQLCaptureStore(unavailable,w.capture,w.lineage,Long.MaxValue)
    val pages=new CaptureAckPaging(unavailable,store)
    val d=store.pendingBDescriptorOn(c,w.identity.offset,w.identity.task).getOrElse(throw new IllegalArgumentException("B descriptor missing"))
    require(d.anchor==w.identity.anchor && d.intentSeq==w.intentSeq && d.intentDigest==w.identity.intent &&
      pages.proofId(w.identity)==w.proofId,"pinned proof identity changed")
    val state=pages.statusOn(c,w.identity);require(state.done,"pinned ACK proof incomplete")
    try {
      val head=store.proofFrame(c,w.identity.head)
      require(head("kind")=="ACK" && head("digest")==w.headDigest,"pinned ACK head changed")
      val modeValid=if(w.mode=="PENDING")state.anchorMatchesOffset
        else if(w.mode=="COVERED")d.barrier.exists(pages.containsOn(c,w.identity,_))
        else w.mode=="REPLAY" && state.anchorMatchesOffset && d.originalOffset==store.proofOffsetKey(w.identity.offset)
      require(modeValid,"pinned proof mode changed")
    }finally store.clearProofConnection(c)
  }
  /** Caller must lock and decode actual current SDK D in this same state transaction. */
  def verifyAdvancedOn(c:Connection,old:CapturePinnedWitness,currentOffset:String):CapturePinnedWitness={
    verifyExactOn(c,old)
    val unavailable:()=>Connection=()=>throw new IllegalStateException("proof verification must use caller transaction")
    val store=new MySQLCaptureStore(unavailable,old.capture,old.lineage,Long.MaxValue)
    val descriptor=store.pendingBDescriptorOn(c,currentOffset,old.identity.task)
      .getOrElse(throw new IllegalArgumentException("advanced D B descriptor missing"))
    require(descriptor.status=="CAPTURE_READY" && descriptor.barrier.nonEmpty && descriptor.anchor==old.identity.anchor &&
      descriptor.intentSeq==old.intentSeq && descriptor.intentDigest==old.identity.intent,"advanced D B binding changed")
    val pages=new CaptureAckPaging(unavailable,store)
    val identity=CaptureAckPageIdentity(old.identity.task,currentOffset,descriptor.head,descriptor.anchor,descriptor.intentDigest,
      old.identity.sourceFence,old.identity.guardianAttempt,Some(old.identity.head),Some(old.headDigest))
    require(store.completeModeOn(c,old.identity.task,descriptor,identity,pages)=="COVERED","advanced D does not cover B")
    try CapturePinnedWitness(old.capture,old.lineage,identity.copy(offset=store.proofOffsetKey(currentOffset)),pages.proofId(identity),
      descriptor.intentSeq,store.proofFrame(c,descriptor.head)("digest"),"COVERED")
    finally store.clearProofConnection(c)
  }
  /** Only an absent/in-progress proof or an explicitly B_PENDING subscription means wait. */
  def advancedIfCompleteOn(c:Connection,old:CapturePinnedWitness,currentOffset:String):Option[CapturePinnedWitness]={
    verifyExactOn(c,old)
    val unavailable:()=>Connection=()=>throw new IllegalStateException("proof verification must use caller transaction")
    val store=new MySQLCaptureStore(unavailable,old.capture,old.lineage,Long.MaxValue)
    try {
      val sub=store.subscriptionOn(c,old.identity.task).getOrElse(throw new IllegalArgumentException("B subscription missing"))
      if(sub.status=="B_PENDING")None
      else {
        require(sub.status=="CAPTURE_READY","B subscription is not eligible for recovery")
        val d=store.pendingBDescriptorOn(c,currentOffset,old.identity.task)
          .getOrElse(throw new IllegalArgumentException("advanced D B descriptor missing"))
        require(d.status=="CAPTURE_READY" && d.barrier.nonEmpty && d.anchor==old.identity.anchor &&
          d.intentSeq==old.intentSeq && d.intentDigest==old.identity.intent,"advanced D B binding changed")
        val i=CaptureAckPageIdentity(old.identity.task,currentOffset,d.head,d.anchor,d.intentDigest,old.identity.sourceFence,
          old.identity.guardianAttempt,Some(old.identity.head),Some(old.headDigest))
        val pages=new CaptureAckPaging(unavailable,store)
        pages.statusIfPresentOn(c,i) match {
          case None=>None
          case Some(s) if !s.done=>None
          case Some(_)=>Some(verifyAdvancedOn(c,old,currentOffset))
        }
      }
    }finally store.clearProofConnection(c)
  }
}

/** Bounded, restartable ACK traversal. L remains retained; this is not a GC permit. */
final class CaptureAckPaging(open:()=>Connection,store:MySQLCaptureStore) {
  private def sql[A](c:Connection,s:String,args:Seq[Any])(f:PreparedStatement=>A):A={
    val q=c.prepareStatement(s);q.setQueryTimeout(5)
    try {args.zipWithIndex.foreach {case(v,i)=>q.setObject(i+1,v)};f(q)}finally q.close()
  }
  private def update(c:Connection,s:String,args:Any*):Int=sql(c,s,args)(_.executeUpdate())
  private def rows(c:Connection,s:String,args:Any*):Vector[Map[String,String]]=sql(c,s,args) {q=>
    val r=q.executeQuery();try {val m=r.getMetaData;val b=Vector.newBuilder[Map[String,String]]
      while(r.next())b+=(1 to m.getColumnCount).map(i=>m.getColumnLabel(i)->r.getString(i)).toMap
      b.result()}finally r.close()
  }
  private def lockHead(c:Connection):Unit={
    require(!c.getAutoCommit,"ACK proof requires the caller's state transaction")
    val head=rows(c,"SELECT lineage FROM pekko_capture_readers WHERE capture_id=? FOR UPDATE",store.captureId).head
    require(head("lineage")==store.lineage,"capture source lineage conflict")
  }
  def initialize():Unit={
    val c=open();try Vector(
      "CREATE TABLE IF NOT EXISTS pekko_capture_ack_pages_v1(capture_id VARCHAR(64) NOT NULL,proof_id CHAR(64) NOT NULL,identity_json LONGTEXT NOT NULL,head_seq BIGINT NOT NULL,anchor_seq BIGINT NOT NULL,next_seq BIGINT NULL,next_digest CHAR(64) NULL,ref_index INT NOT NULL,revision BIGINT NOT NULL,found_anchor BOOLEAN NOT NULL,anchor_offset_match BOOLEAN NOT NULL,phase VARCHAR(12) NOT NULL,done BOOLEAN NOT NULL,PRIMARY KEY(capture_id,proof_id))",
      "CREATE TABLE IF NOT EXISTS pekko_capture_ack_page_nodes_v1(capture_id VARCHAR(64) NOT NULL,proof_id CHAR(64) NOT NULL,ack_seq BIGINT NOT NULL,ack_digest CHAR(64) NOT NULL,parent_seq BIGINT NULL,parent_digest CHAR(64) NULL,ref_count INT NOT NULL,PRIMARY KEY(capture_id,proof_id,ack_seq))",
      "CREATE TABLE IF NOT EXISTS pekko_capture_ack_page_pins_v1(capture_id VARCHAR(64) NOT NULL,proof_id CHAR(64) NOT NULL,identity_json LONGTEXT NOT NULL,PRIMARY KEY(capture_id,proof_id))",
      "CREATE TABLE IF NOT EXISTS pekko_capture_ack_prior_v1(capture_id VARCHAR(64) NOT NULL,proof_id CHAR(64) NOT NULL,prior_seq BIGINT NOT NULL,prior_digest CHAR(64) NOT NULL,verified BOOLEAN NOT NULL,PRIMARY KEY(capture_id,proof_id))",
      "CREATE TABLE IF NOT EXISTS pekko_capture_ack_page_refs_v1(capture_id VARCHAR(64) NOT NULL,proof_id CHAR(64) NOT NULL,ack_seq BIGINT NOT NULL,ref_index INT NOT NULL,target_seq BIGINT NOT NULL,target_digest CHAR(64) NOT NULL,PRIMARY KEY(capture_id,proof_id,ack_seq,ref_index),INDEX membership(capture_id,proof_id,target_seq))"
    ).foreach(s=>update(c,s+" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin"))finally c.close()
  }
  private def identity(i:CaptureAckPageIdentity):String={
    require(i.task.nonEmpty && i.intent.nonEmpty && i.sourceFence.nonEmpty && i.guardianAttempt.nonEmpty && i.head>0 && i.anchor>0 &&
      i.priorHead.isDefined==i.priorDigest.isDefined && i.priorHead.forall(n=>n>0 && n<=i.head) && i.priorDigest.forall(_.matches("[a-f0-9]{64}")))
    MySQLCdcGroup.canonical(JsObject("capture"->JsString(store.captureId),"lineage"->JsString(store.lineage),
      "task"->JsString(i.task),"offset"->store.proofOffsetKey(i.offset).parseJson,"head"->JsNumber(i.head),
      "anchor"->JsNumber(i.anchor),"intent"->JsString(i.intent),"fence"->JsString(i.sourceFence),"guardian"->JsString(i.guardianAttempt),
      "priorHead"->i.priorHead.map(JsNumber(_)).getOrElse(JsNull),"priorDigest"->i.priorDigest.map(JsString(_)).getOrElse(JsNull)))
  }
  private[magic] def proofId(i:CaptureAckPageIdentity):String=MySQLCdcGroup.hash(identity(i))
  private def selected(c:Connection,id:String,lock:Boolean):Map[String,String]={
    val state=rows(c,"SELECT * FROM pekko_capture_ack_pages_v1 WHERE capture_id=? AND proof_id=?"+(if(lock)" FOR UPDATE"else""),store.captureId,id).headOption
      .getOrElse(throw new IllegalArgumentException("ACK proof not found"))
    val pin=rows(c,"SELECT identity_json FROM pekko_capture_ack_page_pins_v1 WHERE capture_id=? AND proof_id=?",store.captureId,id)
    require(pin.size==1 && pin.head("identity_json")==state("identity_json"),"ACK proof pin missing or changed")
    state
  }
  private[magic] def beginOn(c:Connection,i:CaptureAckPageIdentity):String={
    lockHead(c);try {
    val canonical=identity(i);val id=MySQLCdcGroup.hash(canonical)
    val latest=rows(c,"SELECT seq FROM pekko_capture_log WHERE capture_id=? AND kind='ACK' AND table_name=? ORDER BY seq DESC LIMIT 1",
      store.captureId,MySQLCdcGroup.hash(store.proofOffsetKey(i.offset))).headOption
    require(latest.exists(_("seq").toLong==i.head),"actual D ACK head changed")
    val f=store.proofFrame(c,i.head);require(f("kind")=="ACK","ACK head missing")
    update(c,"INSERT IGNORE INTO pekko_capture_ack_page_pins_v1 VALUES(?,?,?)",store.captureId,id,canonical)
    val pin=rows(c,"SELECT identity_json FROM pekko_capture_ack_page_pins_v1 WHERE capture_id=? AND proof_id=? FOR UPDATE",store.captureId,id)
    require(pin.size==1 && pin.head("identity_json")==canonical,"ACK proof pin conflict")
    i.priorHead.foreach {n=>
      update(c,"INSERT IGNORE INTO pekko_capture_ack_prior_v1 VALUES(?,?,?,?,FALSE)",store.captureId,id,n,i.priorDigest.get)
      val prior=rows(c,"SELECT * FROM pekko_capture_ack_prior_v1 WHERE capture_id=? AND proof_id=?",store.captureId,id)
      require(prior.size==1 && prior.head("prior_seq").toLong==n && prior.head("prior_digest")==i.priorDigest.get,"prior ACK proof conflict")
    }
    val inserted=update(c,"INSERT IGNORE INTO pekko_capture_ack_pages_v1 VALUES(?,?,?,?,?,?,?,0,0,FALSE,FALSE,'BUILD',FALSE)",
      store.captureId,id,canonical,i.head,i.anchor,i.head,f("digest"))
    require(inserted==1 || selected(c,id,true)("identity_json")==canonical,"ACK proof identity conflict")
    id
    }finally store.clearProofConnection(c)
  }
  /** Each step handles no more than limit references or ACK transitions. */
  private[magic] def stepOn(c:Connection,i:CaptureAckPageIdentity,expectedRevision:Long,limit:Int):CaptureAckPage={
    lockHead(c);try {
    require(limit>0 && limit<=128);val canonical=identity(i);val id=MySQLCdcGroup.hash(canonical);val state=selected(c,id,true)
    require(state("identity_json")==canonical && state("revision").toLong==expectedRevision && state("done")=="0","ACK proof revision conflict")
    var next=Option(state("next_seq")).map(_.toLong);var expected=Option(state("next_digest"));var index=state("ref_index").toInt
    val phase=state("phase");require(Set("BUILD","VERIFY")(phase),"ACK proof phase invalid")
    var found=state("found_anchor")=="1";var same=state("anchor_offset_match")=="1";var budget=limit
    while(next.nonEmpty && budget>0){
      val seq=next.get;val f=store.proofFrame(c,seq)
      require(f("kind")=="ACK" && expected.contains(f("digest")),"ACK page parent digest conflict")
      if(phase=="VERIFY" && i.priorHead.contains(seq)){
        require(i.priorDigest.contains(f("digest")),"prior ACK head digest changed")
        require(update(c,"UPDATE pekko_capture_ack_prior_v1 SET verified=TRUE WHERE capture_id=? AND proof_id=? AND prior_seq=? AND prior_digest=?",
          store.captureId,id,seq,f("digest"))==1,"prior ACK pin missing")
      }
      val payload=f("payload").parseJson.asJsObject.fields;val offset=MySQLCdcGroup.canonical(payload("offset"))
      require(f("table_name")==MySQLCdcGroup.hash(offset),"ACK offset binding conflict")
      if(seq==i.head)require(offset==store.proofOffsetKey(i.offset),"actual D ACK offset conflict")
      if(seq==i.anchor){found=true;same=offset==store.proofOffsetKey(i.offset)}
      val refs=payload("references").asInstanceOf[JsArray].elements
      require(index>=0 && index<=refs.size,"ACK page reference index conflict")
      while(index<refs.size && budget>0){
        val r=refs(index).asJsObject.fields;val target=r("sequence").asInstanceOf[JsNumber].value.toLongExact
        val digest=r("digest").asInstanceOf[JsString].value;require(target<seq,"capture proof cycle")
        require(store.proofFrame(c,target)("digest")==digest,"capture proof digest conflict")
        if(phase=="BUILD")update(c,"INSERT INTO pekko_capture_ack_page_refs_v1 VALUES(?,?,?,?,?,?)",store.captureId,id,seq,index,target,digest)
        else {
          val saved=rows(c,"SELECT target_seq,target_digest FROM pekko_capture_ack_page_refs_v1 WHERE capture_id=? AND proof_id=? AND ack_seq=? AND ref_index=?",store.captureId,id,seq,index)
          require(saved.size==1 && saved.head("target_seq").toLong==target && saved.head("target_digest")==digest,"ACK page reference conflict")
        }
        index+=1;budget-=1
      }
      if(index==refs.size && budget>0){
        val parent=payload("parent") match {
          case JsNull=>None
          case value=>val p=value.asJsObject.fields;val n=p("sequence").asInstanceOf[JsNumber].value.toLongExact
            require(n<seq,"capture proof cycle");Some(n->p("digest").asInstanceOf[JsString].value)
        }
        if(phase=="BUILD")update(c,"INSERT INTO pekko_capture_ack_page_nodes_v1 VALUES(?,?,?,?,?,?,?)",store.captureId,id,seq,f("digest"),parent.map(x=>Long.box(x._1)).orNull,parent.map(_._2).orNull,refs.size)
        else {
          val n=rows(c,"SELECT * FROM pekko_capture_ack_page_nodes_v1 WHERE capture_id=? AND proof_id=? AND ack_seq=?",store.captureId,id,seq)
          require(n.size==1 && n.head("ack_digest")==f("digest") && Option(n.head("parent_seq")).map(_.toLong)==parent.map(_._1) &&
            Option(n.head("parent_digest"))==parent.map(_._2) && n.head("ref_count").toInt==refs.size,"ACK page ancestry conflict")
        }
        next=parent.map(_._1);expected=parent.map(_._2)
        index=0;budget-=1
      }
    }
    val finished=next.isEmpty;require(!finished || found,"ACK proof anchor missing")
    val done=finished && phase=="VERIFY"
    if(done && i.priorHead.nonEmpty){
      val prior=rows(c,"SELECT * FROM pekko_capture_ack_prior_v1 WHERE capture_id=? AND proof_id=?",store.captureId,id)
      require(prior.size==1 && prior.head("prior_seq").toLong==i.priorHead.get && prior.head("prior_digest")==i.priorDigest.get && prior.head("verified")=="1",
        "prior ACK head is not in verified ancestry")
    }
    val nextPhase=if(finished && phase=="BUILD")"VERIFY"else phase
    if(finished && !done){next=Some(i.head);expected=Some(store.proofFrame(c,i.head)("digest"));index=0}
    val changed=update(c,"UPDATE pekko_capture_ack_pages_v1 SET next_seq=?,next_digest=?,ref_index=?,revision=revision+1,found_anchor=?,anchor_offset_match=?,phase=?,done=? WHERE capture_id=? AND proof_id=? AND revision=?",
      next.map(Long.box).orNull,expected.orNull,index,Boolean.box(found),Boolean.box(same),nextPhase,Boolean.box(done),store.captureId,id,expectedRevision)
    require(changed==1,"ACK proof revision conflict");CaptureAckPage(expectedRevision+1,done,same)
    }finally store.clearProofConnection(c)
  }
  private[magic] def statusOn(c:Connection,i:CaptureAckPageIdentity):CaptureAckPage={
    lockHead(c)
    val canonical=identity(i);val id=MySQLCdcGroup.hash(canonical);val state=selected(c,id,true)
    require(state("identity_json")==canonical,"ACK proof identity conflict")
    require(state("done")!="1" || (state("phase")=="VERIFY" && state("found_anchor")=="1" && state("next_seq")==null),"completed ACK proof state conflict")
    i.priorHead.foreach {n=>
      val prior=rows(c,"SELECT * FROM pekko_capture_ack_prior_v1 WHERE capture_id=? AND proof_id=?",store.captureId,id)
      require(prior.size==1 && prior.head("prior_seq").toLong==n && prior.head("prior_digest")==i.priorDigest.get &&
        (state("done")!="1" || prior.head("verified")=="1"),"prior ACK proof pin missing or incomplete")
    }
    CaptureAckPage(state("revision").toLong,state("done")=="1",state("anchor_offset_match")=="1")
  }
  private[magic] def statusIfPresentOn(c:Connection,i:CaptureAckPageIdentity):Option[CaptureAckPage]={
    lockHead(c);val id=proofId(i)
    val found=rows(c,"SELECT 1 FROM pekko_capture_ack_pages_v1 WHERE capture_id=? AND proof_id=?",store.captureId,id)
    if(found.nonEmpty)Some(statusOn(c,i))
    else {
      require(rows(c,"SELECT 1 FROM pekko_capture_ack_page_pins_v1 WHERE capture_id=? AND proof_id=?",store.captureId,id).isEmpty &&
        rows(c,"SELECT 1 FROM pekko_capture_ack_prior_v1 WHERE capture_id=? AND proof_id=?",store.captureId,id).isEmpty &&
        rows(c,"SELECT 1 FROM pekko_capture_ack_page_nodes_v1 WHERE capture_id=? AND proof_id=? LIMIT 1",store.captureId,id).isEmpty &&
        rows(c,"SELECT 1 FROM pekko_capture_ack_page_refs_v1 WHERE capture_id=? AND proof_id=? LIMIT 1",store.captureId,id).isEmpty,
        "orphaned ACK proof evidence")
      None
    }
  }
  private[magic] def containsAckOn(c:Connection,i:CaptureAckPageIdentity,seq:Long):Boolean={
    lockHead(c);try {
      val id=proofId(i);require(statusOn(c,i).done,"ACK proof incomplete or identity changed")
      val node=rows(c,"SELECT ack_digest FROM pekko_capture_ack_page_nodes_v1 WHERE capture_id=? AND proof_id=? AND ack_seq=?",store.captureId,id,seq).headOption
      node.exists {n=>val ack=store.proofFrame(c,seq);require(ack("kind")=="ACK" && ack("digest")==n("ack_digest"),"ACK proof node changed");true}
    }finally store.clearProofConnection(c)
  }
  /** Requires an immutable L and a trusted state database; this is not an external signature. */
  private[magic] def containsOn(c:Connection,i:CaptureAckPageIdentity,target:Long):Boolean={
    lockHead(c);try {
      val id=proofId(i);require(statusOn(c,i).done,"ACK proof incomplete or identity changed")
      val ref=rows(c,"SELECT * FROM pekko_capture_ack_page_refs_v1 WHERE capture_id=? AND proof_id=? AND target_seq=? LIMIT 1",store.captureId,id,target).headOption
      ref.exists {r=>
        val seq=r("ack_seq").toLong;val ack=store.proofFrame(c,seq)
        val nodes=rows(c,"SELECT ack_digest,ref_count FROM pekko_capture_ack_page_nodes_v1 WHERE capture_id=? AND proof_id=? AND ack_seq=?",store.captureId,id,seq)
        require(nodes.size==1 && nodes.head("ack_digest")==ack("digest") && ack("kind")=="ACK","ACK proof node missing")
        val refs=ack("payload").parseJson.asJsObject.fields("references").asInstanceOf[JsArray].elements
        val index=r("ref_index").toInt;require(index>=0 && index<refs.size && refs.size==nodes.head("ref_count").toInt,"ACK proof reference index conflict")
        val entry=refs(index).asJsObject.fields
        require(entry("sequence")==JsNumber(target) && entry("digest")==JsString(r("target_digest")) &&
          store.proofFrame(c,target)("digest")==r("target_digest"),"ACK proof reference changed")
        true
      }
    }finally store.clearProofConnection(c)
  }
}
