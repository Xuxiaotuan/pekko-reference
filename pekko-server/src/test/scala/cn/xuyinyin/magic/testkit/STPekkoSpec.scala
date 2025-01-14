package cn.xuyinyin.magic.testkit

import cn.xuyinyin.magic.common.FutureImplicits
import com.typesafe.scalalogging.Logger
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.{Duration, FiniteDuration}

/**
 * Actor standard testkit enhanced trait for use alongside
 * [[akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit]].
 *
 * @author Xuxiaotuan
 */
trait STPekkoSpec extends AnyWordSpecLike with BeforeAndAfterEach {

  protected val log: Logger = Logger(getClass)

  def sleep(delay: Duration): Unit = FutureImplicits.sleep(delay)

  /**
   * Create a TestProbe and use it directly on specified function.
   *
   * @example {{{
   * val actor = spawn(MyActor())
   * testProbe[Record] { probe =>
   *   actor ! GetValue(tp.ref)
   *   probe.expectMessage(Record(1, "Tulzscha"))
   * }
   * }}}
   */
  def testProbe[M](func: TestProbe[M] => Any)(implicit system: ActorSystem[_]): TestProbe[M] = {
    val probe = TestProbe[M]()
    func(probe)
    probe
  }

  /**
   * Shortened of [[testProbe]]
   */
  def probe[M](func: TestProbe[M] => Any)(implicit system: ActorSystem[_]): TestProbe[M] = testProbe(func)

  /**
   * Create a TestProbe and use it's ActorRef directly on specified function.
   * For example:
   *
   * @example {{{
   * val actor = spawn(MyActor())
   * testProbeRef[Record](actor ! GetValue(_)) expectMessage Record.default
   * }}}
   */
  def testProbeRef[M](func: ActorRef[M] => Any)(implicit system: ActorSystem[_]): TestProbe[M] = {
    val probe = TestProbe[M]()
    func(probe.ref)
    probe
  }

  /**
   * Shortened of [[testProbeRef]]
   */
  def probeRef[M](func: ActorRef[M] => Any)(implicit system: ActorSystem[_]): TestProbe[M] = testProbeRef(func)


  /**
   * Enhancement for TestProbe
   */
  implicit class RichTestProbe[M](probe: TestProbe[M]) {

    /**
     * Wrap the return of [[TestProbe.receiveMessage()]] to partition function.
     */
    def receiveMessagePF(assert: M => Any): M = {
      val r = probe.receiveMessage()
      assert(r)
      r
    }

    /**
     * Shortened of [[receiveMessagePF]]
     */
    def receivePF(assert: M => Any): M = receiveMessagePF(assert)

    /**
     * Wrap the return of [[TestProbe.receiveMessage(max: FiniteDuration)]]
     * to partition function.
     */
    def receiveMessagePFIn(max: FiniteDuration)(assert: M => Any): M = {
      val r = probe.receiveMessage(max)
      assert(r)
      r
    }

    /**
     * Shortened of [[receiveMessagePFIn]]
     */
    def receivePFIn(max: FiniteDuration)(assert: M => Any): M = receiveMessagePFIn(max)(assert)
  }


}
