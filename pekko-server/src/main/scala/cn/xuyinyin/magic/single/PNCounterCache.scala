package cn.xuyinyin.magic.single

import cn.xuyinyin.magic.config.PekkoConfig
import cn.xuyinyin.magic.single.PekkoGc.UpdateGCLimit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.TypedActorContextOps
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.cluster.ddata.typed.scaladsl.{DistributedData, Replicator}
import org.apache.pekko.cluster.ddata.{PNCounter, PNCounterKey, SelfUniqueAddress}

/**
 * 定义在包私有作用域下的对象PumpPNGCounterCache，用于管理与PNCounter相关的缓存操作逻辑
 */
private[single] object PNGCounterCache {

  sealed trait Command
  final case class Increment(value: Int)            extends Command
  final case class Decrement(value: Int)            extends Command
  final case class GetValue(replyTo: ActorRef[Int]) extends Command

  sealed private trait InternalCommand                                                                      extends Command
  final private case class InternalUpdate(rsp: Replicator.UpdateResponse[PNCounter])                        extends InternalCommand
  final private case class InternalGetValue(rsp: Replicator.GetResponse[PNCounter], replyTo: ActorRef[Int]) extends InternalCommand
  final private case class InternalSubscribe(rsp: Replicator.SubscribeResponse[PNCounter])                  extends InternalCommand
  final private case object AutoCorrect                                                                     extends InternalCommand
  final private case class InternalGetThenAutoCorrect(rsp: Replicator.GetResponse[PNCounter])               extends InternalCommand

  // apply方法用于创建PumpPNGCounterCache的行为实例，接受一个PNCounterKey作为参数
  def apply(key: PNCounterKey): Behavior[Command] = Behaviors.setup { ctx =>
    // 隐式获取当前节点的SelfUniqueAddress，通过DistributedData获取系统相关信息
    implicit val node: SelfUniqueAddress = DistributedData(ctx.system).selfUniqueAddress

    // 从PekkoConfig中获取全局任务限制值，用于后续可能的自动校正等操作
    val limit = PekkoConfig.root.getInt("pekko.global-task-limit")

    // 使用DistributedData创建一个带有Replicator消息适配器的行为，用于处理与分布式数据相关的操作
    val behavior = DistributedData.withReplicatorMessageAdapter[Command, PNCounter] { replicator =>
      // 订阅指定的PNCounterKey，当数据有变化时会收到相应的响应，这里传入处理响应的函数InternalSubscribe.apply
      replicator.subscribe(key, InternalSubscribe.apply)

      // 定义处理外部命令的行为逻辑
      Behaviors.receiveMessage {
        case Increment(value) =>
          // 对于Increment命令，通过Replicator发起一个更新操作
          // 创建一个Replicator.Update请求，指定要更新的键（key）、初始值（PNCounter.empty）、写入模式（Replicator.WriteLocal）
          // 以及回复处理函数（_.increment(value)用于增加指定的值），并将回复处理函数应用到结果上
          // 最后将请求发送出去，并指定处理更新响应的函数为InternalUpdate.apply
          replicator.askUpdate(
            replyTo => Replicator.Update(key, PNCounter.empty, Replicator.WriteLocal, replyTo)(_.increment(value)),
            InternalUpdate.apply)
          // 在发送更新请求后，保持当前行为不变
          Behaviors.same

        case Decrement(value) =>
          // 对于Decrement命令，类似Increment命令的处理方式，只是更新操作是减少指定的值
          replicator.askUpdate(
            replyTo => Replicator.Update(key, PNCounter.empty, Replicator.WriteLocal, replyTo)(_.decrement(value)),
            InternalUpdate.apply)
          Behaviors.same

        case GetValue(replyTo) =>
          // 对于GetValue命令，通过Replicator发起一个获取值的操作
          // 创建一个Replicator.Get请求，指定要获取值的键（key）、读取模式（Replicator.ReadLocal）以及回复处理函数
          // 并将获取值的响应处理函数InternalGetValue应用到结果上，将请求发送出去
          replicator.askGet(replyTo => Replicator.Get(key, Replicator.ReadLocal, replyTo), value => InternalGetValue(value, replyTo))
          Behaviors.same

        // 处理内部命令的行为逻辑
        case internal: InternalCommand =>
          internal match {
            case InternalUpdate(_) => Behaviors.same
            // 当收到InternalGetValue且获取值成功（对应指定的键key）时
            case InternalGetValue(rsp @ Replicator.GetSuccess(`key`), replyTo) =>
              // 将获取到的值发送给回复的ActorRef
              replyTo ! rsp.get(key).value.intValue
              Behaviors.same
            case InternalGetValue(_, _) => Behaviors.unhandled

            // 当收到InternalSubscribe且数据有变化（对应指定的键key）时
            case InternalSubscribe(rsp @ Replicator.Changed(`key`)) =>
              // 获取变化后的值并转换为Int类型
              val value = rsp.get(key).value.toInt
              // 将更新后的任务限制值发送给父Actor（通过将当前TypedActorContext转换为Classic上下文来获取父Actor）
              ctx.toClassic.parent ! UpdateGCLimit(value)
              Behaviors.same

            case AutoCorrect =>
              // 当触发AutoCorrect命令时，通过Replicator发起一个获取值的操作
              replicator.askGet(replyTo => Replicator.Get(key, Replicator.ReadLocal, replyTo), value => InternalGetThenAutoCorrect(value))
              Behaviors.same

            case InternalGetThenAutoCorrect(rsp @ Replicator.GetSuccess(`key`)) =>
              Behaviors.same

            case InternalGetThenAutoCorrect(rsp @ Replicator.NotFound(`key`)) =>
              // 当获取值未找到（对应指定的键key）时，向自己发送一个Increment命令，增加的值为之前获取的全局任务限制值limit
              ctx.self ! Increment(limit)
              Behaviors.same

            case _ => Behaviors.unhandled
          }
      }
    }
    // 在初始化完成后，向自己发送一个AutoCorrect命令，触发自动校正相关的操作逻辑
    ctx.self ! AutoCorrect
    behavior
  }
}
