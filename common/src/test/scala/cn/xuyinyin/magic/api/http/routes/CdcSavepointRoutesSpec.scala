package cn.xuyinyin.magic.api.http.routes

import cn.xuyinyin.magic.cdc.platform._
import cn.xuyinyin.magic.cdc.savepoint._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import scala.concurrent.Future
import spray.json._

class CdcSavepointRoutesSpec extends AnyWordSpecLike with Matchers with ScalatestRouteTest {
  private val digest="a"*64
  private val epoch=CaptureEpoch("capture-a",1,"lineage",2,None,digest,digest)
  private val header=CdcSavepointHeader("sp-1","capture-a","lineage",epoch,digest,0,"owner",1,"SEALED",Some(digest))
  private class Service extends CdcTaskService {
    def status=CdcPlatformStatus(true,"ACTIVE",None,0,2)
    def captures()=Future.successful(Vector.empty)
    def submit(r:CdcTaskRequest)=Future.failed[CdcTaskAcceptance](new CdcPlatformException("UNUSED"))
    def get(id:String)=Future.successful(None)
    def listAfter(a:Option[String],n:Int)=Future.successful(CdcTaskPage(Vector.empty,None))
    def cancel(id:String)=Future.failed[CdcTaskView](new CdcPlatformException("UNUSED"))
    override def createSavepoint(captureId:String,savepointId:String)=Future.successful(header)
    override def restoreSavepoint(savepointId:String)=Future.successful(Some(header->Vector.empty))
  }
  "CDC savepoint routes" should {
    "create and read only the sealed manifest" in {
      val route=new CdcTaskRoutes(Some(new Service))(system.dispatcher).routes
      Post("/api/v1/cdc-savepoints/capture-a/sp-1") ~> route ~> check { status shouldBe StatusCodes.Accepted }
      Get("/api/v1/cdc-savepoints/sp-1") ~> route ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String].parseJson.asJsObject.fields("savepoint").asJsObject.fields("status") shouldBe JsString("SEALED")
      }
    }
  }
}
