package cn.xuyinyin.magic.cluster

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._

class ReadinessCheckSpec extends ScalaTestWithActorTestKit(ConfigFactory.parseString("pekko.actor.provider=local"))
  with AnyWordSpecLike with Matchers {
  private implicit val ec: ExecutionContext = system.executionContext
  "readiness work" should {
    "share concurrent probes without starting more actual JDBC work" in {
      val calls=new AtomicInteger;val pending=Promise[Boolean]()
      val gate=new ReadinessCheck(HealthChecker.ReadinessProbes(()=>Future.successful(true),()=>Future.successful(true),()=>{calls.incrementAndGet();pending.future}),100.millis)
      try {val responses=Vector.fill(30)(gate.check());calls.get() shouldBe 1
        responses.foreach(r=>Await.result(r,1.second).ready shouldBe false)
      } finally pending.trySuccess(true)
    }
    "bound responses but retain the actual-work slot until late completion" in {
      val calls=new AtomicInteger;val pending=Promise[Boolean]()
      val gate=new ReadinessCheck(HealthChecker.ReadinessProbes(()=>Future.successful(true),()=>Future.successful(true),()=>{calls.incrementAndGet();pending.future}),50.millis)
      Await.result(gate.check(),1.second).ready shouldBe false
      Vector.fill(20)(gate.check()).foreach(r=>Await.result(r,1.second).ready shouldBe false)
      calls.get() shouldBe 1
      pending.success(true)
      createTestProbe[Boolean]().awaitAssert {Await.result(gate.check(),1.second).ready shouldBe true}
      calls.get() shouldBe 2
    }
    "treat synchronous exceptions and failed probes as unconfirmed without skipping other probes" in {
      val calls=new AtomicInteger
      val probes=HealthChecker.ReadinessProbes(()=>throw new IllegalStateException("unavailable"),()=>Future.failed(new IllegalStateException("no")),()=>{calls.incrementAndGet();Future.successful(true)})
      val result=Await.result(HealthChecker.readiness(probes),1.second)
      result shouldBe HealthChecker.ReadinessStatus(false,false,true);calls.get() shouldBe 1
    }
  }
}
