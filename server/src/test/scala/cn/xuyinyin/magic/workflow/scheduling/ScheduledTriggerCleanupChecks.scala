package cn.xuyinyin.magic.workflow.scheduling

import java.lang.reflect.{InvocationTargetException,Proxy}
import java.sql.{Connection,SQLException}
import java.util.concurrent.atomic.{AtomicBoolean,AtomicReference}
import java.util.concurrent.{CountDownLatch,TimeUnit}
import javax.sql.DataSource
import org.scalatest.Assertions._
import scala.concurrent.{Await,ExecutionContext}
import scala.concurrent.duration._
import spray.json._

/** Shared H2/MySQL boundary checks. Only the connection reply is injected; all SQL is real. */
private[workflow] object ScheduledTriggerCleanupChecks {
  val scenarios = Vector("reject", "commit_reply_lost", "commit_not_sent", "readback_unavailable", "stale_reject", "stale_release", "yield", "delayed_release")
  def run(ds: DataSource, scenario: String): JsObject = {
    implicit val ec: ExecutionContext = ExecutionContext.global
    val fault = new AtomicReference[String]("")
    val unavailable = new AtomicBoolean(false)
    val injected = new AtomicBoolean(false)
    val committed = new CountDownLatch(1)
    val reply = new CountDownLatch(1)
    val proxy = new DataSource {
      def getConnection: Connection = {
        if(unavailable.get()) throw new SQLException("test: readback unavailable", "08006")
        val c = ds.getConnection
        Proxy.newProxyInstance(getClass.getClassLoader,Array(classOf[Connection]),(_,method,args) => {
          val mode = if(method.getName == "commit") fault.getAndSet("") else ""
          if(mode == "before") { injected.set(true); throw new SQLException("test: COMMIT not sent", "08006") }
          val result = try method.invoke(c,Option(args).getOrElse(Array.empty[AnyRef]):_*)
            catch { case e: InvocationTargetException => throw e.getCause }
          if(mode == "hold") {
            committed.countDown()
            require(reply.await(10,TimeUnit.SECONDS),"test: release reply gate timed out")
          }
          if(mode == "after" || mode == "readback") {
            injected.set(true)
            if(mode == "readback") unavailable.set(true)
            throw new SQLException("test: real COMMIT reply lost", "08007")
          }
          result
        }).asInstanceOf[Connection]
      }
      def getConnection(u:String,p:String):Connection = throw new UnsupportedOperationException
      def getLogWriter:java.io.PrintWriter = ds.getLogWriter
      def setLogWriter(v:java.io.PrintWriter):Unit = ds.setLogWriter(v)
      def setLoginTimeout(v:Int):Unit = ds.setLoginTimeout(v)
      def getLoginTimeout:Int = ds.getLoginTimeout
      def getParentLogger:java.util.logging.Logger = ds.getParentLogger
      def unwrap[T](c:Class[T]):T = ds.unwrap(c)
      def isWrapperFor(c:Class[_]):Boolean = ds.isWrapperFor(c)
    }
    val store = new TableSchedulingStore(proxy,SchedulingLimits(2,1,8,1,1048576,8),ownershipEnabled=true)
    val durable = new TableSchedulingStore(ds,store.limits,ownershipEnabled=true)
    val ha = store.ownership.get
    ha.registerOwner(SnapshotOwnerIdentity("owner","managed-jvm",101,"start"))
    val workflow = "trigger-"+scenario
    val execution = "execution-"+scenario
    store.register(ScheduledTableDefinition(workflow,1,"{}",TableResourceDemand("mysql","mysql",1,1024)))
    assert(ha.submitScheduled(workflow,"cron",2000,"cron-2000",None,execution) == ("accepted",execution))
    val runtime = new TableSchedulingRuntime(store,"owner")
    val first = Await.result(runtime.acquireRecovery(workflow,store.claim(1).head.id),3.seconds).get
    assert(runtime.scheduledTrigger(execution).exists(_.triggerId == "cron-2000"))
    var retainedOnUnknown = false
    if(Set("stale_reject","stale_release","yield","delayed_release")(scenario)) {
      val next = if(scenario == "delayed_release") {
        fault.set("hold")
        val oldReply = runtime.release(execution,"ready",true,Some(first.id))
        try {
          assert(committed.await(3,TimeUnit.SECONDS))
          val held = store.claim(1).head
          // Reconcile the old local map through durable readback while its first reply is still delayed.
          Await.result(runtime.release(execution,"ready",true,Some(held.id)),3.seconds)
          val acquired = Await.result(runtime.acquireRecovery(workflow,held.id),3.seconds).get
          reply.countDown();Await.result(oldReply,3.seconds)
          acquired
        } finally reply.countDown()
      } else {
        Await.result(runtime.release(execution,"ready",true,Some(first.id)),3.seconds)
        assert(runtime.scheduledTrigger(execution).isEmpty)
        Await.result(runtime.acquireRecovery(workflow,store.claim(1).head.id),3.seconds).get
      }
      assert(next.id != first.id)
      if(scenario == "stale_reject") Await.result(runtime.reject(first),3.seconds)
      if(scenario == "stale_release") Await.result(runtime.release(execution,"completed",true,Some(first.id)),3.seconds)
      assert(runtime.owns(next))
      assert(runtime.scheduledTrigger(execution).exists(_.triggerId == "cron-2000"))
      assert(store.get(workflow).get.grantId.contains(next.id))
      Await.result(runtime.reject(next),3.seconds)
    } else {
      fault.set(scenario match {case "commit_reply_lost" => "after"; case "commit_not_sent" => "before"; case "readback_unavailable" => "readback"; case _ => ""})
      if(Set("commit_not_sent","readback_unavailable")(scenario)) {
        intercept[SQLException](Await.result(runtime.reject(first),3.seconds))
        assert(runtime.owns(first))
        assert(runtime.scheduledTrigger(execution).nonEmpty)
        retainedOnUnknown = true
        val expectedPhase = if(scenario == "commit_not_sent") "recovering" else "blocked"
        assert(durable.get(workflow).get.state == expectedPhase)
        unavailable.set(false)
      }
      Await.result(runtime.reject(first),3.seconds)
    }
    assert(runtime.scheduledTrigger(execution).isEmpty)
    assert(!runtime.owns(first))
    assert(store.get(workflow).get.state == "blocked")
    assert(ha.scheduledTrigger(execution).exists(_.triggerId == "cron-2000"))
    assert(ha.submitScheduled(workflow,"cron",2000,"cron-2000",None,"wrong-execution") == ("duplicate","cron-2000"))
    Await.result(runtime.reject(first),3.seconds)
    assert(runtime.scheduledTrigger(execution).isEmpty)
    assert(injected.get() == Set("commit_reply_lost","commit_not_sent","readback_unavailable")(scenario))
    JsObject("scenario"->JsString(scenario),"phase"->JsString("blocked"),"triggerCacheEmpty"->JsBoolean(true),
      "durableTriggerPreserved"->JsBoolean(true),"duplicateRejected"->JsBoolean(true),
      "faultInjected"->JsBoolean(injected.get()),"contextRetainedOnUnknown"->JsBoolean(retainedOnUnknown))
  }
}
