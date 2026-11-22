package cn.xuyinyin.magic.common

import com.typesafe.scalalogging.Logger
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.cluster.ddata.Replicator.{GetResponse, GetSuccess, NotFound, UpdateResponse, UpdateSuccess}
import org.apache.pekko.cluster.ddata.typed.scaladsl.Replicator._
import org.apache.pekko.cluster.ddata.typed.scaladsl.{DistributedData, Replicator, ReplicatorMessageAdapter}
import org.apache.pekko.cluster.ddata.{Key, ReplicatedData, SelfUniqueAddress}

/**
 * org.apache.pekko Distributed Data wrapper for simplifying the implementation of the relevant Actor.
 *
 * @author Xuxiaotuan
 */
trait DDataHelper[DData <: ReplicatedData] {

  protected type Cache     = DData
  protected type UpdateRsp = UpdateResponse[DData]
  protected type GetRsp    = GetResponse[DData]

  private val logger = Logger(getClass)

  sealed trait IntlRsp {
    def onSuccess[Command](success: DData => _, notFound: () => _)(implicit cacheKey: Key[DData]): Behavior[Command] = this match {
      case c: IntlGetRsp =>
        c.rsp match {
          case r: GetSuccess[DData] =>
            success(r.get(cacheKey))
            Behaviors.same[Command]
          case r: NotFound[DData] =>
            notFound()
            Behaviors.same[Command]
          case e =>
            logger.debug(s"org.apache.pekko DData handled IntlGetRsp message, ${e}")
            Behaviors.unhandled[Command]
        }
      case c: IntlUpdateRsp =>
        c.rsp match {
          case _: UpdateSuccess[DData] => Behaviors.same[Command]
          case e =>
            logger.debug(s"org.apache.pekko DData handled IntlUpdateRsp message, ${e}")
            Behaviors.unhandled[Command]
        }
    }
  }

  protected def selfUniqueAddress(implicit ctx: ActorContext[_]): SelfUniqueAddress = DistributedData(ctx.system).selfUniqueAddress

  protected trait IntlGetRsp extends IntlRsp {
    def rsp: GetResponse[DData]
  }

  protected trait IntlUpdateRsp extends IntlRsp {
    def rsp: UpdateResponse[DData]
  }

  /**
   * [[ReplicatorMessageAdapter.askUpdate]]
   */
  protected def update[Command](
      writeConsistency: WriteConsistency,
      responseAdapter: Replicator.UpdateResponse[DData] => Command
    )(modify: DData => DData
    )(implicit replicator: ReplicatorMessageAdapter[Command, DData],
      initCache: DData,
      cacheKey: Key[DData]): Behavior[Command] = {
    replicator.askUpdate(Replicator.Update(cacheKey, initCache, writeConsistency, _)(modify), responseAdapter)
    Behaviors.same
  }

  /**
   * [[ReplicatorMessageAdapter.askUpdate]]
   */
  protected def update[Command](
      responseAdapter: Replicator.UpdateResponse[DData] => Command
    )(modify: DData => DData
    )(implicit replicator: ReplicatorMessageAdapter[Command, DData],
      initCache: DData,
      cacheKey: Key[DData],
      writeConsistency: WriteConsistency): Behavior[Command] = {
    replicator.askUpdate(Replicator.Update(cacheKey, initCache, writeConsistency, _)(modify), responseAdapter)
    Behaviors.same
  }

  /**
   * [[ReplicatorMessageAdapter.askGet]]
   */
  protected def get[Command](
      readConsistency: ReadConsistency,
      responseAdapter: Replicator.GetResponse[DData] => Command
    )(implicit replicator: ReplicatorMessageAdapter[Command, DData],
      cacheKey: Key[DData]): Behavior[Command] = {
    replicator.askGet(Replicator.Get(cacheKey, readConsistency, _), responseAdapter)
    Behaviors.same
  }

  /**
   * [[ReplicatorMessageAdapter.askGet]]
   */
  protected def get[Command](
      responseAdapter: Replicator.GetResponse[DData] => Command
    )(implicit replicator: ReplicatorMessageAdapter[Command, DData],
      cacheKey: Key[DData],
      readConsistency: ReadConsistency): Behavior[Command] = {
    replicator.askGet(Replicator.Get(cacheKey, readConsistency, _), responseAdapter)
    Behaviors.same
  }

  protected def cacheAdapter[Command](withAdapter: ReplicatorMessageAdapter[Command, DData] => Behavior[Command]): Behavior[Command] = {
    DistributedData.withReplicatorMessageAdapter[Command, DData](withAdapter(_))
  }

}
