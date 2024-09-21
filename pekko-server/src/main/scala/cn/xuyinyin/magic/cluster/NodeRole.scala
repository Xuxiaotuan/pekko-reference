package cn.xuyinyin.magic.cluster

import cn.xuyinyin.magic.common.SeqImplicits.EnhanceSeq

import scala.collection.mutable

/**
 * @author : Xuxiaotuan
 * @since : 2024-09-21 22:18
*/
object NodeRole {

  // Aggregation roles
  private lazy val ServerAll = "server-all"
  private lazy val Dispatcher = "dispatcher"
  private lazy val Worker = "worker"
  private lazy val HTTP = "http"

  // Aggregation roles -> Details roles
  private lazy val ServerAllRoles = DispatcherRoles ++ WorkerRoles ++ HttpRoles
  private lazy val DispatcherRoles = Seq(DataPumpDispatcher)
  private lazy val WorkerRoles = Seq(DataPumpWorker)
  private lazy val HttpRoles = Seq(HttpCommand, HttpQuery)

  // Details roles
  private val DataPumpDispatcher = "dispatcher-pump"
  private val DataPumpWorker = "worker-pump"
  private val HttpCommand = "http-cmd"
  private val HttpQuery = "http-query"

  /**
   * Flatten pekko reference node roles.
   */
  def flattenRoles(roles: Seq[String]): Seq[String] = {
    val rs = mutable.Set[String]()
    if (roles ? ServerAll) rs ++= ServerAllRoles
    if (roles ? Dispatcher) rs ++= DispatcherRoles
    if (roles ? Worker) rs ++= WorkerRoles
    if (roles ? HTTP) rs ++= HttpRoles
    rs.toSeq
  }

}