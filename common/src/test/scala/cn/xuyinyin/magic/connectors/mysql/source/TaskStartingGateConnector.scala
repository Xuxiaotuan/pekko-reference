package cn.xuyinyin.magic.connectors.mysql.source

import io.debezium.config.Configuration
import io.debezium.connector.mysql.{MySqlConnector, MySqlConnectorTask, MySqlOffsetContext, MySqlPartition}
import io.debezium.pipeline.ChangeEventSourceCoordinator
import java.util.concurrent.CountDownLatch
import org.apache.kafka.connect.connector.Task

/** Test-only gate in the MySQL task start, before delegating to its original implementation. */
object TaskStartingGate {
  val entered = new CountDownLatch(1)
  val release = new CountDownLatch(1)
}
final class TaskStartingGateConnector extends MySqlConnector {
  override def taskClass(): Class[_ <: Task] = classOf[TaskStartingGateTask]
}
final class TaskStartingGateTask extends MySqlConnectorTask {
  override def start(config: Configuration): ChangeEventSourceCoordinator[MySqlPartition, MySqlOffsetContext] = {
    TaskStartingGate.entered.countDown()
    TaskStartingGate.release.await()
    super.start(config)
  }
}
