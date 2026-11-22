package cn.xuyinyin.magic.cdc.bootstrap

import java.sql.Types

/**
 * Compares the JDBC shape observed from MySQL with the Debezium shape sealed by the Reader.
 * MySQL TEXT family columns are reported as LONGVARCHAR by Connector/J while Debezium keeps
 * the string JDBC code in its schema descriptor. The compatibility is deliberately narrow.
 */
object BootstrapSchemaCompatibility {
  private val textTypeNames = Set("TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT")

  def compatible(expectedJdbcType: Int, expectedTypeName: String, actualJdbcType: Int): Boolean =
    actualJdbcType == expectedJdbcType ||
      textTypeNames.contains(expectedTypeName.toUpperCase(java.util.Locale.ROOT)) &&
        actualJdbcType == Types.LONGVARCHAR

  def columnsCompatible(expected: Seq[(String, Int, String)], actual: Seq[(String, Int)]): Boolean =
    expected.size == actual.size && expected.zip(actual).forall { case ((expectedName, expectedType, expectedTypeName), (actualName, actualType)) =>
      expectedName == actualName && compatible(expectedType, expectedTypeName, actualType)
    }
}
