package cn.xuyinyin.magic.workflow.bootstrap

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import spray.json._

class BootstrapProtocolSpec extends AnyWordSpec with Matchers {
  "bootstrap fold" should {
    "project old and new PK members independently without claiming source group identity" in {
      val f=new BootstrapFold(Some("0"),"100",10000)
      f.snapshot(Vector(JsObject("id"->JsNumber(50),"v"->JsString("old"))))
      f.event("d",JsObject("id"->JsNumber(50)),None)
      f.event("c",JsObject("id"->JsNumber(150)),Some(JsObject("id"->JsNumber(150),"v"->JsString("moved"))))
      f.result shouldBe empty
      f.event("c",JsObject("id"->JsNumber(30)),Some(JsObject("id"->JsNumber(30),"v"->JsString("new"))))
      f.result shouldBe Vector(JsObject("id"->JsNumber(30),"v"->JsString("new")))
    }
    "bound retained logical bytes and reject incomplete row images" in {
      intercept[IllegalArgumentException](new BootstrapFold(None,"100",4).snapshot(Vector(JsObject("id"->JsNumber(1)))))
    }
  }
  "bootstrap phases" should {
    "separate unfinished views, sealed inputs, replay and activation" in {
      BootstrapPhases.allowed("QUEUED","SCANNING") shouldBe true
      BootstrapPhases.allowed("SCANNING","STREAMING") shouldBe false
      BootstrapPhases.allowed("ACTIVATING","STREAMING") shouldBe true
      BootstrapPhases.allowed("CANCELING","STREAMING") shouldBe false
      BootstrapPhases.allowed("CANCELED","SCANNING") shouldBe false
    }
  }
}
