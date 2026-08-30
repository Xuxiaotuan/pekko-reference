package cn.xuyinyin.magic.workflow.scheduler

import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.scheduler.ScheduleCalculator.FixedRate
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.sql.DriverManager
import java.time.Instant
import scala.io.Source
import scala.concurrent.duration._

object SchedulerCoordinatorSpec {
  private val h2Url = s"jdbc:h2:mem:scheduler-coordinator-${java.util.UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1"

  def config: Config = synchronized {
    Class.forName("org.h2.Driver")
    val connection = DriverManager.getConnection(h2Url)
    try {
      val statement = connection.createStatement()
      try {
        val input = Option(getClass.getClassLoader.getResourceAsStream("schema/h2/h2-create-schema.sql"))
          .getOrElse(throw new IllegalStateException("Pekko Persistence JDBC H2 schema resource is unavailable"))
        val sql = try Source.fromInputStream(input).mkString finally input.close()
        sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(statement.execute)
      } finally statement.close()
    } finally connection.close()
    ConfigFactory.parseString(s"pekko-persistence-jdbc.shared-databases.slick.db.url = \"$h2Url\"")
      .withFallback(ConfigFactory.load("application-test"))
  }
}

class SchedulerCoordinatorSpec extends ScalaTestWithActorTestKit(SchedulerCoordinatorSpec.config) with AnyWordSpecLike with Matchers {
  private val now = Instant.parse("2026-08-29T00:00:00Z")

  "SchedulerCoordinator" should {
    "stop an unacknowledged reply actor after its bounded TTL" in {
      val region = createTestProbe[ShardingEnvelope[EventSourcedWorkflowActor.Command]]()
      val coordinator = spawn(
        SchedulerCoordinator(region.ref, () => now, retryBase = 1.second, persistenceId = "scheduler-reply-ttl", replyActorTTL = Some(20.millis)),
        "reply-ttl-coordinator"
      )
      val replies = createTestProbe[SchedulerCoordinator.ScheduleReply]()
      val diagnostics = createTestProbe[SchedulerCoordinator.Diagnostics]()
      coordinator ! SchedulerCoordinator.Add(SchedulerCoordinator.Schedule("reply-ttl", "workflow-reply-ttl", FixedRate(1.hour)), replies.ref)
      replies.expectMessageType[SchedulerCoordinator.ScheduleAdded]

      coordinator ! SchedulerCoordinator.Fire("reply-ttl", 1L)
      region.expectMessageType[ShardingEnvelope[EventSourcedWorkflowActor.Command]]
      Thread.sleep(100)
      coordinator ! SchedulerCoordinator.GetDiagnostics(diagnostics.ref)
      diagnostics.expectMessage(SchedulerCoordinator.Diagnostics(replyActorCount = 0))
    }

    "correlate concurrent workflow replies even when acknowledgements arrive in reverse order" in {
      val region = createTestProbe[ShardingEnvelope[EventSourcedWorkflowActor.Command]]()
      val coordinator = spawn(SchedulerCoordinator(region.ref, () => now, persistenceId = "scheduler-correlation"), "correlation-coordinator")
      val replies = createTestProbe[SchedulerCoordinator.ScheduleReply]()
      val state = createTestProbe[SchedulerCoordinator.State]()
      coordinator ! SchedulerCoordinator.Add(SchedulerCoordinator.Schedule("correlation", "workflow-correlation", FixedRate(1.hour)), replies.ref)
      replies.expectMessageType[SchedulerCoordinator.ScheduleAdded]

      coordinator ! SchedulerCoordinator.Fire("correlation", 1L)
      coordinator ! SchedulerCoordinator.Fire("correlation", 2L)
      val deliveries = Vector.fill(2)(region.expectMessageType[ShardingEnvelope[EventSourcedWorkflowActor.Command]])
        .map(_.message.asInstanceOf[EventSourcedWorkflowActor.ExecuteScheduled])
        .map(delivery => delivery.triggerId -> delivery)
        .toMap
      val preparedDeadline = 2.seconds.fromNow
      var prepared = Vector.empty[SchedulerCoordinator.PendingTrigger]
      while (preparedDeadline.hasTimeLeft() && prepared.size != 2) {
        coordinator ! SchedulerCoordinator.GetState(state.ref)
        prepared = state.receiveMessage(200.millis).pendingTriggers
      }
      prepared.map(_.triggerId).toSet shouldBe Set("correlation-1", "correlation-2")
      deliveries("correlation-2").replyTo ! EventSourcedWorkflowActor.ExecutionAccepted("execution-2")
      deliveries("correlation-1").replyTo ! EventSourcedWorkflowActor.DuplicateExecution("correlation-1", "execution-1")

      val deadline = 2.seconds.fromNow
      var pending = prepared
      while (deadline.hasTimeLeft() && pending.nonEmpty) {
        coordinator ! SchedulerCoordinator.GetState(state.ref)
        pending = state.receiveMessage(200.millis).pendingTriggers
      }
      pending shouldBe empty
    }

    "add, update, pause, resume, list, and remove schedules" in {
      val region = createTestProbe[ShardingEnvelope[EventSourcedWorkflowActor.Command]]()
      val coordinator = spawn(SchedulerCoordinator(region.ref, () => now, persistenceId = "scheduler-crud"), "crud-coordinator")
      val replies = createTestProbe[SchedulerCoordinator.ScheduleReply]()
      val listed = createTestProbe[SchedulerCoordinator.Schedules]()
      val initial = SchedulerCoordinator.Schedule("crud", "workflow-crud", FixedRate(1.hour))

      coordinator ! SchedulerCoordinator.Add(initial, replies.ref)
      replies.expectMessage(SchedulerCoordinator.ScheduleAdded("crud"))
      coordinator ! SchedulerCoordinator.Pause("crud", replies.ref)
      replies.expectMessage(SchedulerCoordinator.ScheduleUpdated("crud"))
      coordinator ! SchedulerCoordinator.ListSchedules(listed.ref)
      listed.expectMessageType[SchedulerCoordinator.Schedules].values.head.enabled shouldBe false
      coordinator ! SchedulerCoordinator.Resume("crud", replies.ref)
      replies.expectMessage(SchedulerCoordinator.ScheduleUpdated("crud"))
      coordinator ! SchedulerCoordinator.Update(initial.copy(definition = FixedRate(2.hours)), replies.ref)
      replies.expectMessage(SchedulerCoordinator.ScheduleUpdated("crud"))
      coordinator ! SchedulerCoordinator.Remove("crud", replies.ref)
      replies.expectMessage(SchedulerCoordinator.ScheduleRemoved("crud"))
      coordinator ! SchedulerCoordinator.ListSchedules(listed.ref)
      listed.expectMessageType[SchedulerCoordinator.Schedules].values shouldBe empty
    }

    "pause and resume the durable schedule timer" in {
      val region = createTestProbe[ShardingEnvelope[EventSourcedWorkflowActor.Command]]()
      val coordinator = spawn(SchedulerCoordinator(region.ref, () => now, persistenceId = "scheduler-pause-resume"), "pause-resume-coordinator")
      val replies = createTestProbe[SchedulerCoordinator.ScheduleReply]()
      val schedule = SchedulerCoordinator.Schedule("pause-resume", "workflow-pause-resume", FixedRate(200.millis))

      coordinator ! SchedulerCoordinator.Add(schedule, replies.ref)
      replies.expectMessageType[SchedulerCoordinator.ScheduleAdded]
      coordinator ! SchedulerCoordinator.Pause("pause-resume", replies.ref)
      replies.expectMessage(SchedulerCoordinator.ScheduleUpdated("pause-resume"))
      region.expectNoMessage(300.millis)
      coordinator ! SchedulerCoordinator.Resume("pause-resume", replies.ref)
      replies.expectMessage(SchedulerCoordinator.ScheduleUpdated("pause-resume"))
      region.expectMessageType[ShardingEnvelope[EventSourcedWorkflowActor.Command]](1.second)
    }

    "persist a prepared trigger before delivering it and acknowledge accepted, duplicate, and already-running replies" in {
      val region = createTestProbe[ShardingEnvelope[EventSourcedWorkflowActor.Command]]()
      val coordinator = spawn(SchedulerCoordinator(region.ref, () => now, persistenceId = "scheduler-delivery"), "delivery-coordinator")
      val replies = createTestProbe[SchedulerCoordinator.ScheduleReply]()
      val state = createTestProbe[SchedulerCoordinator.State]()
      val schedule = SchedulerCoordinator.Schedule("daily", "workflow-1", FixedRate(1.hour))

      coordinator ! SchedulerCoordinator.Add(schedule, replies.ref)
      replies.expectMessage(SchedulerCoordinator.ScheduleAdded("daily"))
      Seq(
        EventSourcedWorkflowActor.ExecutionAccepted("execution-1"),
        EventSourcedWorkflowActor.DuplicateExecution("daily-2", "execution-1"),
        EventSourcedWorkflowActor.AlreadyRunning("execution-1")
      ).zipWithIndex.foreach { case (ack, index) =>
        val scheduledAt = index.toLong + 1L
        coordinator ! SchedulerCoordinator.Fire("daily", scheduledAt)
        val envelope = region.expectMessageType[ShardingEnvelope[EventSourcedWorkflowActor.Command]]
        val delivery = envelope.message.asInstanceOf[EventSourcedWorkflowActor.ExecuteScheduled]
        delivery.triggerId shouldBe s"daily-$scheduledAt"
        delivery.replyTo ! ack
        val deadline = 2.seconds.fromNow
        var pending = Vector(SchedulerCoordinator.PendingTrigger("not-cleared", "", "", 0L))
        while (deadline.hasTimeLeft() && pending.nonEmpty) {
          coordinator ! SchedulerCoordinator.GetState(state.ref)
          pending = state.receiveMessage(200.millis).pendingTriggers
        }
        pending shouldBe empty
      }
    }

    "redeliver an unacknowledged trigger using a bounded retry timer" in {
      val region = createTestProbe[ShardingEnvelope[EventSourcedWorkflowActor.Command]]()
      val coordinator = spawn(SchedulerCoordinator(region.ref, () => now, retryBase = 20.millis, persistenceId = "scheduler-retry"), "retry-coordinator")
      val replies = createTestProbe[SchedulerCoordinator.ScheduleReply]()
      coordinator ! SchedulerCoordinator.Add(SchedulerCoordinator.Schedule("retry", "workflow-2", FixedRate(1.hour)), replies.ref)
      replies.expectMessageType[SchedulerCoordinator.ScheduleAdded]

      coordinator ! SchedulerCoordinator.Fire("retry", 1L)
      region.expectMessageType[ShardingEnvelope[EventSourcedWorkflowActor.Command]]
      region.expectMessageType[ShardingEnvelope[EventSourcedWorkflowActor.Command]](500.millis)
    }

    "remove exhausted retries from pending state and stop redelivering" in {
      val region = createTestProbe[ShardingEnvelope[EventSourcedWorkflowActor.Command]]()
      val coordinator = spawn(SchedulerCoordinator(region.ref, () => now, retryBase = 1.millis, persistenceId = "scheduler-exhaustion"), "exhaustion-coordinator")
      val replies = createTestProbe[SchedulerCoordinator.ScheduleReply]()
      val state = createTestProbe[SchedulerCoordinator.State]()
      coordinator ! SchedulerCoordinator.Add(SchedulerCoordinator.Schedule("exhaust", "workflow-exhaust", FixedRate(1.hour)), replies.ref)
      replies.expectMessageType[SchedulerCoordinator.ScheduleAdded]

      coordinator ! SchedulerCoordinator.Fire("exhaust", 1L)
      (1 to 9).foreach(_ => region.expectMessageType[ShardingEnvelope[EventSourcedWorkflowActor.Command]](1.second))
      region.expectNoMessage(300.millis)
      coordinator ! SchedulerCoordinator.GetState(state.ref)
      state.expectMessageType[SchedulerCoordinator.State].pendingTriggers shouldBe empty
    }

    "cancel every pending retry when removing its schedule" in {
      val region = createTestProbe[ShardingEnvelope[EventSourcedWorkflowActor.Command]]()
      val coordinator = spawn(SchedulerCoordinator(region.ref, () => now, retryBase = 200.millis, persistenceId = "scheduler-remove-pending"), "remove-pending-coordinator")
      val replies = createTestProbe[SchedulerCoordinator.ScheduleReply]()
      val state = createTestProbe[SchedulerCoordinator.State]()
      coordinator ! SchedulerCoordinator.Add(SchedulerCoordinator.Schedule("remove-pending", "workflow-remove-pending", FixedRate(1.hour)), replies.ref)
      replies.expectMessageType[SchedulerCoordinator.ScheduleAdded]
      coordinator ! SchedulerCoordinator.Fire("remove-pending", 1L)
      coordinator ! SchedulerCoordinator.Fire("remove-pending", 2L)
      region.expectMessageType[ShardingEnvelope[EventSourcedWorkflowActor.Command]]
      region.expectMessageType[ShardingEnvelope[EventSourcedWorkflowActor.Command]]
      coordinator ! SchedulerCoordinator.Remove("remove-pending", replies.ref)
      replies.expectMessage(SchedulerCoordinator.ScheduleRemoved("remove-pending"))
      region.expectNoMessage(500.millis)
      coordinator ! SchedulerCoordinator.GetState(state.ref)
      state.expectMessageType[SchedulerCoordinator.State].pendingTriggers shouldBe empty
    }
  }
}
