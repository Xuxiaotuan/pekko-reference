package cn.xuyinyin.magic.workflow.integration

import java.io.{ByteArrayInputStream, EOFException, InputStream}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** These guard the test injector: a wrong packet boundary or false COMMIT match invalidates a cut. */
class MySQLCommitResponseProxySpec extends AnyWordSpec with Matchers {
  "the test-only MySQL packet boundary" should {
    "read an entire frame when TCP fragments every read" in {
      val bytes = Array[Byte](3, 0, 0, 1, 0, 42, 43)
      val fragmented = new ByteArrayInputStream(bytes) {
        override def read(b: Array[Byte], off: Int, len: Int): Int = super.read(b, off, math.min(len, 1))
      }
      MySQLCommitResponseProxy.readPacket(fragmented).toVector shouldBe bytes.toVector
    }
    "reject a truncated packet rather than forward a false successful response" in {
      intercept[EOFException](MySQLCommitResponseProxy.readPacket(new ByteArrayInputStream(Array[Byte](3,0,0,1,0))))
    }
    "recognize only a sequence-zero COM_QUERY whose full SQL is COMMIT" in {
      def packet(seq: Int, command: Int, sql: String) = {
        val body = Array(command.toByte) ++ sql.getBytes("UTF-8")
        Array(body.length.toByte,0.toByte,0.toByte,seq.toByte) ++ body
      }
      MySQLCommitResponseProxy.isCommit(packet(0,3,"commit")) shouldBe true
      MySQLCommitResponseProxy.isCommit(packet(1,3,"commit")) shouldBe false
      MySQLCommitResponseProxy.isCommit(packet(0,3,"SELECT 'commit'")) shouldBe false
      MySQLCommitResponseProxy.isCommit(packet(0,22,"commit")) shouldBe false
    }
    "never classify an ERR packet or resultset header as a successful COMMIT" in {
      MySQLCommitResponseProxy.isOk(Array[Byte](1,0,0,1,0)) shouldBe true
      MySQLCommitResponseProxy.isOk(Array[Byte](1,0,0,1,-1)) shouldBe false
      MySQLCommitResponseProxy.isOk(Array[Byte](1,0,0,1,1)) shouldBe false
    }
  }
}
