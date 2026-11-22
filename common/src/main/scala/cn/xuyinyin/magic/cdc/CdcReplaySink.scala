package cn.xuyinyin.magic.cdc

import cn.xuyinyin.magic.connectors.mysql.source.capture.CdcCapturedBatch
import scala.concurrent.{ExecutionContext,Future}

/** A successful result means the target commit has completed. */
private[magic] trait CdcReplaySink {
  def commitBatch(subscriptionId:String,executionId:String,batch:CdcCapturedBatch,log:String=>Unit)
    (implicit ec:ExecutionContext):Future[Unit]
}
