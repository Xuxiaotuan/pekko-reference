package cn.xuyinyin.magic.parser.calcite

import cn.xuyinyin.magic.testkit.STSpec
import com.typesafe.config.ConfigFactory
import org.apache.calcite.DataContext
import org.apache.calcite.config.Lex
import org.apache.calcite.linq4j.{Enumerable, Linq4j}
import org.apache.calcite.plan.hep.{HepPlanner, HepProgram, HepProgramBuilder}
import org.apache.calcite.plan.{RelOptTable, RelOptUtil}
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.`type`.{RelDataType, RelDataTypeFactory, RelDataTypeField}
import org.apache.calcite.rel.core.{Filter, Join, Project, TableScan}
import org.apache.calcite.rel.rel2sql.RelToSqlConverter
import org.apache.calcite.rel.rules.CoreRules
import org.apache.calcite.rex.RexNode
import org.apache.calcite.schema.impl.{AbstractSchema, AbstractTable}
import org.apache.calcite.schema.{ScannableTable, SchemaPlus, Table}
import org.apache.calcite.sql.`type`.SqlTypeName
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.sql.{SqlExplainFormat, SqlExplainLevel, SqlNode}
import org.apache.calcite.tools.{Frameworks, Planner}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}

import java.util
import java.util.concurrent.Executors
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}
import scala.jdk.CollectionConverters.IterableHasAsJava

// 定义一个简单的 EMP 表（仅用于验证生成计划，实际数据为空）
class EmpSingleTable extends AbstractTable with ScannableTable {

  override def getRowType(typeFactory: RelDataTypeFactory): RelDataType = {
    typeFactory
      .builder()
      .add("EMPNO", typeFactory.createSqlType(SqlTypeName.INTEGER))
      .add("ENAME", typeFactory.createSqlType(SqlTypeName.VARCHAR))
      .build()
  }

  override def scan(root: DataContext): Enumerable[Array[AnyRef]] = {
    // 初始化数据：注意数组中的元素顺序必须和 getRowType 定义的一致
    val data = Seq(
      Array[AnyRef](Integer.valueOf(1), "Sales"),
      Array[AnyRef](Integer.valueOf(2), "Engineering")
    )
    // 利用 Linq4j.asEnumerable 将 Java 集合转换为 Enumerable
    Linq4j.asEnumerable(data.asJava)
  }
}

// 自定义 Schema，包含一个 EMP 表、一个DEPT表
class SingleMySchema extends AbstractSchema {
  override def getTableMap: java.util.Map[String, Table] = {
    val map = new java.util.HashMap[String, Table]()
    map.put("EMP", new EmpSingleTable())
    map
  }
}

class SingleCalciteToPekkopec extends STSpec {

  "calcite to pekko" should {
    "demo1" in {
      type Row = Map[String, Any]

      val configPekko = ConfigFactory.parseString("""
                                                    |pekko {
                                                    |  remote.artery {
                                                    |    canonical {
                                                    |      port = 0 # 使用随机端口
                                                    |    }
                                                    |  }
                                                    |}
                                                    |""".stripMargin)
      // 创建 Pekko ActorSystem 和 Materializer
      implicit val system: ActorSystem  = ActorSystem("demo", configPekko)
      implicit val mat: Materializer    = Materializer(system)
      implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(8))

      // 示例 SQL：连接 EMP 和 DEPT，过滤 DEPT.DEPTNAME = 'Sales'
      val originalSql =
        """
          |SELECT e.EMPNO, e.ENAME
          |FROM EMP e
          |""".stripMargin

      println(s"Original SQL:\n$originalSql")

      // -----------------------------------------------------
      // 【步骤1】解析 SQL，生成 SqlNode
      // -----------------------------------------------------
      val parserConfig: SqlParser.Config = SqlParser
        .config()
        .withCaseSensitive(false)
        .withLex(Lex.MYSQL)
      val sqlParser        = SqlParser.create(originalSql, parserConfig)
      val sqlNode: SqlNode = sqlParser.parseStmt()
      println("解析得到的 SqlNode:")
      println(sqlNode.toString)

      // -----------------------------------------------------
      // 【步骤2】构造 Schema，并利用 Planner 解析、校验、转换为 RelNode
      // -----------------------------------------------------
      val rootSchema: SchemaPlus = Frameworks.createRootSchema(true)
      rootSchema.add("MYSCHEMA", new SingleMySchema())
      val config = Frameworks
        .newConfigBuilder()
        .defaultSchema(rootSchema.getSubSchema("MYSCHEMA"))
        .build()
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
        .addRuleInstance(CoreRules.FILTER_INTO_JOIN) // 示例规则，将 Filter 推入 Join
        .build()
      val hepPlanner = new HepPlanner(hepProgram)
      hepPlanner.setRoot(relNode)
      val optimizedRelNode: RelNode = hepPlanner.findBestExp()

      println("优化后的 Logical Plan:")
      println(RelOptUtil.dumpPlan("", optimizedRelNode, SqlExplainFormat.TEXT, SqlExplainLevel.ALL_ATTRIBUTES))

      // -----------------------------------------------------
      // 【步骤4】将优化后的 RelNode 转换为 PostgreSQL 方言的 SQL（仅用于展示）
      // -----------------------------------------------------
      val dialect           = PostgresqlSqlDialect.DEFAULT
      val relToSqlConverter = new RelToSqlConverter(dialect)
      val sqlResult         = relToSqlConverter.visitRoot(optimizedRelNode)
      val optimizedPgSql    = sqlResult.asStatement().toSqlString(dialect).getSql

      println("转换后的 PostgreSQL SQL:")
      println(optimizedPgSql)
      println("--------------------------------\n")

      // -----------------------------------------------------
      // 【步骤5】将 Calcite 的 RelNode 转换为 Pekko Stream Source，并执行流处理
      // -----------------------------------------------------
      val source: Source[Row, NotUsed] = RelNodeToPekkoStream.convert(optimizedRelNode)
      val future                       = source.runWith(Sink.foreach(row => println(s"\n Stream 输出：$row")))

      // 等待流处理结束后关闭系统
      Await.result(future, 5.seconds)
      system.terminate()
    }

    // 定义一个简单的转换器，将 Calcite 的 RelNode 转换为 Pekko Stream 的 Source
    // 这里仅作为示例：不管 RelNode 实际是什么，都返回一个固定的 Source
    object RelNodeToPekkoStream {
      type Row = Map[String, Any]

      def scanTable(scan: TableScan): Iterator[Row] = {
        // 解析出来是什么数据库，然后读库
        val table: RelOptTable = scan.getTable
        val fieldList: util.List[RelDataTypeField] = table.getRowType.getFieldList
        println(s"scan: $scan \n table: ${table.getQualifiedName} fieldList: $fieldList")
        Iterator.empty
      }
      def evaluateFilter(row: Row, condition: RexNode): Boolean             = true
      def applyProjection(row: Row, projects: java.util.List[RexNode]): Row = row
      // 处理 Join
      def applyJoin(left: Source[Row, NotUsed], right: Source[Row, NotUsed], condition: RexNode): Source[Row, NotUsed] = {
        // 实现 Join 操作的逻辑
        left.zip(right).map { case (leftRow, rightRow) =>
          // 根据条件合并 leftRow 和 rightRow
          leftRow ++ rightRow
        }
      }

      def convert(relNode: RelNode): Source[Row, NotUsed] = {
        relNode match {
          case scan: TableScan =>
            // 处理 TableScan
            scanTable(scan)
          case filter: Filter =>
            // 处理 Filter
            val childSource = convert(filter.getInput)
            childSource.filter(row => evaluateFilter(row, filter.getCondition))
          case project: Project =>
            // 处理 Projection
            val childSource = convert(project.getInput)
            childSource.map(row => applyProjection(row, project.getProjects))
          case join: Join =>
            // 处理 Join
            val leftSource  = convert(join.getLeft)
            val rightSource = convert(join.getRight)
            applyJoin(leftSource, rightSource, join.getCondition)
          // 其他 RelNode 类型的处理逻辑
          case _ =>
            throw new UnsupportedOperationException(s"Unsupported operator: ${relNode.getClass}")
        }
        // 实际应用中，你可以对 relNode 进行模式匹配，针对不同的操作（TableScan/Filter/Join 等）生成相应的流处理逻辑
        // 这里我们直接返回一条模拟连接后的数据作为演示
        Source.single(Map("EMPNO" -> 1, "ENAME" -> "Alice", "DEPTNAME" -> "Sales"))
      }
    }
  }
}
