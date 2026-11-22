package cn.xuyinyin.magic.cdc.bootstrap

import java.sql.Types
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class BootstrapSchemaCompatibilitySpec extends AnyWordSpec with Matchers {
  "bootstrap schema compatibility" should {
    "accept MySQL TEXT when Debezium sealed it as a string JDBC type" in {
      BootstrapSchemaCompatibility.compatible(Types.VARCHAR, "TEXT", Types.LONGVARCHAR) shouldBe true
    }

    "keep binary and text JDBC families distinct" in {
      BootstrapSchemaCompatibility.compatible(Types.VARCHAR, "TEXT", Types.LONGVARBINARY) shouldBe false
    }
  }
}
