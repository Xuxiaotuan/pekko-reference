package cn.xuyinyin.magic.parser.calcite

import cn.xuyinyin.magic.testkit.STSpec
import org.apache.calcite.DataContext
import org.apache.calcite.config.Lex
import org.apache.calcite.linq4j.Enumerable
import org.apache.calcite.plan.RelOptUtil
import org.apache.calcite.plan.hep.{HepPlanner, HepProgramBuilder}
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.`type`.{RelDataType, RelDataTypeFactory}
import org.apache.calcite.rel.core.{Filter, Join, Project, TableScan}
import org.apache.calcite.rel.rel2sql.RelToSqlConverter
import org.apache.calcite.rel.rules.CoreRules
import org.apache.calcite.rex.{RexCall, RexInputRef, RexLiteral, RexNode}
import org.apache.calcite.schema.impl.{AbstractSchema, AbstractTable}
import org.apache.calcite.schema.{ScannableTable, SchemaPlus, Table}
import org.apache.calcite.sql.`type`.SqlTypeName
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.sql.{SqlExplainFormat, SqlExplainLevel, SqlNode}
import org.apache.calcite.tools.{FrameworkConfig, Frameworks, Planner}

import scala.jdk.CollectionConverters._

class CalciteQueryParserSpec extends STSpec {

  // 模拟 EMP 表，包含 EMPNO, ENAME 和 DEPTNO
  class XxtEmpTable extends AbstractTable with ScannableTable {
    override def scan(root: DataContext): Enumerable[Array[AnyRef]] = ???

    // 定义 EMP 表的列名和类型
    override def getRowType(typeFactory: RelDataTypeFactory): RelDataType = {
      typeFactory.builder()
        .add("EMPNO", typeFactory.createSqlType(SqlTypeName.INTEGER))
        .add("ENAME", typeFactory.createSqlType(SqlTypeName.VARCHAR))
        .add("DEPTNO", typeFactory.createSqlType(SqlTypeName.INTEGER)) // 新增 DEPTNO 字段
        .build()
    }
  }

  // 模拟 DEPT 表，包含字段 DEPTNO 和 DEPTNAME
  class XxtDeptTable extends AbstractTable with ScannableTable {
    override def scan(root: DataContext): Enumerable[Array[AnyRef]] = ???

    override def getRowType(typeFactory: RelDataTypeFactory): RelDataType = {
      typeFactory
        .builder()
        .add("DEPTNO", typeFactory.createSqlType(SqlTypeName.INTEGER))
        .add("DEPTNAME", typeFactory.createSqlType(SqlTypeName.VARCHAR))
        .build()
    }
  }

  // 自定义 Schema，包含一个 EMP 表、包含一个 DEPT 表
  class XxtSchema extends AbstractSchema {
    override def getTableMap: java.util.Map[String, Table] = {
      val map = new java.util.HashMap[String, Table]()
      map.put("EMP", new XxtEmpTable())
      map.put("DEPT", new XxtDeptTable())
      map
    }
  }

  "calcite to pekko" should {
    "demo1" in {
      // 混合执行流程，适用于任意 SQL
      val sql = """
                  |SELECT e.EMPNO, e.ENAME, d.DEPTNAME
                  |FROM EMP e JOIN DEPT d ON e.EMPNO = d.DEPTNO
                  |WHERE d.DEPTNAME = 'Sales'
                  |""".stripMargin

      println(s"Original SQL:\n$sql")

      // 调用统一流程方法
      val root      = parseToRel(sql)
      val optimized = optimize(root)
      val pgSql     = relToPostgres(optimized)
      val filters   = extractTableFilters(optimized)

      println(s"\nPostgreSQL SQL:\n$pgSql")
      println("\nExtracted Table Filters:")
      filters.foreach { case (tbl, cond) => println(s"Table: $tbl, Filter: $cond") }
    }
  }

  "demo2" in {
    // 更复杂的 SQL 示例：多条件过滤并排序
    val sql2 = """
                 |SELECT e.EMPNO, e.ENAME, d.DEPTNAME
                 |FROM EMP e
                 |JOIN DEPT d ON e.DEPTNO = d.DEPTNO
                 |WHERE e.ENAME LIKE 'A%' AND d.DEPTNAME <> 'HR'
                 |ORDER BY e.EMPNO DESC
                 |""".stripMargin

    println(s" Original SQL (demo2): $sql2")

    val root2      = parseToRel(sql2)
    val optimized2 = optimize(root2)
    val pgSql2     = relToPostgres(optimized2)
    val filters2   = extractTableFilters(optimized2)

    println(s"PostgreSQL SQL (demo2): $pgSql2")
    println(" Extracted Table Filters (demo2):")
    filters2.foreach { case (tbl, cond) => println(s"Table: $tbl, Filter: $cond") }
  }

  /**
   * 步骤1：解析并校验 SQL，返回初始 RelNode
   */
  private def parseToRel(sql: String): RelNode = {
    val config          = SqlParser.config().withCaseSensitive(false).withLex(Lex.MYSQL)
    val parsed: SqlNode = SqlParser.create(sql, config).parseStmt()

    val schema: SchemaPlus = Frameworks.createRootSchema(true)
    schema.add("MYSCHEMA", new XxtSchema)
    val fc: FrameworkConfig = Frameworks
      .newConfigBuilder()
      .defaultSchema(schema.getSubSchema("MYSCHEMA"))
      .build()
    val planner: Planner = Frameworks.getPlanner(fc)

    val validated = planner.validate(planner.parse(sql))
    val rel       = planner.rel(validated).project
    println("\nInitial Logical Plan:")
    println(RelOptUtil.dumpPlan("", rel, SqlExplainFormat.TEXT, SqlExplainLevel.ALL_ATTRIBUTES))
    rel
  }

  /**
   * 步骤2：应用优化规则，返回优化后的 RelNode
   */
  private def optimize(root: RelNode): RelNode = {
    val hep = new HepPlanner(
      new HepProgramBuilder().addRuleInstance(CoreRules.FILTER_INTO_JOIN).build()
    )
    hep.setRoot(root)
    val opt = hep.findBestExp
    println("\nOptimized Logical Plan:")
    println(RelOptUtil.dumpPlan("", opt, SqlExplainFormat.TEXT, SqlExplainLevel.ALL_ATTRIBUTES))
    opt
  }

  /**
   * 步骤3：将 RelNode 转为 PostgreSQL SQL
   */
  private def relToPostgres(rel: RelNode): String = {
    val dialect   = PostgresqlSqlDialect.DEFAULT
    val converter = new RelToSqlConverter(dialect)
    converter.visitRoot(rel).asStatement().toSqlString(dialect).getSql
  }

  /**
   * 提取每张表及其过滤条件
   */
  private def extractTableFilters(node: RelNode): Seq[(String, String)] = node match {
    case s: org.apache.calcite.rel.core.Sort => extractTableFilters(s.getInput)
    case p: Project    => extractTableFilters(p.getInput)
    case f: Filter     =>
      val base = extractTableFilters(f.getInput)
      val cond = formatCondition(f.getCondition, f.getInput)
      base.map(tbl => tbl.copy(_2 = cond))
    case j: Join       => extractTableFilters(j.getLeft) ++ extractTableFilters(j.getRight)
    case ts: TableScan => Seq(ts.getTable.getQualifiedName.asScala.mkString(".") -> "")
    case _             => Seq.empty
  }


  /**
   * 格式化过滤表达式为 SQL 可读条件
   */
  private def formatCondition(expr: RexNode, ctx: RelNode): String = expr match {
    case call: RexCall =>
      val op = call.getOperator.getName
      call.getOperands.asScala.map(formatOperand(_, ctx)).mkString(s" $op ")
    case lit: RexLiteral =>
      lit.getValue2 match {
        case s: String => s"'$s'"
        case o         => o.toString
      }
    case ref: RexInputRef =>
      val name = ctx.getRowType.getFieldList.get(ref.getIndex).getName
      s"\"$name\""
    case _ => expr.toString
  }

  private def formatOperand(node: RexNode, ctx: RelNode): String = node match {
    case lit: RexLiteral  => formatCondition(lit, ctx)
    case ref: RexInputRef => formatCondition(ref, ctx)
    case other            => other.toString
  }

}
