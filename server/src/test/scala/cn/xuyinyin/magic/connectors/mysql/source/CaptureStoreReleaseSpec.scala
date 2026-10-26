package cn.xuyinyin.magic.connectors.mysql.source

import cn.xuyinyin.magic.connectors.mysql.source.capture.MySQLCaptureStore
import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.engine.{CdcExecutionException, ExecutionFailure}
import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.sql.{Connection, DriverManager, SQLException}
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class CaptureStoreReleaseSpec extends STSpec {
  private def fixture(withTable: Boolean): (MySQLCaptureStore, AtomicInteger) = {
    val url = s"jdbc:h2:mem:release_${UUID.randomUUID().toString.replace('-', '_')};DB_CLOSE_DELAY=-1"
    val setup = DriverManager.getConnection(url)
    try {
      if (withTable) {
        val q = setup.createStatement()
        try q.execute("CREATE TABLE pekko_capture_schemas(capture_id VARCHAR, table_name VARCHAR, schema_id VARCHAR, definition CLOB)")
        finally q.close()
      }
    } finally setup.close()
    val opens = new AtomicInteger()
    val store = new MySQLCaptureStore(() => {
      opens.incrementAndGet()
      val actual = DriverManager.getConnection(url)
      Proxy.newProxyInstance(getClass.getClassLoader, Array(classOf[Connection]), new InvocationHandler {
        override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef = {
          if (method.getName == "close") {
            actual.close()
            throw new SQLException("injected close confirmation lost")
          }
          try method.invoke(actual, Option(args).getOrElse(Array.empty[AnyRef]): _*)
          catch { case e: InvocationTargetException => throw e.getCause }
        }
      }).asInstanceOf[Connection]
    }, "release", "lineage", 1024)
    store -> opens
  }

  "capture store close" should {
    "retain the resource reservation after a successful query with unconfirmed close" in {
      val (store, opens) = fixture(withTable = true)
      val error = intercept[Throwable](store.schema("a"))
      ExecutionFailure.from(error, "subscribe").releaseConfirmed shouldBe false
      ExecutionFailure.from(error, "subscribe").code shouldBe ExecutionFailure.ReleaseUnknown
      store.releaseConfirmed shouldBe false
      opens.get() shouldBe 1
      val blocked = intercept[CdcExecutionException](store.schema("a"))
      blocked.failure.releaseConfirmed shouldBe false
      opens.get() shouldBe 1
    }
    "preserve the primary query failure while prioritizing the unknown release" in {
      val (store, _) = fixture(withTable = false)
      val error = intercept[CdcExecutionException](store.schema("a"))
      error.failure.releaseConfirmed shouldBe false
      error.failure.stage shouldBe "capture-store-close"
      error.getCause.getMessage shouldBe "injected close confirmation lost"
      error.getSuppressed.length shouldBe 1
      error.getSuppressed.head shouldBe a[SQLException]
      store.releaseConfirmed shouldBe false
    }
  }
}
