package cn.xuyinyin.magic.parser.calcite

import cn.xuyinyin.magic.common.fileUtils
import cn.xuyinyin.magic.parser.calcite.CalciteMysqlSqlParser.{parserConfig, parseSql}
import cn.xuyinyin.magic.testkit.STSpec
import org.apache.calcite.plan.RelOptUtil
import org.apache.calcite.plan.hep.{HepPlanner, HepProgram, HepProgramBuilder}
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.rel2sql.RelToSqlConverter
import org.apache.calcite.rel.rules.CoreRules
import org.apache.calcite.sql.{SqlDialect, SqlNode}
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.sql.parser.impl.SqlParserImpl
import org.apache.calcite.tools.{FrameworkConfig, Frameworks, Planner}

class BigSqlSpec extends STSpec {

  val filename =
    "/Users/xujiawei/Downloads/large.sql"
  val sql = fileUtils.readFileContents(filename)

  "Big Sql" should {
    "mysql to pg" in {
      // Example SQL query in source dialect (MySQL)
      val sourceSqlQuery: String = "SELECT * FROM my_table WHERE column1 = 'value' limit 10"

      // Configure source and target dialects
      val sourceDialect: SqlDialect = SqlDialect.DatabaseProduct.MYSQL.getDialect
      val targetDialect: SqlDialect = SqlDialect.DatabaseProduct.POSTGRESQL.getDialect

      // Configure SQL parser with the source dialect
      val parserConfig: SqlParser.Config = SqlParser
        .configBuilder()
        .setParserFactory(SqlParserImpl.FACTORY)
        .setUnquotedCasing(sourceDialect.getUnquotedCasing)
        .setQuotedCasing(sourceDialect.getQuotedCasing)
        .setIdentifierMaxLength(SqlParser.DEFAULT_IDENTIFIER_MAX_LENGTH)
        .build();

      // Parse the SQL query using the configured parser
      val parser: SqlParser = SqlParser.create(sourceSqlQuery, parserConfig);
      val sqlNode: SqlNode = parser.parseQuery();

      // Generate equivalent SQL query in the target dialect
      val targetSqlQuery: String = sqlNode.toSqlString(targetDialect).getSql;

      // Output the converted SQL query
      System.out.println("Converted SQL query in target dialect:");
      System.out.println(targetSqlQuery);
    }

    "large mysql to pg" in {
      println(s"原始 SQL 长度：${sql.length}")

      // 1. 配置解析器，注意设置 MySQL 的相关配置
      val parser: SqlParser = parseSql(sql)

      // 解析得到 SqlNode（AST）
      val sqlNode: SqlNode = parser.parseStmt()

      // 2. 校验 SQL（此步骤需要提前构造 schema、operatorTable、typeFactory 等，下面仅示意）
      // Frameworks.configBuilder 可帮助构造 Planner 的配置，这里假设你已经定义好 schema
      val config: FrameworkConfig = Frameworks
        .newConfigBuilder()
        // .defaultSchema(yourSchema)
        .build()
      val planner: Planner = Frameworks.getPlanner(config)

      // 重新解析、校验并转换：注意，planner.parse 和 planner.validate 内部会调用 SqlParser 和 SqlValidator
      val parsedNode: SqlNode    = planner.parse(sql)
      val validatedNode: SqlNode = planner.validate(parsedNode)

      // 3. 将校验后的 SqlNode 转换为关系表达式（RelNode）
      val rel: RelNode = planner.rel(validatedNode).project

      // 4. 对 RelNode 进行优化，下面以 HepPlanner 为例
      val hepProgram: HepProgram = new HepProgramBuilder()
        .addRuleInstance(CoreRules.FILTER_INTO_JOIN) // 示例：添加推送过滤条件规则
        // 根据实际需要添加更多规则……
        .build()
      val hepPlanner = new HepPlanner(hepProgram)
      hepPlanner.setRoot(rel)
      val optimizedRel: RelNode = hepPlanner.findBestExp()

      // 调试输出优化前后计划
      println("初始 Logical Plan:")
      println(RelOptUtil.dumpPlan("", rel, org.apache.calcite.sql.SqlExplainFormat.TEXT, org.apache.calcite.sql.SqlExplainLevel.ALL_ATTRIBUTES))
      println("优化后的 Enumerable Plan:")
      println(
        RelOptUtil.dumpPlan("", optimizedRel, org.apache.calcite.sql.SqlExplainFormat.TEXT, org.apache.calcite.sql.SqlExplainLevel.ALL_ATTRIBUTES))

      // 5. 将优化后的 RelNode 转回 SQL，此处指定使用 PostgreSQL 方言
      val dialect           = PostgresqlSqlDialect.DEFAULT
      val relToSqlConverter = new RelToSqlConverter(dialect)
      val sqlResult         = relToSqlConverter.visitRoot(optimizedRel)
      val postgresSql       = sqlResult.asStatement().toSqlString(dialect).getSql

      println(s"转换后的 PostgreSQL SQL 长度：${postgresSql.length}")
      println(postgresSql)
    }
  }
}
