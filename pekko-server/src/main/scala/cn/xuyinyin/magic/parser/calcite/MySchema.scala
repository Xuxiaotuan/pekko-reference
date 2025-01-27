package cn.xuyinyin.magic.parser.calcite

import org.apache.calcite.schema.Table
import org.apache.calcite.schema.impl.AbstractSchema

import java.util
/**
 * @author : XuJiaWei
 * @since : 2025-01-23 15:07
*/
class MySchema extends AbstractSchema {
  override def getTableMap: util.Map[String, Table] = {
    val tableMap = new util.HashMap[String, Table]()
    tableMap.put("my_table", new MyTable()) // 添加 my_table
    tableMap.put("my_department", new MyDepartment()) // 添加 my_table
    tableMap
  }
}
