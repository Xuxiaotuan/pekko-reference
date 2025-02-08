package cn.xuyinyin.magic.parser.calcite

import cn.xuyinyin.magic.common.fileUtils
import cn.xuyinyin.magic.parser.calcite.CalciteMysqlSqlParser.parseSql
import cn.xuyinyin.magic.testkit.STSpec
import org.apache.calcite.DataContext
import org.apache.calcite.config.Lex
import org.apache.calcite.linq4j.Enumerable
import org.apache.calcite.plan.RelOptUtil
import org.apache.calcite.plan.hep.{HepPlanner, HepProgram, HepProgramBuilder}
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.`type`.{RelDataType, RelDataTypeFactory}
import org.apache.calcite.rel.rel2sql.RelToSqlConverter
import org.apache.calcite.rel.rules.CoreRules
import org.apache.calcite.schema.impl.{AbstractSchema, AbstractTable}
import org.apache.calcite.schema.{ScannableTable, SchemaPlus, Table}
import org.apache.calcite.sql.`type`.SqlTypeName
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.sql.parser.impl.SqlParserImpl
import org.apache.calcite.sql.{SqlDialect, SqlExplainFormat, SqlExplainLevel, SqlNode}
import org.apache.calcite.tools.{FrameworkConfig, Frameworks, Planner}

// 定义一个简单的 EMP 表（仅用于验证生成计划，实际数据为空）
class EmpTable extends AbstractTable with ScannableTable {

  override def scan(root: DataContext): Enumerable[Array[AnyRef]] = ???

  override def getRowType(typeFactory: RelDataTypeFactory): RelDataType = {
    typeFactory
      .builder()
      .add("EMPNO", typeFactory.createSqlType(SqlTypeName.INTEGER))
      .add("ENAME", typeFactory.createSqlType(SqlTypeName.VARCHAR))
      .build()
  }
}

// 模拟 DEPT 表，包含字段 DEPTNO 和 DEPTNAME
class DeptTable extends AbstractTable with ScannableTable {
  override def scan(root: DataContext): Enumerable[Array[AnyRef]] = ???

  override def getRowType(typeFactory: RelDataTypeFactory): RelDataType = {
    typeFactory
      .builder()
      .add("DEPTNO", typeFactory.createSqlType(SqlTypeName.INTEGER))
      .add("DEPTNAME", typeFactory.createSqlType(SqlTypeName.VARCHAR))
      .build()
  }
}

// 自定义 Schema，只包含一个 EMP 表
class BigMySchema extends AbstractSchema {
  override def getTableMap: java.util.Map[String, Table] = {
    val map = new java.util.HashMap[String, Table]()
    map.put("EMP", new EmpTable())
    map.put("DEPT", new DeptTable())
    map
  }
}

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
      val sqlNode: SqlNode  = parser.parseQuery();

      // Generate equivalent SQL query in the target dialect
      val targetSqlQuery: String = sqlNode.toSqlString(targetDialect).getSql;

      // Output the converted SQL query
      System.out.println("Converted SQL query in target dialect:");
      System.out.println(targetSqlQuery);
    }

    "mysdl to pg optimized" in {
      // 示例 SQL（MySQL 语法）
      // 示例 SQL：连接 EMP 和 DEPT，过滤 DEPT.DEPTNAME = 'Sales'
      val originalSql =
        """
          |SELECT e.EMPNO, e.ENAME, d.DEPTNAME
          |FROM EMP e JOIN DEPT d ON e.EMPNO = d.DEPTNO
          |WHERE d.DEPTNAME = 'Sales'
          |""".stripMargin
      println(s"Original SQL:\n$originalSql")

      // -----------------------------------------------------
      // 【步骤1】解析 SQL，生成 SqlNode
      // -----------------------------------------------------
      // 配置解析器：设置 MySQL 的语法规范
      val parserConfig: SqlParser.Config = SqlParser
        .config()
        .withCaseSensitive(false)
        .withLex(Lex.MYSQL)
      // 创建解析器并解析 SQL 字符串
      val sqlParser        = SqlParser.create(originalSql, parserConfig)
      val sqlNode: SqlNode = sqlParser.parseStmt()
      println("解析得到的 SqlNode:")
      println(sqlNode.toString)

      // -----------------------------------------------------
      // 【步骤2】构造 Schema，并利用 Planner 解析、校验、转换为 RelNode
      // -----------------------------------------------------
      // 创建根 Schema 并将自定义 Schema 添加进去
      val rootSchema: SchemaPlus = Frameworks.createRootSchema(true)
      rootSchema.add("MYSCHEMA", new BigMySchema())
      // 设置默认 Schema 为 MYSCHEMA（注意：表名区分大小写，请保证 SQL 中的 EMP 与这里一致）
      val config: FrameworkConfig = Frameworks
        .newConfigBuilder()
        .defaultSchema(rootSchema.getSubSchema("MYSCHEMA"))
        .build()
      // 获取 Planner（Planner 内部会构造解析器、校验器和转换器）
      val planner: Planner = Frameworks.getPlanner(config)

      // 使用 Planner 完成解析、校验和转换
      val parsedNode       = planner.parse(originalSql)
      val validatedNode    = planner.validate(parsedNode)
      val relNode: RelNode = planner.rel(validatedNode).project

      println("初始 Logical Plan:")
      println(RelOptUtil.dumpPlan("", relNode, SqlExplainFormat.TEXT, SqlExplainLevel.ALL_ATTRIBUTES))

      // -----------------------------------------------------
      // 【步骤3】对 RelNode 进行优化（使用 HepPlanner 示例规则）
      // -----------------------------------------------------
      val hepProgram: HepProgram = new HepProgramBuilder()
        .addRuleInstance(CoreRules.FILTER_INTO_JOIN) // 示例：将 Filter 推入 Join 的规则
        .build()
      val hepPlanner = new HepPlanner(hepProgram)
      hepPlanner.setRoot(relNode)
      val optimizedRelNode: RelNode = hepPlanner.findBestExp()

      println("优化后的 Logical Plan:")
      println(
        RelOptUtil
          .dumpPlan("", optimizedRelNode, SqlExplainFormat.TEXT, SqlExplainLevel.ALL_ATTRIBUTES))

      // -----------------------------------------------------
      // 【步骤4】将优化后的 RelNode 转换为 PostgreSQL 方言的 SQL
      // -----------------------------------------------------
      val dialect           = PostgresqlSqlDialect.DEFAULT
      val relToSqlConverter = new RelToSqlConverter(dialect)
      val sqlResult         = relToSqlConverter.visitRoot(optimizedRelNode)
      val optimizedPgSql    = sqlResult.asStatement().toSqlString(dialect).getSql

      println("转换后的 PostgreSQL SQL:")
      println(optimizedPgSql)
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
