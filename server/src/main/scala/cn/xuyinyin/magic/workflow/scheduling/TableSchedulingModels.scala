package cn.xuyinyin.magic.workflow.scheduling

import cn.xuyinyin.magic.common.CborSerializable

final case class TableResourceDemand(sourceInstance: String, targetInstance: String, parallelism: Int, pageBytes: Int) extends CborSerializable {
  require(sourceInstance.nonEmpty && targetInstance.nonEmpty && sourceInstance.length <= 255 && targetInstance.length <= 255)
  require(parallelism > 0 && parallelism <= 8 && pageBytes > 0 && pageBytes <= 16 * 1024 * 1024)
  def pageReservation: Long = Math.multiplyExact(parallelism.toLong, Math.multiplyExact(pageBytes.toLong, 3L))
  def connections: Map[String, Int] = Vector(sourceInstance -> parallelism, targetInstance -> parallelism)
    .groupMapReduce(_._1)(_._2)(_ + _)
}
final case class SchedulingLimits(maxTables: Int, maxTablesPerNode: Int, maxConnectionsPerInstance: Int,
  maxStarts: Int, maxPageReservation: Long, candidatePage: Int) {
  require(maxTables > 0 && maxTablesPerNode > 0 && maxConnectionsPerInstance > 0 && maxStarts > 0 && maxPageReservation > 0)
  require(candidatePage > 0 && candidatePage <= 1000)
}
final case class ScheduledTableDefinition(workflowId: String, revision: Long, workflowJson: String, demand: TableResourceDemand) extends CborSerializable {
  require(workflowId.nonEmpty && workflowId.length <= 255 && revision > 0 && workflowJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 65536)
}
final case class ScheduledTable(definition: ScheduledTableDefinition, state: String, executionId: Option[String],
  requestKey: Option[String], queueOrder: Long, notBefore: Long, grantId: Option[String], owner: Option[String]) extends CborSerializable {
  def workflowId: String = definition.workflowId
}
final case class TableGrant(task: ScheduledTable, id: String) extends CborSerializable
final case class ScheduledTableSummary(workflowId: String, revision: Long, state: String,
  executionId: Option[String], queueOrder: Long, demand: TableResourceDemand) extends CborSerializable
