package cn.xuyinyin.magic.cdc.platform

import cn.xuyinyin.magic.tags.ExternalIntegration
import java.sql.{Connection,DriverManager}
import java.util.UUID
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Dedicated MySQL state-store check; does not exercise guardian or CDC source recovery. */
class CdcEndOwnerMySqlSpec extends AnyWordSpec with Matchers {
  "endOwner on the dedicated MySQL state store" should {
    "preserve consumed versioned claims and allow a consumed legacy claim" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_CDC_END_OWNER_MYSQL").contains("1"))cancel("dedicated MySQL fixture required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("capture-archive"))
      Class.forName("com.mysql.cj.jdbc.Driver")
      val root=sys.env("CAPTURE_STATE_URL")
      require(root.contains("127.0.0.1:25822/pekko_test_shared_state"),"unexpected state fixture")
      val user=sys.env("CDC_WINDOW_MYSQL_USER");val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      val admin=DriverManager.getConnection(root,user,password)
      try {val q=admin.createStatement();try {val r=q.executeQuery("SELECT @@server_uuid,@@port")
        try {require(r.next() && r.getString(1)=="78a80500-1849-402a-9f71-81cd1cf3fcfb" && r.getInt(2)==25822 && !r.next(),"state server identity changed")}
        finally r.close()}finally q.close()}finally admin.close()
      val suffix=UUID.randomUUID().toString.replace("-","").take(10)
      Seq(("v2",2,false,false),("downgraded",1,true,false),("legacy",1,false,true)).foreach {
        case (label,version,sidecar,clean)=>
          val schema="pekko_test_endowner_"+suffix+"_"+label
          val c=DriverManager.getConnection(root,user,password)
          try {val q=c.createStatement();try q.execute("CREATE DATABASE "+schema)finally q.close()}finally c.close()
          val url=root.replace("pekko_test_shared_state",schema)
          def open():Connection=DriverManager.getConnection(url,user,password)
          val store=new CdcTaskStore(()=>open(),CdcPlatformLimits());store.initialize()
          val managed=CdcManagedOwner("boot","deployment","host","guardian","key",123,"start","profile")
          store.beginOwner("old",1L,Some(managed)) shouldBe true
          val setup=open();try {val q=setup.createStatement();try {
            q.execute(s"INSERT INTO pekko_cdc_b_recovery_v1(owner_attempt,permit_id,task_id,capture_id,offset_json,marker_present,table_name,generation,operation_id,signed_proof,created_at,claimed_by,consumed,proof_version) VALUES('first','permit','task','capture','{}',FALSE,'orders','g1','op','proof',1,'old',TRUE,$version)")
            if(sidecar)q.execute("INSERT INTO pekko_cdc_b_fence_v2 VALUES('permit','uuid','db',1,'token',0,'op','SUBSCRIBE','orders','g1')")
          }finally q.close()}finally setup.close()
          store.endOwner("old",confirmed=true,2L) shouldBe clean
          store.ownerStatus shouldBe (if(clean)"STOPPED" else "UNKNOWN")
          println(s"END_OWNER_MYSQL schema=$schema case=$label clean=$clean state=${store.ownerStatus}")
      }
    }
  }
}
