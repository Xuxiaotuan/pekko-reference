package cn.xuyinyin.magic.workflow.integration

import CdcJournalWindowSupport._
import cn.xuyinyin.magic.workflow.scheduling._
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import spray.json._
import java.nio.file.Path
import java.lang.management.ManagementFactory

/** 700000 persistent control records, NOT 700000 running actors or physical source tables. */
object RealMySQLSchedulingMetadataProcess {
  def main(args:Array[String]):Unit={
    val settings=Settings.environment();val schema=args(0);val dir=Path.of(args(1));val mode=args(2)
    val config=new HikariConfig();config.setJdbcUrl(settings.url(schema)+"&rewriteBatchedStatements=true")
    config.setUsername(settings.user);config.setPassword(settings.password);config.setMaximumPoolSize(2);config.setMinimumIdle(0)
    val pool=new HikariDataSource(config)
    val store=new TableSchedulingStore(pool,SchedulingLimits(6,4,16,2,131072,128))
    val started=System.nanoTime();val memory=Vector.newBuilder[JsValue]
    def observe(rows:Int):Unit={
      System.gc()
      val heap=ManagementFactory.getMemoryMXBean.getHeapMemoryUsage
      val proc=new ProcessBuilder("ps","-o","rss=","-p",ProcessHandle.current().pid().toString).start()
      val text=scala.io.Source.fromInputStream(proc.getInputStream);val rss=try text.mkString.trim.toLong finally text.close();proc.waitFor()
      val sample=JsObject("rows"->JsNumber(rows),"heapUsedAfterGc"->JsNumber(heap.getUsed),"heapMax"->JsNumber(heap.getMax),
        "rssKiB"->JsNumber(rss),"threads"->JsNumber(ManagementFactory.getThreadMXBean.getThreadCount),
        "pekkoThreadsObserved"->JsNumber(ManagementFactory.getThreadMXBean.getThreadInfo(ManagementFactory.getThreadMXBean.getAllThreadIds).count(t=>t!=null&&t.getThreadName.toLowerCase.contains("pekko"))),
        "seconds"->JsNumber((System.nanoTime()-started)/1e9))
      memory+=sample;emit("METADATA_PROGRESS",sample.fields.toSeq:_*)
    }
    try {
      if(mode=="load"){
        observe(0)
        val template=TableSchedulingExperimentSupport.workflow(settings,schema,1).copy(id="META_TASK",name="metadata-only").toJson.compactPrint
        val cn=pool.getConnection;cn.setAutoCommit(false)
        val statement=cn.prepareStatement("INSERT INTO pekko_table_tasks (workflow_id,revision,workflow_json,source_instance,target_instance,parallelism,page_bytes,phase,execution_id,request_key,queue_order,not_before) VALUES (?,1,?,'mysql-local','mysql-local',2,4096,'ready',?,?,?,0)")
        statement.setQueryTimeout(30)
        try {
          (1 to 700000).foreach { i=>
            val id=f"metadata-$i%07d"
            statement.setString(1,id);statement.setString(2,template.replace("META_TASK",id));statement.setString(3,s"meta-e-$i");statement.setString(4,s"meta-r-$i");statement.setLong(5,i.toLong)
            statement.addBatch()
            if(i%1000==0){statement.executeBatch();statement.clearBatch();cn.commit()}
            if(Set(10000,100000,700000).contains(i))observe(i)
          }
          val st=cn.createStatement();try st.executeUpdate("UPDATE pekko_table_admission_lock SET queue_sequence=700000 WHERE id=1") finally st.close();cn.commit()
        }finally{statement.close();cn.close()}
      }
      val count=withConnection(settings,schema)(query(_,"SELECT COUNT(*) AS n FROM pekko_table_tasks")).elements.head.asJsObject.fields("n").convertTo[String].toInt
      require(count==700000)
      val queries=Vector(None,Some("metadata-0350000"),Some("metadata-0699900"),Some("metadata-0700000")).map{after=>
        val start=System.nanoTime();val rows=store.listAfter(after,100)
        require(rows.size<=100&&rows.forall(r=>after.forall(r.workflowId>_)))
        JsObject("after"->after.map(JsString(_)).getOrElse(JsNull),"rows"->JsNumber(rows.size),"milliseconds"->JsNumber((System.nanoTime()-start)/1e6))
      }
      val claimed=if(mode=="load"){
        val first=store.claim(128);require(first.size==2);first.foreach(g=>require(store.bind(g,"old-worker")))
        val second=store.claim(128);require(second.size==2);second.foreach(g=>require(store.bind(g,"old-worker")))
        require(store.claim(128).isEmpty)
        val all=first++second
        require(all.flatMap(_.task.definition.demand.connections).map(_._2).sum==16)
        require(store.release(all.head,"ready",releaseConfirmed=false))
        all.drop(2).foreach(g=>require(store.release(g,"ready",releaseConfirmed=true)))
        val next=store.claim(128);require(next.size==2&&next.forall(_.task.workflowId>"metadata-0000004"))
        all
      }else{
        val running=withConnection(settings,schema)(query(_,"SELECT workflow_id FROM pekko_table_tasks WHERE phase='running' AND owner_id='old-worker'"))
        require(running.elements.nonEmpty)
        running.elements.foreach{row=>val t=store.get(row.asJsObject.fields("workflow_id").convertTo[String]).get;require(!store.bind(TableGrant(t,t.grantId.get),"new-worker"))}
        require(store.claim(128).isEmpty) // outstanding unbound grants and old owner remain charged after JVM restart
        Vector.empty[TableGrant]
      }
      val explain=withConnection(settings,schema){c=>
        executeSql(c,"ANALYZE TABLE pekko_table_tasks")
        query(c,"EXPLAIN SELECT workflow_id FROM pekko_table_tasks WHERE phase='ready' ORDER BY queue_order,workflow_id LIMIT 128")
      }
      require(explain.elements.exists(_.asJsObject.fields.get("key").contains(JsString("pekko_table_ready"))))
      observe(count)
      val phases=withConnection(settings,schema)(query(_,"SELECT phase,COUNT(*) AS n FROM pekko_table_tasks GROUP BY phase"))
      val sizes=withConnection(settings,schema)(query(_,"SELECT TABLE_ROWS,DATA_LENGTH,INDEX_LENGTH FROM information_schema.tables WHERE table_schema='"+schema+"' AND table_name='pekko_table_tasks'"))
      val result=JsObject("mode"->JsString(mode),"rows"->JsNumber(count),"passed"->JsBoolean(true),"heap"->JsArray(memory.result()),
        "metadataPages"->JsArray(queries),"explain"->explain,"tableSizes"->sizes,"phases"->phases,"claimedInitially"->JsNumber(claimed.size),
        "seconds"->JsNumber((System.nanoTime()-started)/1e9),
        "scope"->JsString("control-index JDBC experiment with representative serialized definitions; no physical 700000-table or CDC throughput claim"))
      write(dir,s"$mode-result.json",result)
    }finally pool.close()
  }
}
