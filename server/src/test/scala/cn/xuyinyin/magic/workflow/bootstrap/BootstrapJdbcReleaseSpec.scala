package cn.xuyinyin.magic.workflow.bootstrap

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.engine.{CdcExecutionException,ExecutionFailure}
import java.lang.reflect.{InvocationHandler,InvocationTargetException,Method,Proxy}
import java.sql.{Connection,DriverManager,SQLException}
import java.util.concurrent.atomic.AtomicInteger

class BootstrapJdbcReleaseSpec extends STSpec {
  private def fixture():(BootstrapJdbc,AtomicInteger)={
    val opens=new AtomicInteger()
    val jdbc=new BootstrapJdbc(()=>{
      opens.incrementAndGet()
      val actual=DriverManager.getConnection("jdbc:h2:mem:bootstrap_close_"+java.util.UUID.randomUUID().toString.replace('-','_'))
      Proxy.newProxyInstance(getClass.getClassLoader,Array(classOf[Connection]),new InvocationHandler {
        def invoke(proxy:Any,method:Method,args:Array[AnyRef]):AnyRef={
          if(method.getName=="close"){actual.close();throw new SQLException("close confirmation lost")}
          try method.invoke(actual,Option(args).getOrElse(Array.empty[AnyRef]):_*)
          catch{case e:InvocationTargetException=>throw e.getCause}
        }
      }).asInstanceOf[Connection]
    });jdbc->opens
  }
  "bootstrap JDBC release" should {
    "retain unknown classification on subsequent use without opening another connection" in {
      val (jdbc,opens)=fixture()
      intercept[CdcExecutionException](jdbc.connection(_=>())).failure.releaseConfirmed shouldBe false
      val blocked=intercept[Throwable](jdbc.connection(_=>()))
      ExecutionFailure.from(blocked,"bootstrap").releaseConfirmed shouldBe false
      opens.get() shouldBe 1
    }
    "keep a primary failure reachable when closing is also unknown" in {
      val (jdbc,_)=fixture()
      val error=intercept[CdcExecutionException](jdbc.connection(_=>throw new IllegalStateException("primary")))
      error.getSuppressed.map(_.getMessage).toVector should contain("primary")
    }
  }
}
