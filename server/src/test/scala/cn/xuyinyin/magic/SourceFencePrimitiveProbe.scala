package cn.xuyinyin.magic

import cn.xuyinyin.magic.connectors.mysql.source.capture.{CaptureControlFence,CaptureFenceBinding,CaptureFenceReceipt}
import java.lang.reflect.InvocationTargetException
import java.sql.Connection

/** Test-only access to source primitives; never a substitute for a consumed permit. */
private[magic] object SourceFencePrimitiveProbe {
  private def call(target:AnyRef,name:String,args:Seq[AnyRef]):AnyRef={
    val methods=target.getClass.getDeclaredMethods.filter(m=>m.getName.endsWith(name) && m.getParameterCount==args.size)
    require(methods.length==1,s"$name primitive unavailable")
    val method=methods.head;method.setAccessible(true)
    try method.invoke(target,args:_*) catch {case e:InvocationTargetException=>throw e.getCause}
  }
  def release(c:Connection,r:CaptureFenceReceipt,id:String,digest:String):Unit={call(CaptureControlFence,"releasePrimitive",Seq(c,r,id,digest));()}
  def release(c:CaptureControlFence.ProtectedController,r:CaptureFenceReceipt,id:String,digest:String):Unit={call(c,"releasePrimitive",Seq(r,id,digest));()}
  def write(c:Connection,b:CaptureFenceBinding):CaptureFenceReceipt=call(CaptureControlFence,"writeMarker",Seq(c,b)).asInstanceOf[CaptureFenceReceipt]
  def write(c:CaptureControlFence.ProtectedController,b:CaptureFenceBinding):CaptureFenceReceipt=call(c,"writePrimitive",Seq(b)).asInstanceOf[CaptureFenceReceipt]
}
