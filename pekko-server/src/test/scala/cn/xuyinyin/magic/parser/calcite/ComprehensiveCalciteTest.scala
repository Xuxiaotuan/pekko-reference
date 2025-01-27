package cn.xuyinyin.magic.parser.calcite

import cn.xuyinyin.magic.testkit.STSpec
import org.apache.calcite.plan.RelOptUtil
import org.apache.calcite.plan.hep.{HepPlanner, HepProgramBuilder}
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.JoinRelType
import org.apache.calcite.rel.rel2sql.RelToSqlConverter
import org.apache.calcite.rel.rules.CoreRules
import org.apache.calcite.schema.SchemaPlus
import org.apache.calcite.schema.impl.ScalarFunctionImpl
import org.apache.calcite.sql._
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect
import org.apache.calcite.sql.fun.SqlStdOperatorTable
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.tools._

/**
 * @author : XuJiaWei
 * @since : 2025-01-27 21:18
 */
class ComprehensiveCalciteTest extends STSpec {
  // ███████╗ 初始化配置 ███████╗
  private val rootSchema: SchemaPlus = Frameworks.createRootSchema(true)

  // 注册自定义 Schema（模拟内存表）
  rootSchema.add("my_schema", new MySchema)

  private val config: FrameworkConfig = Frameworks
    .newConfigBuilder()
    .defaultSchema(rootSchema.getSubSchema("my_schema"))
    .parserConfig(SqlParser.config().withCaseSensitive(false))
    .build()

  // ███████╗ 工具方法 ███████╗
  private def toSql(relNode: RelNode, dialect: SqlDialect = SqlDialect.DatabaseProduct.POSTGRESQL.getDialect): String = {
    new RelToSqlConverter(dialect)
      .visitRoot(relNode)
      .asStatement()
      .toSqlString(dialect)
      .getSql
  }

  private def explain(relNode: RelNode): String = {
    RelOptUtil.toString(relNode)
  }

  // ███████╗ 测试用例 ███████╗

  // 测试 1: 基础扫描与投影
  "Simple scan and project" should {
    "generate correct SQL" in {
      val relBuilder = RelBuilder.create(config)
      val relNode = relBuilder
        .scan("my_table")
        .project(relBuilder.field("id"), relBuilder.field("name"))
        .build()

      val sql = toSql(relNode)
      println("sql : \n" + sql)
      sql shouldBe "SELECT \"id\", \"name\"\nFROM \"my_schema\".\"my_table\""
    }

    // 测试 2: 复杂过滤条件

    "be optimized" in {
      val relBuilder = RelBuilder.create(config)
      // 构建 RelNode（包含冗余的过滤条件和投影操作）
      val relNode = relBuilder
        .scan("my_table") // 扫描表 my_table
        .filter(
          relBuilder.and(
            relBuilder.greaterThan(relBuilder.field("age"), relBuilder.literal(18)), // age > 18
            relBuilder.call(
              SqlStdOperatorTable.LIKE, // 使用 LIKE 操作符
              relBuilder.field("name"), // name 字段
              relBuilder.literal("A%")  // 模式 "A%"
            ),
            relBuilder.equals(relBuilder.field("age"), relBuilder.field("age")) // 冗余条件：age = age
          )
        )
        .project(
          relBuilder.field("id"),
          relBuilder.field("name"),
          relBuilder.field("age"),
          relBuilder.field("salary")
        )
        .filter(
          relBuilder.greaterThan(relBuilder.field("salary"), relBuilder.literal(50000)) // salary > 50000
        )
        .project(
          relBuilder.field("id"),
          relBuilder.field("name"),
          relBuilder.field("age")
        )
        .build()

      // 打印原始计划
      println("原始计划:\n" + RelOptUtil.toString(relNode))

      // 生成 SQL
      val dialect: PostgresqlSqlDialect = new PostgresqlSqlDialect(SqlDialect.EMPTY_CONTEXT)

      // 创建 RelToSqlConverter 实例
      val relToSqlConverter = new RelToSqlConverter(dialect)

      // 正确调用 visitRoot 处理根节点
      val result           = relToSqlConverter.visitRoot(relNode)
      val sqlNode: SqlNode = result.asStatement()

      // 将 SQL 节点转换为 SQL 字符串
      val sqlString = sqlNode.toSqlString(dialect).getSql
      println("生成优化前的 SQL: \n" + sqlString)

      // 创建优化器并添加规则
      val hepProgramBuilder = new HepProgramBuilder()
      hepProgramBuilder.addRuleInstance(CoreRules.FILTER_REDUCE_EXPRESSIONS) // 简化过滤条件
      hepProgramBuilder.addRuleInstance(CoreRules.FILTER_MERGE)              // 合并过滤条件
      hepProgramBuilder.addRuleInstance(CoreRules.PROJECT_FILTER_TRANSPOSE)  // 将过滤条件下推到投影之前
      hepProgramBuilder.addRuleInstance(CoreRules.PROJECT_MERGE)             // 合并冗余的投影操作
      hepProgramBuilder.addRuleInstance(CoreRules.PROJECT_REMOVE)            // 移除冗余的投影操作
      val planner: HepPlanner = new HepPlanner(hepProgramBuilder.build())
      planner.setRoot(relNode)
      val optimized: RelNode = planner.findBestExp()
      // 打印优化后计划
      println("\n优化后计划:\n" + RelOptUtil.toString(optimized))
      val sql = toSql(optimized, dialect)
      println("生成优化后的 SQL:\n" + sql)
    }

    // 测试 3: 聚合与分组
    "Aggregation with grouping  handle rollup" in {
      val relBuilder = RelBuilder.create(config)
      val relNode = relBuilder
        .scan("my_table")
        .aggregate(
          relBuilder.groupKey("age"),
          relBuilder.sum(false, "total", relBuilder.field("id"))
        )
        .build()

      val sql = toSql(relNode)
      println("sql: " + sql)
      sql shouldBe "SELECT \"age\", SUM(\"id\") AS \"total\"\nFROM \"my_schema\".\"my_table\"\nGROUP BY \"age\""
    }

    "Table join operations generate proper join conditions" in {
      val relBuilder: RelBuilder = RelBuilder.create(config)
      // 扫描员工表和部门表，并为它们设置别名
      val employee   = relBuilder.scan("my_table").as("e")
      val department = relBuilder.scan("my_department").as("d")

      // 执行内连接操作，连接条件是员工表的 `dept_id` 字段与部门表的 `id` 字段相等
      val join = relBuilder
        .join(
          JoinRelType.INNER,
          relBuilder.equals(
            employee.field("id"),       // 员工表的部门 ID 字段
            department.field("dept_id") // 部门表的主键字段
          )
        )
        .project(                       // 选择需要输出的字段
          employee.field("name"),       // 员工姓名
          department.field("dept_name") // 部门名称
        )
        .build()

      // 将生成的逻辑计划转换为 SQL 语句
      val sql = toSql(join) // 假设 toSql 是一个将逻辑计划转换为 SQL 的方法
      println("Generated SQL: " + sql)

      // 验证生成的 SQL 是否正确
      sql should include("INNER JOIN \"employee\" AS \"e\"")           // 验证是否包含正确的 JOIN 语句
      sql should include("ON \"e\".\"dept_id\" = \"d\".\"id\"")        // 验证连接条件是否正确
      sql should include("SELECT \"e\".\"name\", \"d\".\"dept_name\"") // 验证 SELECT 字段是否正确
    }

    // 测试 5: 自定义函数处理
    "Custom scalar function be integrated into plan" in {
      // 注册自定义函数
      rootSchema.add(
        "MY_UPPER",
        ScalarFunctionImpl.create(
          classOf[XxtStringUtils],
          "upper"
        ))

      val sql     = "SELECT MY_UPPER(name) FROM test_table"
      val planner = Frameworks.getPlanner(config)

      // 解析 SQL
      val parsed: SqlNode = planner.parse(sql)

    }
  }

}

// 辅助类定义
object StringUtils {
  def upper(s: String): String = s.toUpperCase
}
