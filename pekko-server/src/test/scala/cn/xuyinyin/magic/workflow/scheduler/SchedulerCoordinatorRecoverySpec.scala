package cn.xuyinyin.magic.workflow.scheduler

import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.scheduler.ScheduleCalculator.FixedRate
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import java.sql.DriverManager
import scala.io.Source
import scala.concurrent.duration._

object SchedulerCoordinatorRecoverySpec {
  private val h2Url = s"jdbc:h2:mem:scheduler-coordinator-recovery-${java.util.UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1"

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

class SchedulerCoordinatorRecoverySpec extends ScalaTestWithActorTestKit(SchedulerCoordinatorRecoverySpec.config) with AnyWordSpecLike with Matchers {
  "SchedulerCoordinator recovery" should {
    "rebuild an enabled schedule timer after restart" in {
      val region = createTestProbe[ShardingEnvelope[EventSourcedWorkflowActor.Command]]()
      val now = Instant.parse("2026-08-29T00:00:00Z")
      val replies = createTestProbe[SchedulerCoordinator.ScheduleReply]()
      val first = spawn(SchedulerCoordinator(region.ref, () => now, retryBase = 1.second, persistenceId = "scheduler-timer-recovery"), "timer-before-recovery")
      first ! SchedulerCoordinator.Add(SchedulerCoordinator.Schedule("timer", "workflow-timer", FixedRate(1.second)), replies.ref)
      replies.expectMessageType[SchedulerCoordinator.ScheduleAdded]
      region.expectNoMessage(100.millis)
      testKit.stop(first)

      val recovered = spawn(SchedulerCoordinator(region.ref, () => now, retryBase = 1.second, persistenceId = "scheduler-timer-recovery"), "timer-after-recovery")
      val envelope = region.expectMessageType[ShardingEnvelope[EventSourcedWorkflowActor.Command]](3.seconds)
      envelope.message.asInstanceOf[EventSourcedWorkflowActor.ExecuteScheduled].triggerId shouldBe "timer-1787961601000"
      testKit.stop(recovered)
    }

    "rebuild timers and redeliver a pending trigger after restart" in {
      val region = createTestProbe[ShardingEnvelope[EventSourcedWorkflowActor.Command]]()
      val now = Instant.parse("2026-08-29T00:00:00Z")
      val replies = createTestProbe[SchedulerCoordinator.ScheduleReply]()
      val first = spawn(SchedulerCoordinator(region.ref, () => now, retryBase = 1.second, persistenceId = "scheduler-pending-recovery"), "coordinator-before-recovery")
      first ! SchedulerCoordinator.Add(SchedulerCoordinator.Schedule("recovery", "workflow-3", FixedRate(1.hour)), replies.ref)
      replies.expectMessageType[SchedulerCoordinator.ScheduleAdded]
      first ! SchedulerCoordinator.Fire("recovery", 1L)
      region.expectMessageType[ShardingEnvelope[EventSourcedWorkflowActor.Command]]
      testKit.stop(first)

      val recovered = spawn(SchedulerCoordinator(region.ref, () => now, retryBase = 1.second, persistenceId = "scheduler-pending-recovery"), "coordinator-after-recovery")
      val envelope = region.expectMessageType[ShardingEnvelope[EventSourcedWorkflowActor.Command]](3.seconds)
      envelope.message.asInstanceOf[EventSourcedWorkflowActor.ExecuteScheduled].triggerId shouldBe "recovery-1"
      testKit.stop(recovered)
    }
  }
}
