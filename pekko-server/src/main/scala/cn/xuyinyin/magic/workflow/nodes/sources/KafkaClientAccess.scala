package cn.xuyinyin.magic.workflow.nodes.sources

import com.typesafe.config.ConfigFactory
import cn.xuyinyin.magic.workflow.nodes.base.{SourceAcquisitionCleanupFailed, SourceResourceScope}
import org.apache.pekko.Done
import scala.concurrent.Promise
import scala.util.control.NonFatal
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.ActorAttributes
import org.apache.pekko.kafka.ConsumerSettings
import org.apache.pekko.stream.scaladsl.Source

import java.time.Duration
import java.util.Collections
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

final case class KafkaRecord(partition: Int, offset: Long, value: String)

private[sources] trait KafkaClientAccess {
  def inSession(resources: SourceResourceScope): KafkaClientAccess = this

  def partitionOffsets(topic: ResolvedKafkaTopic, reset: KafkaOffsetReset)
    (implicit ec: ExecutionContext): Future[Vector[KafkaPartitionBoundary]]

  def records(
    topic: ResolvedKafkaTopic,
    partition: Int,
    startOffset: Long,
    endOffset: Long
  ): Source[KafkaRecord, NotUsed]
}

private[sources] final case class KafkaPoll(records: Vector[KafkaRecord], position: Long)

private[sources] trait KafkaPartitionPoller {
  def poll(): KafkaPoll
  def close(): Unit
}

private[sources] trait KafkaPartitionPollerFactory {
  def open(
    settings: ConsumerSettings[String, String],
    topicPartition: TopicPartition,
    startOffset: Long
  ): KafkaPartitionPoller
}

private object DefaultKafkaPartitionPollerFactory extends KafkaPartitionPollerFactory {
  override def open(
    settings: ConsumerSettings[String, String],
    topicPartition: TopicPartition,
    startOffset: Long
  ): KafkaPartitionPoller = {
    val consumer = settings.createKafkaConsumer()
    try {
      consumer.assign(Collections.singleton(topicPartition))
      consumer.seek(topicPartition, startOffset)
      new KafkaPartitionPoller {
        override def poll(): KafkaPoll = {
          val records = consumer.poll(Duration.ofMillis(settings.pollTimeout.toMillis))
            .records(topicPartition)
            .asScala
            .map(record => KafkaRecord(record.partition(), record.offset(), record.value()))
            .toVector
          KafkaPoll(records, consumer.position(topicPartition))
        }

        override def close(): Unit = consumer.close(settings.getCloseTimeout)
      }
    } catch {
      case failure: Throwable =>
        try consumer.close(settings.getCloseTimeout)
        catch { case NonFatal(cleanup) => throw new SourceAcquisitionCleanupFailed(failure, cleanup) }
        throw failure
    }
  }
}

private final class KafkaPollingState(
  val poller: KafkaPartitionPoller,
  var position: Long
)

private[sources] final class PekkoKafkaClientAccess(
  pollerFactory: KafkaPartitionPollerFactory = DefaultKafkaPartitionPollerFactory,
  resources: Option[SourceResourceScope] = None
) extends KafkaClientAccess {
  override def inSession(scope: SourceResourceScope): KafkaClientAccess = new PekkoKafkaClientAccess(pollerFactory, Some(scope))

  override def partitionOffsets(topic: ResolvedKafkaTopic, reset: KafkaOffsetReset)
    (implicit ec: ExecutionContext): Future[Vector[KafkaPartitionBoundary]] = Future {
    val settings = consumerSettings(topic, clientId = s"${groupId(topic)}-metadata")
    val consumer = settings.createKafkaConsumer()
    try {
      val partitions = consumer.partitionsFor(topic.topic).asScala
        .map(info => new TopicPartition(topic.topic, info.partition()))
        .sortBy(_.partition())
        .toVector
      val beginnings = consumer.beginningOffsets(partitions.asJava).asScala
      val ends = consumer.endOffsets(partitions.asJava).asScala
      partitions.map { partition =>
        val beginning = beginnings(partition).longValue()
        val end = ends(partition).longValue()
        val start = reset match {
          case KafkaOffsetReset.Earliest => beginning
          case KafkaOffsetReset.Latest => end
        }
        KafkaPartitionBoundary(partition.partition(), start, end)
      }
    } finally consumer.close(settings.getCloseTimeout)
  }

  override def records(
    topic: ResolvedKafkaTopic,
    partition: Int,
    startOffset: Long,
    endOffset: Long
  ): Source[KafkaRecord, NotUsed] = {
    if (startOffset >= endOffset) Source.empty
    else {
      val settings = consumerSettings(topic, clientId = s"${groupId(topic)}-$partition")
      val topicPartition = new TopicPartition(topic.topic, partition)
      Source.unfoldResource[Vector[KafkaRecord], KafkaPollingState](
        create = () => {
          val closed = Promise[Done]()
          // Reserve before opening; close stays on the poller's own stream thread.
          resources.foreach { scope =>
            scope.acquire(() => Future.successful(closed), (p: Promise[Done]) => p.future).value.foreach(_.get)
          }
          try {
            val underlying = pollerFactory.open(settings, topicPartition, startOffset)
            new KafkaPollingState(new KafkaPartitionPoller {
              def poll(): KafkaPoll = underlying.poll()
              def close(): Unit = try {
                underlying.close()
                closed.trySuccess(Done)
              } catch { case NonFatal(e) => closed.tryFailure(e); throw e }
            }, startOffset)
          } catch {
            case e: SourceAcquisitionCleanupFailed => closed.tryFailure(e); throw e
            case NonFatal(e) => closed.trySuccess(Done); throw e
          }
        },
        read = state => {
          if (state.position >= endOffset) None
          else {
            val polled = state.poller.poll()
            state.position = polled.position
            Some(polled.records.filter(record =>
              record.partition == partition &&
                record.offset >= startOffset &&
                record.offset < endOffset
            ))
          }
        },
        close = state => state.poller.close()
      )
        .mapConcat(_.toList)
        .withAttributes(ActorAttributes.dispatcher(settings.dispatcher))
    }
  }

  private def consumerSettings(topic: ResolvedKafkaTopic, clientId: String): ConsumerSettings[String, String] =
    ConsumerSettings(
      ConfigFactory.load().getConfig(ConsumerSettings.configPath),
      new StringDeserializer,
      new StringDeserializer
    )
      .withBootstrapServers(topic.bootstrapServers)
      .withGroupId(groupId(topic))
      .withClientId(clientId)
      .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none")

  private def groupId(topic: ResolvedKafkaTopic): String =
    s"pekko-bounded-${topic.topic.replaceAll("[^A-Za-z0-9._-]", "-")}".take(200)
}
