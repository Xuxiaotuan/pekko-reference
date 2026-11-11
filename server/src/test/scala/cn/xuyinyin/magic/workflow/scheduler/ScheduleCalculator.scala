package cn.xuyinyin.magic.workflow.scheduler

import cn.xuyinyin.magic.common.CborSerializable
import cron4s.Cron
import cron4s.datetime.{DateTimeCron, IsDateTime}
import cron4s.expr.CronExpr
import cron4s.lib.javatime._
import com.fasterxml.jackson.annotation.{JsonSubTypes, JsonTypeInfo}

import java.time.{Instant, LocalDateTime, ZoneOffset}
import scala.concurrent.duration.FiniteDuration

object ScheduleCalculator {
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
  @JsonSubTypes(Array(
    new JsonSubTypes.Type(value = classOf[FixedRate], name = "fixed-rate"),
    new JsonSubTypes.Type(value = classOf[CronSchedule], name = "cron")
  ))
  sealed trait Definition extends CborSerializable
  final case class FixedRate(interval: FiniteDuration) extends Definition
  final case class CronSchedule(expression: String) extends Definition

  def validate(schedule: Definition): Either[String, Unit] = schedule match {
    case FixedRate(interval) if interval.length > 0L => Right(())
    case FixedRate(_) => Left("fixed-rate interval must be positive")
    case CronSchedule(expression) => Cron.parse(normalize(expression)).left.map(_.toString).map(_ => ())
  }

  def next(schedule: Definition, from: Instant): Instant = schedule match {
    case FixedRate(interval) => from.plusMillis(interval.toMillis)
    case CronSchedule(expression) =>
      val cron = Cron.parse(normalize(expression)).fold(error => throw new IllegalArgumentException(error.toString), identity)
      val local = LocalDateTime.ofInstant(from, ZoneOffset.UTC)
      DateTimeCron[CronExpr]
        .next(cron, implicitly[IsDateTime[LocalDateTime]])(local)
        .map(_.toInstant(ZoneOffset.UTC))
        .getOrElse(throw new IllegalArgumentException(s"cron has no next occurrence: $expression"))
  }

  /** cron4s uses a seconds field and '?' for an unconstrained day field. */
  private[scheduler] def normalize(expression: String): String = {
    val fields = expression.trim.split("\\s+").filter(_.nonEmpty)
    if (fields.length != 5) expression
    else {
      val cron4sFields = Array("0") ++ fields
      val dayOfMonth = cron4sFields(3)
      val dayOfWeek = cron4sFields(5)
      if (dayOfMonth == "*" && dayOfWeek == "*") cron4sFields(5) = "?"
      else if (dayOfMonth == "*") cron4sFields(3) = "?"
      else if (dayOfWeek == "*") cron4sFields(5) = "?"
      cron4sFields.mkString(" ")
    }
  }
}
