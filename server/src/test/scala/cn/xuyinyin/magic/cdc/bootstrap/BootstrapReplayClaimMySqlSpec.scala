package cn.xuyinyin.magic.cdc.bootstrap

import cn.xuyinyin.magic.cdc.{CdcOperation,MySQLCdcEnvelope,MySQLCdcGroup,MySQLCdcGroupProtocol,MySQLCdcSourcePosition}
import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.cdc.checkpoint.{AlreadyCommitted,BatchId,Committed,SourceBatch,SourceCursor}
import cn.xuyinyin.magic.cdc.recovery.{CdcExecutionException, ExecutionFailure}
import cn.xuyinyin.magic.connectors.mysql.sink.MySQLCdcApplyConfig
import java.lang.reflect.{InvocationHandler,InvocationTargetException,Method,Proxy}
import java.sql.{Connection,DriverManager,SQLRecoverableException,SQLException}
import java.util.concurrent.{CountDownLatch,TimeUnit}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.{Await,ExecutionContext}
import scala.concurrent.duration._
import spray.json._

/** Real target transactions; does not claim to fence arbitrary SQL writers. */
class BootstrapReplayClaimMySqlSpec extends AnyWordSpec with Matchers {
  "managed bootstrap replay" should {
    "lock and validate its target claim and retain committed group evidence after an unknown reply" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_SHARED_CAPTURE_MATRIX").contains("1"))cancel("dedicated MySQL required")
      val url=sys.env("CAPTURE_STATE_URL");require(url.contains(":25822/") && url.contains("pekko_test_shared_state"))
      val user=sys.env("CDC_WINDOW_MYSQL_USER");val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      def open():Connection=DriverManager.getConnection(url,user,password)
      def sql(s:String):Unit={val c=open();try {val q=c.createStatement();try q.execute(s)finally q.close()}finally c.close()}
      def scalar(s:String):Long={val c=open();try {val q=c.createStatement();try {val r=q.executeQuery(s);try {require(r.next());r.getLong(1)}finally r.close()}finally q.close()}finally c.close()}
      val suffix=java.util.UUID.randomUUID().toString.replace("-","").take(12)
      val table="replay_claim_"+suffix;val id="boot_"+suffix;val connector="capture_"+suffix
      sql(s"CREATE TABLE `$table`(id BIGINT PRIMARY KEY,label VARCHAR(64)) ENGINE=InnoDB")
      sql("CREATE TABLE IF NOT EXISTS pekko_sync_cdc_group_ledger(group_id VARCHAR(64) PRIMARY KEY,evidence LONGTEXT NOT NULL,committed_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6)) ENGINE=InnoDB")
      val schema="""{"primaryKeyColumnNames":["id"],"columns":[{"name":"id","jdbcType":-5,"typeName":"BIGINT"},{"name":"label","jdbcType":12,"typeName":"VARCHAR"}]}"""
      val codec=new BootstrapRowCodec(schema);new BootstrapTarget(()=>open(),table,codec,id).claim()
      val targetConfig=MySQLCdcApplyConfig.trusted("127.0.0.1",25822,"pekko_test_shared_state",table,user,password)
      val protocol=MySQLCdcGroupProtocol(id,"g1","source-lineage")
      val identity=MySQLCdcGroup.canonical(JsObject("version"->JsNumber(2),"connectorId"->JsString(connector),"database"->JsString("source_db"),
        "table"->JsString("orders"),"primaryKey"->JsString("id"),"schemaFingerprint"->JsString(codec.schemaId),"groupProtocol"->protocol.json,
        "columns"->JsArray(Vector(JsObject("name"->JsString("id"),"jdbcType"->JsNumber(-5),"typeName"->JsString("BIGINT")),
          JsObject("name"->JsString("label"),"jdbcType"->JsNumber(12),"typeName"->JsString("VARCHAR"))))))
      def batch(n:Int):SourceBatch={
        val env=MySQLCdcEnvelope(1,CdcOperation.Create,JsObject("id"->JsNumber(n)),None,
          Some(JsObject("id"->JsNumber(n),"label"->JsString("row-"+n))),
          MySQLCdcSourcePosition(connector,"source_db","orders",false,Some("binlog.000001"),Some(100L+n),Some(0),None))
        val group=MySQLCdcGroup(MySQLCdcGroup.id(protocol,env),Vector("single.c"->env))
        val partition="mysql-cdc:"+connector
        SourceBatch("source",partition,n.toLong,BatchId.sha256("execution","source",partition,n.toLong),
          SourceCursor("mysql.capture.groups.v1","{}",identity),Vector(group.canonicalJson))
      }
      implicit val ec:ExecutionContext=ExecutionContext.global
      def run(s:BootstrapReplaySink,b:SourceBatch)=Await.result(s.commitBatchConfigured(targetConfig,id,"execution",b,b.rows,_=>()),15.seconds)
      def tamper(value:String):Unit={val c=open();try {val q=c.prepareStatement("UPDATE pekko_bootstrap_targets SET table_name=? WHERE bootstrap_id=?")
        try {q.setString(1,value);q.setString(2,id);require(q.executeUpdate()==1)}finally q.close()}finally c.close()}
      val plain=new BootstrapReplaySink(_=>open());val first=batch(1)
      tamper("other_table")
      intercept[IllegalStateException](run(plain,first))
      scalar(s"SELECT COUNT(*) FROM `$table`") shouldBe 0L
      tamper(table)
      val locked=new CountDownLatch(1);val release=new CountDownLatch(1)
      def wrapped(c:Connection,beforeCommit:()=>Unit,afterCommit:()=>Unit):Connection=
        Proxy.newProxyInstance(classOf[Connection].getClassLoader,Array(classOf[Connection]),new InvocationHandler {
          override def invoke(proxy:Any,m:Method,args:Array[AnyRef]):AnyRef={
            if(m.getName=="commit")beforeCommit()
            val out=try m.invoke(c,Option(args).getOrElse(Array.empty[AnyRef]):_*) catch {case e:InvocationTargetException=>throw e.getCause}
            if(m.getName=="commit")afterCommit()
            out
          }
        }).asInstanceOf[Connection]
      val locking=new BootstrapReplaySink(_=>wrapped(open(),()=>{locked.countDown();require(release.await(10,TimeUnit.SECONDS))},()=>()))
      val pending=locking.commitBatchConfigured(targetConfig,id,"execution",first,first.rows,_=>())
      try {
        require(locked.await(10,TimeUnit.SECONDS),"target commit did not reach lock window")
        val other=open();try {val q=other.createStatement();try {q.execute("SET SESSION innodb_lock_wait_timeout=1")
          intercept[SQLException](q.executeUpdate(s"UPDATE pekko_bootstrap_targets SET table_name='other_table' WHERE bootstrap_id='$id'"))
        }finally q.close()}finally other.close()
      }finally release.countDown()
      Await.result(pending,15.seconds) shouldBe a[Committed]
      val second=batch(2);val unknown=new BootstrapReplaySink(_=>wrapped(open(),()=>(),()=>throw new SQLRecoverableException("commit reply lost","08S01")))
      val failure=intercept[CdcExecutionException](run(unknown,second));failure.failure.code shouldBe ExecutionFailure.CommitUnknown
      run(plain,second) shouldBe a[AlreadyCommitted]
      scalar(s"SELECT COUNT(*) FROM `$table` WHERE (id=1 AND label='row-1') OR (id=2 AND label='row-2')") shouldBe 2L
      scalar(s"SELECT COUNT(*) FROM `$table`") shouldBe 2L
      println(s"BOOTSTRAP_REPLAY_CLAIM table=$table invalidClaimRejected=true claimLockBlocked=true commitUnknown=true replayIdempotent=true fieldsMatch=4")
    }
  }
}
