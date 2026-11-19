package cn.xuyinyin.magic.connectors.mysql.sink

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.sql.{Connection, PreparedStatement, SQLException}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.collection.mutable.ArrayBuffer

class JdbcStatementScopeSpec extends AnyWordSpec with Matchers {
  "a transaction statement scope" should {
    "close every statement even when more than one close fails" in {
      val attempts = ArrayBuffer.empty[String]
      def statement(sql: String): PreparedStatement = Proxy.newProxyInstance(getClass.getClassLoader,
        Array(classOf[PreparedStatement]), new InvocationHandler {
          override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef = method.getName match {
            case "close" => attempts += sql; throw new SQLException(sql)
            case _ => throw new UnsupportedOperationException(method.getName)
          }
        }).asInstanceOf[PreparedStatement]
      val connection = Proxy.newProxyInstance(getClass.getClassLoader, Array(classOf[Connection]), new InvocationHandler {
        override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef = method.getName match {
          case "prepareStatement" => statement(args(0).toString)
          case _ => throw new UnsupportedOperationException(method.getName)
        }
      }).asInstanceOf[Connection]
      val scope = new JdbcStatementScope(connection)
      val first = scope.prepare("first")
      scope.prepare("first") should be theSameInstanceAs first
      scope.prepare("second")
      val failure = intercept[SQLException](scope.close())
      attempts.toVector shouldBe Vector("second", "first")
      failure.getMessage shouldBe "second"
      failure.getSuppressed.map(_.getMessage).toVector shouldBe Vector("first")
      scope.close()
      attempts.size shouldBe 2
    }
  }
}
