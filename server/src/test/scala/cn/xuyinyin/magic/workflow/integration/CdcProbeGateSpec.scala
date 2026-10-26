package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.testkit.STSpec
import java.util.concurrent.atomic.AtomicReference

private[integration] object CdcProbeGate {
  def claim(gate:AtomicReference[String],kind:String):Boolean={
    val armed=gate.get()
    armed==kind && gate.compareAndSet(armed,"")
  }
}
class CdcProbeGateSpec extends STSpec {
  "a controller-armed cut point" should {
    "match equal text from stdin and take it exactly once" in {
      val fromStdin=new String("READ_OPEN".toCharArray)
      val gate=new AtomicReference[String](fromStdin)
      CdcProbeGate.claim(gate,"READ_OPEN") shouldBe true
      CdcProbeGate.claim(gate,"READ_OPEN") shouldBe false
    }
  }
}
