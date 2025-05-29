package cn.xuyinyin.magic.parser.calcite

import cn.xuyinyin.magic.testkit.STSpec
import org.apache.calcite.config.Lex
import org.apache.calcite.plan.RelOptUtil
import org.apache.calcite.plan.hep.{HepPlanner, HepProgramBuilder}
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.{Filter, Join, Project, TableScan}
import org.apache.calcite.rel.rel2sql.RelToSqlConverter
import org.apache.calcite.rel.rules.CoreRules
import org.apache.calcite.rex.{RexCall, RexInputRef, RexLiteral, RexNode}
import org.apache.calcite.schema.SchemaPlus
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.sql.{SqlExplainFormat, SqlExplainLevel, SqlNode}
import org.apache.calcite.tools.{FrameworkConfig, Frameworks, Planner}
import scala.jdk.CollectionConverters._

class CalciteQueryParserSpec extends STSpec {

  "calcite to pekko" should {
    "demo1" in {
      // ------------------ 步骤0：定义并打印原始 SQL ------------------
      val sql =
        """
          |SELECT e.EMPNO, e.ENAME, d.DEPTNAME
          |FROM EMP e JOIN DEPT d ON e.EMPNO = d.DEPTNO
          |WHERE d.DEPTNAME = 'Sales'
          |""".stripMargin
      println(s"Original SQL:\n$sql")

      // ------------------ 步骤1：解析 SQL，生成 SqlNode ------------------
      val parserConfig: SqlParser.Config = SqlParser
        .config()
        .withCaseSensitive(false)
        .withLex(Lex.MYSQL) // 使用 MySQL 语法规则
      val rootNode: SqlNode = SqlParser.create(sql, parserConfig).parseStmt()
      println(s"Parsed SqlNode: $rootNode")

      // ------------------ 步骤2：构造 Schema，创建并初始化 Planner ------------------
      val rootSchema: SchemaPlus = Frameworks.createRootSchema(true)
      rootSchema.add("MYSCHEMA", new BigMySchema)
      val frameworkConfig: FrameworkConfig = Frameworks.newConfigBuilder()
        .defaultSchema(rootSchema.getSubSchema("MYSCHEMA"))
        .build()
      val planner: Planner = Frameworks.getPlanner(frameworkConfig)

      // 解析、校验、并将校验后的 SQL 转为 RelNode
      val parsed    = planner.parse(sql)
      val validated = planner.validate(parsed)
      val relNode: RelNode = planner.rel(validated).project
      println("\nInitial Logical Plan:")
      println(RelOptUtil.dumpPlan("", relNode, SqlExplainFormat.TEXT, SqlExplainLevel.ALL_ATTRIBUTES))

      // ------------------ 步骤3：应用规则优化（HepPlanner） ------------------
      val hepPlanner = new HepPlanner(
        new HepProgramBuilder()
          .addRuleInstance(CoreRules.FILTER_INTO_JOIN) // 将过滤条件下推到 Join
          .build()
      )
      hepPlanner.setRoot(relNode)
      val optimized: RelNode = hepPlanner.findBestExp
      println("\nOptimized Logical Plan:")
      println(RelOptUtil.dumpPlan("", optimized, SqlExplainFormat.TEXT, SqlExplainLevel.ALL_ATTRIBUTES))

      // ------------------ 步骤4：将优化后的 RelNode 转换回 SQL ------------------
      val dialect = PostgresqlSqlDialect.DEFAULT
      val relToSql = new RelToSqlConverter(dialect)
      val pgSql = relToSql.visitRoot(optimized).asStatement().toSqlString(dialect).getSql
      println(s"\nPostgreSQL SQL:\n$pgSql")

      // ------------------ 步骤5：提取每张表及其过滤条件 ------------------
      val tableFilters = extractTableFilters(optimized)
      println("\nExtracted Table Filters:")
      tableFilters.foreach { case (tbl, cond) => println(s"Table: $tbl, Filter: $cond") }
    }
  }

  /**
   * 提取表和过滤条件的主逻辑：
   * - Project: 跳过列投影
   * - Filter: 获取前端表列表，再格式化当前 Filter 条件
   * - Join: 分别处理左右输入
   * - TableScan: 直接提取表名，无过滤条件
   */
  private def extractTableFilters(node: RelNode): Seq[(String, String)] = node match {
    case p: Project    => extractTableFilters(p.getInput)
    case f: Filter     =>
      val base = extractTableFilters(f.getInput)
      val cond = formatCondition(f.getCondition, f.getInput)
      base.map(tbl => tbl.copy(_2 = cond))
    case j: Join       => extractTableFilters(j.getLeft) ++ extractTableFilters(j.getRight)
    case ts: TableScan =>
      val tbl = ts.getTable.getQualifiedName.asScala.mkString(".")
      Seq(tbl -> "")
    case _             => Seq.empty
  }

  /**
   * 将 RexNode（表达式）格式化为可读的 SQL 过滤条件：
   * - RexCall: 拼接操作符和操作数
   * - RexLiteral: 提取字面量值，字符串加引号
   * - RexInputRef: 根据上下文获取真实列名
   */
  private def formatCondition(expr: RexNode, context: RelNode): String = expr match {
    case call: RexCall =>
      val op    = call.getOperator.getName
      val parts = call.getOperands.asScala.map(formatOperand(_, context))
      parts.mkString(s" $op ")
    case lit: RexLiteral =>
      lit.getValue2 match {
        case s: String => s"'$s'"
        case other     => other.toString
      }
    case ref: RexInputRef =>
      val name = context.getRowType.getFieldList.get(ref.getIndex).getName
      s"\"$name\""
    case _ => expr.toString
  }

  /**
   * 格式化单个操作数：常量或列引用
   */
  private def formatOperand(node: RexNode, context: RelNode): String = node match {
    case lit: RexLiteral    => formatCondition(lit, context)
    case ref: RexInputRef   => formatCondition(ref, context)
    case other              => other.toString
  }

}
