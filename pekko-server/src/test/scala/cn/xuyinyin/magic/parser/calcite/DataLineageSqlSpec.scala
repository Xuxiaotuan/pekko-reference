package cn.xuyinyin.magic.parser.calcite

import org.apache.calcite.config.Lex
import org.apache.calcite.sql._
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.sql.util.SqlBasicVisitor
import cn.xuyinyin.magic.testkit.STSpec

import scala.collection.mutable
import scala.jdk.CollectionConverters._

class DataLineageSqlSpec extends STSpec {

  // 定义表和字段的依赖关系收集器
  class FieldDependencyVisitor(targetField: String) extends SqlBasicVisitor[Unit] {
    val tableDependencies: mutable.Set[String] = mutable.Set[String]()
    val fieldDependencies: mutable.Set[String] = mutable.Set[String]()
    var foundTarget = false

    override def visit(node: SqlNode): Unit = {
      node match {
        case select: SqlSelect =>
          // 处理 SELECT 列表
          processSelectList(select)
          // 处理 FROM 子句
          Option(select.getFrom).foreach(visit)
          // 处理 WHERE 子句
          Option(select.getWhere).foreach(visit)
          // 处理 GROUP BY 子句
          Option(select.getGroup).foreach(_.getList.forEach(visit))

        case join: SqlJoin =>
          // 处理 JOIN
          visit(join.getLeft)
          visit(join.getRight)
          Option(join.getCondition).foreach(visit)

        case id: SqlIdentifier =>
          // 收集字段依赖
          if (id.names.size() > 1) {
            val tableName = id.names.get(0)
            val fieldName = id.names.get(1)
            if (s"$fieldName" == targetField || foundTarget) {
              tableDependencies.add(tableName)
              fieldDependencies.add(s"$tableName.$fieldName")
            }
          }

        case call: SqlBasicCall =>
          // 处理函数调用
          if (call.toString.contains(targetField)) {
            foundTarget = true
          }
          call.getOperandList.forEach(visit)
          foundTarget = false

        case _ =>
          // 递归访问其他节点
          node.getKind.childTypes.forEach { child =>
            Option(node.getOperand(child)).foreach(visit)
          }
      }
    }

    private def processSelectList(select: SqlSelect): Unit = {
      select.getSelectList.asScala.foreach {
        case alias: SqlBasicCall if alias.getKind == SqlKind.AS =>
          val aliasName = alias.getOperandList.get(1).toString
          if (aliasName == targetField) {
            foundTarget = true
            visit(alias.getOperandList.get(0))
            foundTarget = false
          }
        case selectItem =>
          if (selectItem.toString.contains(targetField)) {
            foundTarget = true
            visit(selectItem)
            foundTarget = false
          }
      }
    }
  }

  // SQL 裁剪器
  class SQLPruner(sqlNode: SqlNode, dependencies: mutable.Set[String]) {
    def prune(): SqlNode = {
      sqlNode match {
        case select: SqlSelect =>
          val newSelectList = pruneSelectList(select)
          val newFrom = pruneFromClause(select.getFrom)
          select
            .clone(select.getParserPosition)
            .asInstanceOf[SqlSelect]
            .withSelectList(newSelectList)
            .withFrom(newFrom)
        case _ => sqlNode
      }
    }

    private def pruneSelectList(select: SqlSelect): SqlNodeList = {
      val newList = select.getSelectList.asScala
        .filter(item => dependencies.exists(dep => item.toString.contains(dep)))
      new SqlNodeList(newList.asJava, select.getSelectList.getParserPosition)
    }

    private def pruneFromClause(from: SqlNode): SqlNode = {
      from match {
        case join: SqlJoin =>
          if (isJoinRequired(join)) {
            val newLeft = pruneFromClause(join.getLeft)
            val newRight = pruneFromClause(join.getRight)
            join.clone(join.getParserPosition)
              .asInstanceOf[SqlJoin]
              .withLeft(newLeft)
              .withRight(newRight)
          } else {
            // 如果 JOIN 不需要，返回必要的一侧
            if (isNodeRequired(join.getLeft)) pruneFromClause(join.getLeft)
            else pruneFromClause(join.getRight)
          }
        case id: SqlIdentifier =>
          if (isNodeRequired(id)) id else null
        case _ => from
      }
    }

    private def isJoinRequired(join: SqlJoin): Boolean = {
      isNodeRequired(join.getLeft) && isNodeRequired(join.getRight)
    }

    private def isNodeRequired(node: SqlNode): Boolean = {
      dependencies.exists(dep => node.toString.contains(dep.split("\\.")(0)))
    }
  }

  "Data Lineage Sql" should {
    "demo1" in {
      val sql =
        """
          |SELECT
          | t3.level,
          | t4.level_name,
          | sum( t2.salary ) AS salary
          |FROM
          | t1
          | INNER JOIN t2 ON t1.id = t2.id
          | INNER JOIN t3 ON t2.id = t3.id
          | INNER JOIN t4 ON t4.level = t3.level
          |WHERE
          | t1.age > 50
          |GROUP BY
          | t3.level
          |""".stripMargin

      val parserConfig: SqlParser.Config = SqlParser
        .config()
        .withCaseSensitive(false)
        .withLex(Lex.MYSQL)

      val parser = SqlParser.create(sql, parserConfig)
      val sqlNode = parser.parseQuery

      // 分析 salary 字段的依赖
      val visitor = new FieldDependencyVisitor("salary")
      visitor.visit(sqlNode)

      println("Table Dependencies: " + visitor.tableDependencies.mkString(", "))
      println("Field Dependencies: " + visitor.fieldDependencies.mkString(", "))

      // 裁剪 SQL
      val pruner = new SQLPruner(sqlNode, visitor.fieldDependencies)
      val prunedSql = pruner.prune()

      println("Pruned SQL: " + prunedSql.toString)

      // 验证结果
      visitor.tableDependencies should contain allOf("t1", "t2", "t3")
      visitor.tableDependencies should not contain "t4"
    }
  }
}