package cn.xuyinyin.magic.connectors.mysql.source

import cn.xuyinyin.magic.connectors.mysql.source.capture.CaptureSnapshotMonitor
import cn.xuyinyin.magic.testkit.STSpec
import java.lang.management.ManagementFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.management._

class CaptureConnectionHealthSpec extends STSpec {
  "current capture connection health" should {
    "separate a missing, connected and disconnected streaming MBean" in {
      val server=ManagementFactory.getPlatformMBeanServer
      val id="health_"+UUID.randomUUID().toString.replace('-','_')
      val monitor=CaptureSnapshotMonitor.platform(id)
      monitor.beforeStart();monitor.streamingConnected shouldBe None
      val connected=new AtomicBoolean(true)
      val name=new ObjectName(s"debezium.mysql:type=connector-metrics,context=streaming,server=$id")
      val bean=new DynamicMBean {
        def getAttribute(n:String):AnyRef=if(n=="Connected")Boolean.box(connected.get())else throw new AttributeNotFoundException(n)
        def setAttribute(a:Attribute):Unit=throw new AttributeNotFoundException(a.getName)
        def getAttributes(names:Array[String]):AttributeList={val list=new AttributeList();names.foreach(n=>list.add(new Attribute(n,getAttribute(n))));list}
        def setAttributes(values:AttributeList):AttributeList=new AttributeList()
        def invoke(action:String,args:Array[AnyRef],signature:Array[String]):AnyRef=throw new ReflectionException(new NoSuchMethodException(action))
        def getMBeanInfo:MBeanInfo=new MBeanInfo(getClass.getName,"probe",
          Array(new MBeanAttributeInfo("Connected","boolean","connected",true,false,false)),null,null,null)
      }
      server.registerMBean(bean,name)
      try {
        monitor.streamingConnected shouldBe Some(true)
        connected.set(false);monitor.streamingConnected shouldBe Some(false)
      }finally server.unregisterMBean(name)
      monitor.streamingConnected shouldBe None
    }
  }
}
