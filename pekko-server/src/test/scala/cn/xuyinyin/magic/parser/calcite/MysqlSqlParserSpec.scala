package cn.xuyinyin.magic.parser.calcite

import cn.xuyinyin.magic.common.fileUtils
import cn.xuyinyin.magic.parser.calcite.CalciteMysqlSqlParser.parseSql
import cn.xuyinyin.magic.testkit.STSpec
import org.apache.calcite.config.Lex
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.rel2sql.RelToSqlConverter
import org.apache.calcite.schema.SchemaPlus
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.sql.{SqlDialect, SqlNode}
import org.apache.calcite.tools.{FrameworkConfig, Frameworks, Planner, RelBuilder}

/**
 * @author : XuJiaWei
 * @since : 2025-01-23 14:41
 */
class MysqlSqlParserSpec extends STSpec {

  override def beforeEach(): Unit = {
    println("================================================================")
  }

  override def afterEach(): Unit = {
    println("================================================================")
  }

  "calcite sql Node" should {

    "case 1" in {
      val filename =
        "/Users/xjw/magic/scala-workbench/pekko-reference/pekko-server/src/test/scala/cn/xuyinyin/magic/parser/calcite/test.sql"
      val sql = fileUtils.readFileContents(filename)

      // Step 1: Parse SQL to SqlNode
      val parser: SqlParser = parseSql(sql)
      val sqlNode: SqlNode  = parser.parseStmt()

      // 创建PostgreSQL方言实例
      val dialect = new PostgresqlSqlDialect(SqlDialect.EMPTY_CONTEXT)

      // 转换SQL到PostgreSQL格式// 转换SQL到PostgreSQL格式
      val postgresSql = sqlNode.toSqlString(dialect).getSql

      println(postgresSql)
    }

    "case 2 create relNode" in {
      // 创建根 Schema
      val rootSchema: SchemaPlus = Frameworks.createRootSchema(true)
      // 注册自定义的 Schema
      rootSchema.add("my_schema", new MySchema())

      // 创建 FrameworkConfig
      val config: FrameworkConfig = Frameworks
        .newConfigBuilder()
        .defaultSchema(rootSchema.getSubSchema("my_schema")) // 设置默认 Schema
        .build()

      // 使用 RelBuilder 创建 RelNode
      val relBuilder: RelBuilder = RelBuilder.create(config)
      val relNode: RelNode = relBuilder
        .scan("my_table")                                                         // 扫描 my_table
        .filter(relBuilder.equals(relBuilder.field("id"), relBuilder.literal(1))) // 添加过滤条件
        .build()

      // 打印 RelNode
      println(relNode)
    }

    "case 3 relNode to sql" in {
      // 创建根 Schema
      val rootSchema: SchemaPlus = Frameworks.createRootSchema(true)
      // 注册自定义的 Schema
      rootSchema.add("my_schema", new MySchema())

      // 创建 FrameworkConfig
      val config: FrameworkConfig = Frameworks
        .newConfigBuilder()
        .defaultSchema(rootSchema.getSubSchema("my_schema")) // 设置默认 Schema
        .build()

      // 使用 RelBuilder 创建 RelNode
      val relBuilder: RelBuilder = RelBuilder.create(config)
      val relNode: RelNode = relBuilder
        .scan("my_table")                                                         // 扫描 my_table
        .filter(relBuilder.equals(relBuilder.field("id"), relBuilder.literal(1))) // 添加过滤条件
        .build()

      // 打印 RelNode 的类型和结构
      println(s"RelNode 类型: ${relNode.getClass}")
      println(s"RelNode 结构:\n${relNode.explain()}")

      // 创建 PostgreSQL 方言实例
      val dialect: SqlDialect = new PostgresqlSqlDialect(SqlDialect.EMPTY_CONTEXT)

      // 创建 RelToSqlConverter 实例
      val relToSqlConverter = new RelToSqlConverter(dialect)

      // 正确调用 visitRoot 处理根节点
      val result  = relToSqlConverter.visitRoot(relNode)
      val sqlNode: SqlNode = result.asStatement()

      // 将 SQL 节点转换为 SQL 字符串
      val sqlString = sqlNode.toSqlString(dialect).getSql

      println(s"生成的 SQL:\n$sqlString")
    }

    // 测试 RelNode → SQL 转换
    "case 5 convert logical plan to correct PostgreSQL SQL" in {
      // 公共配置初始化
      val rootSchema: SchemaPlus = Frameworks.createRootSchema(true)
      rootSchema.add("my_schema", new MySchema()) // 假设 MySchema 已实现

      val config: FrameworkConfig = Frameworks.newConfigBuilder()
        .parserConfig(
          SqlParser.configBuilder()
            .setLex(Lex.MYSQL) // 根据需求设置 SQL 方言
            .build()
        )
        .defaultSchema(rootSchema.getSubSchema("my_schema"))
        .build()

      val relBuilder = RelBuilder.create(config)

      // 构建 RelNode
      val relNode = relBuilder
        .scan("my_table")
        .filter(
          relBuilder.and(
            relBuilder.equals(relBuilder.field("id"), relBuilder.literal(1)),
            relBuilder.greaterThan(relBuilder.field("age"), relBuilder.literal(18))
          )
        )
        .project(relBuilder.field("name"))
        .build()

      // 创建转换器
      val dialect = new PostgresqlSqlDialect(SqlDialect.EMPTY_CONTEXT)
      val converter = new RelToSqlConverter(dialect) // 正确构造方法

      // 执行转换
      val result = converter.visitRoot(relNode).asStatement()
      val sql = result.toSqlString(dialect).getSql

      // 验证结果
      val expected =
        """SELECT "name"
          |FROM "my_schema"."my_table"
          |WHERE "id" = 1 AND "age" > 18""".stripMargin.replaceAll("\n", "")

      sql.replaceAll("\n", "") shouldBe expected
    }

    "case 6 convert SQL to correct RelNode" in {
      // 公共配置初始化
      val rootSchema: SchemaPlus = Frameworks.createRootSchema(true)
      rootSchema.add("my_schema", new MySchema()) // 假设 MySchema 已实现

      val config: FrameworkConfig = Frameworks.newConfigBuilder()
        .parserConfig(
          SqlParser.config()
            .withLex(Lex.MYSQL) // 根据需求设置 SQL 方言
        )
        .defaultSchema(rootSchema.getSubSchema("my_schema"))
        .build()

      val planner: Planner = Frameworks.getPlanner(config)

      val sql: String = "SELECT id + 1 AS inc_id FROM my_table WHERE name LIKE 'A%'"

    }
  }

}
