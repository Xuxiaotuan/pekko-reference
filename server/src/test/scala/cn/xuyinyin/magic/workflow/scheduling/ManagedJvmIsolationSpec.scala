package cn.xuyinyin.magic.workflow.scheduling

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class ManagedJvmIsolationSpec extends AnyWordSpec with Matchers {
  "managed child isolation" should {
    "refuse an unowned identity and produce a receipt only after its exact child has exited" in {
      val manager=new ManagedJvmIsolation("test-domain",1)
      val child=manager.launch("boot-one",new ProcessBuilder("/bin/cat"))
      try {
        manager.confirm(child.identity,"operation",terminate=false) shouldBe None
        manager.confirm(child.identity.copy(startIdentity="wrong"),"operation",terminate=true) shouldBe None
        child.process.isAlive shouldBe true
        val receipt=manager.confirm(child.identity,"operation",terminate=true).get
        child.process.isAlive shouldBe false
        receipt.identity shouldBe child.identity
        receipt.operationId shouldBe "operation"
        manager.confirm(child.identity,"operation",terminate=false).get.identity shouldBe child.identity
      } finally if(child.process.isAlive)child.process.destroyForcibly().waitFor()
    }
    "bound managed workers and never adopt an arbitrary PID" in {
      val manager=new ManagedJvmIsolation("test-domain",1)
      val child=manager.launch("boot-one",new ProcessBuilder("/bin/cat"))
      try {
        intercept[IllegalStateException](manager.launch("boot-two",new ProcessBuilder("/bin/cat")))
        manager.confirm(SnapshotOwnerIdentity("foreign","test-domain",ProcessHandle.current().pid(),"anything"),"operation",true) shouldBe None
      } finally {child.process.destroyForcibly().waitFor();()}
    }
  }
}
