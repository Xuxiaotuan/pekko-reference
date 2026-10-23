package cn.xuyinyin.magic.workflow.actors

import EventSourcedWorkflowActor._
import cn.xuyinyin.magic.workflow.events.WorkflowEvents._
import cn.xuyinyin.magic.workflow.scheduling.TableSchedulingRuntime
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.jdbc.query.scaladsl.JdbcReadJournal
import org.apache.pekko.persistence.query.PersistenceQuery
import org.apache.pekko.stream.SystemMaterializer
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.persistence.jdbc.config.SnapshotConfig
import org.apache.pekko.persistence.jdbc.db.SlickExtension
import org.apache.pekko.persistence.jdbc.snapshot.dao.SnapshotDao
import org.apache.pekko.serialization.{Serialization,SerializationExtension}
import org.apache.pekko.stream.Materializer
import slick.jdbc.{JdbcBackend,JdbcProfile}
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext,Future}
import scala.concurrent.duration._
import scala.util.control.NonFatal
import spray.json._

/** Read-only event projection and durable trigger ingress. Neither path creates a persistent writer. */
private[magic] final class ManagedSnapshotIngress(system:ActorSystem[_],runtime:TableSchedulingRuntime)(implicit ec:ExecutionContext) {
  private val readers=new AtomicInteger()
  private implicit val materializer:Materializer=SystemMaterializer(system).materializer
  private lazy val journal=PersistenceQuery(system).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)
  // Use the configured JDBC Snapshot DAO and Pekko serializers, including legacy-shaped payload defaults.
  // This is a read-only DAO on the existing shared Slick pool; it does not start a persistent writer.
  private lazy val snapshots:SnapshotDao={
    val classic=system.toClassic.asInstanceOf[ExtendedActorSystem]
    val config=system.settings.config.getConfig(system.settings.config.getString("pekko.persistence.snapshot-store.plugin"))
    val snapshotConfig=new SnapshotConfig(config)
    val db=SlickExtension(classic).database(config)
    classic.dynamicAccess.createInstanceFor[SnapshotDao](snapshotConfig.pluginConfig.dao,Seq(
      classOf[JdbcBackend.Database]->db.database,classOf[JdbcProfile]->db.profile,classOf[SnapshotConfig]->snapshotConfig,
      classOf[Serialization]->SerializationExtension(classic),classOf[ExecutionContext]->ec,classOf[Materializer]->materializer)).get
  }
  def read(workflowId:String):Future[WorkflowState]={
    if(readers.incrementAndGet()>8){readers.decrementAndGet();return Future.failed(new RejectedExecutionException("managed history query capacity exhausted"))}
    val result=try {
      snapshots.latestSnapshot("workflow-"+workflowId).flatMap { selected=>
        val initial=selected.map {case(metadata,state)=>(metadata.sequenceNr,state.asInstanceOf[WorkflowState])}.getOrElse((0L,WorkflowState()))
        journal.currentEventsByPersistenceId("workflow-"+workflowId,initial._1+1,Long.MaxValue).completionTimeout(5.seconds)
        .runFold(initial) { case ((sequence,state),envelope)=>
          require(envelope.sequenceNr==sequence+1,"read-only history requires a retained Journal prefix")
          (envelope.sequenceNr,WorkflowStateTransitions(state,envelope.event.asInstanceOf[WorkflowEvent]))
        }.map(_._2)
      }
        .flatMap { replayed=>runtime.io {
          val definition=runtime.store.get(workflowId)
          val state=if(replayed.workflowJson.nonEmpty)replayed else definition.fold(replayed)(t=>replayed.copy(
            workflowJson=Some(t.definition.workflowJson),revision=t.definition.revision,status=WorkflowStatus("snapshot_"+t.state)))
          val skipped=runtime.store.ownership.get.skippedTriggers(workflowId)
          val summaries=skipped.map(t=>StoredExecutionSummary(s"skipped-${t.scheduleId}-${t.scheduledAt}",t.recordedAt,t.recordedAt,true,"skipped",0L,true))
          state.copy(recentExecutions=(state.recentExecutions++summaries).groupBy(_.executionId).values.map(_.head).toVector.sortBy(s=>(s.startTime,s.executionId)).takeRight(MaxRecentExecutions))
        }}
    } catch {case NonFatal(e)=>Future.failed(e)}
    // A timed-out request must not free admission while JDBC work is still unresolved.
    result.onComplete(_=>readers.decrementAndGet())(ExecutionContext.parasitic)
    Future.firstCompletedOf(Vector(result,org.apache.pekko.pattern.after(5.seconds,system.toClassic.scheduler)(
      Future.failed(new RejectedExecutionException("managed history query timed out")))))
  }
  def manual(workflow:String,request:String):Future[Reply]=runtime.find(workflow).flatMap {
    case None=>Future.successful(NotInitialized(workflow))
    case Some(t) if !Set("registered","completed").contains(t.state)=>Future.successful(
      if(t.requestKey.contains(request))DuplicateExecution(request,t.executionId.get)else AlreadyRunning(t.executionId.get))
    case Some(t)=>
      import cn.xuyinyin.magic.workflow.model.WorkflowDSL.workflowFormat
      runtime.submit(t.definition.workflowJson.parseJson.convertTo[cn.xuyinyin.magic.workflow.model.WorkflowDSL.Workflow],request,t.definition.revision)
        .map(saved=>ExecutionAccepted(saved.executionId.get))
  }
  def scheduled(workflow:String,schedule:String,at:Long,trigger:String):Future[Reply]=read(workflow).flatMap { state=>
    val watermark=state.lastAcceptedTriggerBySchedule.find(_.scheduleId==schedule).map(_.scheduledAt)
    runtime.io(runtime.store.ownership.get.submitScheduled(workflow,schedule,at,trigger,watermark,runtime.executionId(workflow,trigger))).map {
      case ("accepted",id)=>ExecutionAccepted(id)
      case ("duplicate",id)=>DuplicateExecution(trigger,id)
      case (_,id)=>AlreadyRunning(id)
    }
  }
}
