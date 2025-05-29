package cn.xuyinyin.magic.parser.calcite

import cn.xuyinyin.magic.testkit.STSpec
import org.apache.calcite.config.Lex
import org.apache.calcite.plan.RelOptUtil
import org.apache.calcite.plan.hep.{HepPlanner, HepProgram, HepProgramBuilder}
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.{Filter, Join, TableScan}
import org.apache.calcite.rel.rel2sql.RelToSqlConverter
import org.apache.calcite.rel.rules.CoreRules
import org.apache.calcite.rex.{RexCall, RexInputRef, RexLiteral, RexNode}
import org.apache.calcite.schema.SchemaPlus
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.sql.{SqlExplainFormat, SqlExplainLevel, SqlNode}
import org.apache.calcite.tools.{FrameworkConfig, Frameworks, Planner}

import scala.collection.convert.ImplicitConversions.`collection AsScalaIterable`
import scala.jdk.CollectionConverters.CollectionHasAsScala

class CalciteQueryParserSpec extends STSpec {

  "calcite to pekko" should {
    "demo1" in {
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

      // -----------------------------------------------------
      // 【步骤5】提取表名和过滤条件
      // -----------------------------------------------------
      val tableInfo = extractTableNamesAndFilters(optimizedRelNode)

      println("提取的表名和过滤条件：")
      tableInfo.foreach { case (tableName, filter) =>
        println(s"Table: $tableName, Filter: $filter")
      }
    }

    // 递归提取表名和过滤条件
    def extractTableNamesAndFilters(relNode: RelNode): Seq[(String, String)] = {
      relNode match {
        // 跳过 LogicalProject 节点，递归处理输入节点
        case project: org.apache.calcite.rel.core.Project =>
          extractTableNamesAndFilters(project.getInput)
        // Filter 节点，提取过滤条件并递归获取表名
        case filter: Filter =>
          val tableInfo = extractTableNamesAndFilters(filter.getInput) // 递归获取输入节点的表名
          val cond      = getFilterConditionString(filter.getCondition, filter.getInput)
          tableInfo.map { case (tableName, _) => (tableName, cond) }

        // Join 节点，递归提取左、右子树的表名和过滤条件
        case join: Join =>
          val leftTableInfo  = extractTableNamesAndFilters(join.getLeft)
          val rightTableInfo = extractTableNamesAndFilters(join.getRight)
          leftTableInfo ++ rightTableInfo

        // TableScan 节点，提取表名
        case tableScan: TableScan =>
          val tableName = tableScan.getTable.getQualifiedName.mkString(".")
          Seq((tableName, "")) // TableScan 节点没有过滤条件，返回空字符串

        // 对于其他类型的节点，返回空
        case _ =>
          Seq()
      }
    }

    def getFilterConditionString(node: RexNode, inputRel: RelNode): String = node match {
      case call: RexCall =>
        val op    = call.getOperator.getName
        val parts = call.getOperands.asScala.map(r => getOperandString(r, inputRel))
        parts.mkString(s" $op ")
      case lit: RexLiteral =>
        // 直接把字面量值拿出来
        lit.getValue match {
          case s: String => s"'$s'"
          case other     => other.toString
        }
      case ref: RexInputRef =>
        // 单独出现的列引用
        val name = inputRel.getRowType.getFieldList
          .get(ref.getIndex)
          .getName
        s""""$name""""
      case _ =>
        node.toString
    }

    // 新：带上下文的操作数提取
    def getOperandString(
        node: RexNode,
        inputRel: RelNode): String = node match {
      case lit: RexLiteral =>
        lit.getValue2 match {
          case s: String => s"'$s'"
          case other     => other.toString
        }
      case ref: RexInputRef =>
        val name = inputRel.getRowType.getFieldList
          .get(ref.getIndex)
          .getName
        s""""$name""""
      case _ =>
        node.toString
    }

  }
}
