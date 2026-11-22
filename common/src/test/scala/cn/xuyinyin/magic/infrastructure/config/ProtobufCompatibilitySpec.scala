package cn.xuyinyin.magic.infrastructure.config

import com.google.protobuf.ByteString
import com.mysql.cj.x.protobuf.MysqlxSession
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Both generated-code generations must work on the one resolved runtime. */
class ProtobufCompatibilitySpec extends AnyWordSpec with Matchers {
  "the resolved Protobuf runtime" should {
    "round-trip the older Avatica generated messages" in {
      val generated = try Class.forName("org.apache.calcite.avatica.proto.Common$TypedValue")
      catch { case error: LinkageError => fail("Avatica generated code cannot link to the resolved Protobuf runtime", error) }
      val builder = generated.getMethod("newBuilder").invoke(null)
      builder.getClass.getMethod("setStringValue", classOf[String]).invoke(builder, "中文-🙂")
      val message = builder.getClass.getMethod("build").invoke(builder).asInstanceOf[com.google.protobuf.Message]
      generated.getMethod("parseFrom", classOf[Array[Byte]]).invoke(null, message.toByteArray) shouldBe message
    }
    "round-trip the MySQL Connector generated messages" in {
      val message = MysqlxSession.AuthenticateStart.newBuilder().setMechName("MYSQL41")
        .setAuthData(ByteString.copyFromUtf8("fixture-only")).build()
      MysqlxSession.AuthenticateStart.parseFrom(message.toByteArray) shouldBe message
    }
  }
}
