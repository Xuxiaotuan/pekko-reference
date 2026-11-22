package cn.xuyinyin.magic.workflow.scheduler

import cn.xuyinyin.magic.workflow.scheduler.ScheduleCalculator.{CronSchedule, FixedRate}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import scala.concurrent.duration._

class ScheduleCalculatorSpec extends AnyWordSpec with Matchers {
  "ScheduleCalculator" should {
    "calculate the next fixed-rate occurrence" in {
      val now = Instant.parse("2026-08-29T00:00:00Z")

      ScheduleCalculator.next(FixedRate(1.hour), now) shouldBe Instant.parse("2026-08-29T01:00:00Z")
    }

    "calculate the next real cron occurrence" in {
      val now = Instant.parse("2026-08-29T00:30:00Z")

      ScheduleCalculator.next(CronSchedule("0 * * * *"), now) shouldBe Instant.parse("2026-08-29T01:00:00Z")
    }

    "preserve a valid six-field cron expression" in {
      ScheduleCalculator.normalize("0 0 0 * * ?") shouldBe "0 0 0 * * ?"
      ScheduleCalculator.next(CronSchedule("0 0 1 * * ?"), Instant.parse("2026-08-29T00:30:00Z")) shouldBe
        Instant.parse("2026-08-29T01:00:00Z")
    }

    "reject invalid cron expressions" in {
      ScheduleCalculator.validate(CronSchedule("not-cron")).isLeft shouldBe true
    }
  }
}
