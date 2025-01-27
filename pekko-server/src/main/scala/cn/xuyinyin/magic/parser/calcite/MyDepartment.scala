package cn.xuyinyin.magic.parser.calcite

import org.apache.calcite.rel.`type`.{RelDataType, RelDataTypeFactory}
import org.apache.calcite.schema.impl.AbstractTable
import org.apache.calcite.sql.`type`.SqlTypeName

/**
 * @author : XuJiaWei
 * @since : 2025-01-23 15:05
 */
class MyDepartment extends AbstractTable {
  override def getRowType(typeFactory: RelDataTypeFactory): RelDataType = {
    val builder = typeFactory.builder()
    builder.add("dept_id", typeFactory.createSqlType(SqlTypeName.INTEGER))        // 添加 id 列
    builder.add("dept_name", typeFactory.createSqlType(SqlTypeName.VARCHAR, 255)) // 添加 name 列
    builder.build()
  }
}
