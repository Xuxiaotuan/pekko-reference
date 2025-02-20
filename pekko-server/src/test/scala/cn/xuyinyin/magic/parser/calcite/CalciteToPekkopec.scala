package cn.xuyinyin.magic.parser.calcite

import cn.xuyinyin.magic.parser.calcite.myproject.Row
import cn.xuyinyin.magic.testkit.STSpec
import com.typesafe.config.ConfigFactory
import org.apache.calcite.DataContext
import org.apache.calcite.config.Lex
import org.apache.calcite.linq4j.{Enumerable, Linq4j}
import org.apache.calcite.plan.{RelOptTable, RelOptUtil}
import org.apache.calcite.plan.hep.{HepPlanner, HepProgram, HepProgramBuilder}
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.`type`.{RelDataType, RelDataTypeFactory, RelDataTypeField}
import org.apache.calcite.rel.core.{Filter, Join, Project, TableScan}
import org.apache.calcite.rel.rel2sql.RelToSqlConverter
import org.apache.calcite.rel.rules.CoreRules
import org.apache.calcite.rex.RexNode
import org.apache.calcite.schema.impl.{AbstractSchema, AbstractTable}
import org.apache.calcite.schema.{ScannableTable, SchemaPlus, Table}
import org.apache.calcite.sql.{SqlExplainFormat, SqlExplainLevel, SqlNode}
import org.apache.calcite.sql.`type`.SqlTypeName
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.tools.{Frameworks, Planner}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}

import java.util
import java.util.concurrent.Executors
import scala.collection.convert.ImplicitConversions.`collection AsScalaIterable`
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}
import scala.jdk.CollectionConverters.IterableHasAsJava

// 定义行的类型，这里使用 Map[String, Any] 表示一行数据
package object myproject {
  type Row = Map[String, Any]
}

// 定义一个简单的 EMP 表（仅用于验证生成计划，实际数据为空）
class EmpCPTable extends AbstractTable with ScannableTable {

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

// 模拟 DEPT 表，包含字段 DEPTNO 和 DEPTNAME
class DeptCPTable extends AbstractTable with ScannableTable {
  override def getRowType(typeFactory: RelDataTypeFactory): RelDataType = {
    typeFactory
      .builder()
      .add("DEPTNO", typeFactory.createSqlType(SqlTypeName.INTEGER))
      .add("DEPTNAME", typeFactory.createSqlType(SqlTypeName.VARCHAR))
      .build()
  }

  override def scan(root: DataContext): Enumerable[Array[AnyRef]] = {
    // 初始化数据：注意数组中的元素顺序必须和 getRowType 定义的一致
    val data = Seq(
      Array[AnyRef](Integer.valueOf(1), "Alice"),
      Array[AnyRef](Integer.valueOf(2), "Sales")
    )
    // 利用 Linq4j.asEnumerable 将 Java 集合转换为 Enumerable
    Linq4j.asEnumerable(data.asJava)
  }
}

// 自定义 Schema，包含一个 EMP 表、一个DEPT表
class CPMySchema extends AbstractSchema {
  override def getTableMap: java.util.Map[String, Table] = {
    val map = new java.util.HashMap[String, Table]()
    map.put("EMP", new EmpCPTable())
    map.put("DEPT", new DeptCPTable())
    map
  }
}

class CalciteToPekkopec extends STSpec {

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
          |SELECT e.EMPNO, e.ENAME, d.DEPTNAME
          |FROM EMP e JOIN DEPT d ON e.EMPNO = d.DEPTNO
          |WHERE d.DEPTNAME = 'Sales'
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
      rootSchema.add("MYSCHEMA", new CPMySchema())
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
      def relNodeToStream(rel: RelNode): Source[Row, _] = {
        rel match {
          case scan: TableScan =>
            // 从 TableScan 获取 RelOptTable，并提取底层的 ScannableTable
            val relOptTable: RelOptTable = scan.getTable
            val table = relOptTable.unwrap(classOf[ScannableTable]) // 使用 unwrap 获取底层表
            val enumerable = table.scan(null)
            val fieldNames = scan.getRowType.getFieldNames
            val scalaIterator = scala.jdk.CollectionConverters.IteratorHasAsScala(enumerable.iterator()).asScala
            Source.fromIterator(() => scalaIterator.map(row => fieldNames.zip(row.toList).toMap))
          case filter: Filter =>
            // 处理 Filter (DEPTNAME = 'Sales')
            val inputStream = relNodeToStream(filter.getInput)
            inputStream.filter(row => row.getOrElse("DEPTNAME", "") == "Sales")
          case join: Join =>
            // 处理 Join (EMPNO = DEPTNO)
            val leftStream = relNodeToStream(join.getLeft)
            val rightStream = relNodeToStream(join.getRight)
            // 将右侧流收集到内存中作为一个查找表
            val rightFuture = rightStream.runWith(Sink.seq)
            val rightRows = Await.result(rightFuture, 5.seconds) // 等待右侧数据收集完成
            val rightLookup = rightRows.map(row => row("DEPTNO") -> row).toMap
            // 对左侧流进行匹配
            leftStream.mapConcat { left =>
              rightLookup.get(left("EMPNO")).map(right => left ++ right).toList
            }
          case project: Project =>
            // 处理 Project (选择 EMPNO, ENAME, DEPTNAME)
            val inputStream = relNodeToStream(project.getInput)
            inputStream.map(row =>
              Map(
                "EMPNO"    -> row("EMPNO"),
                "ENAME"    -> row("ENAME"),
                "DEPTNAME" -> row("DEPTNAME")
              ))
          case _ =>
            throw new UnsupportedOperationException(s"Unsupported RelNode: ${rel.getRelTypeName}")
        }
      }

      // 运行 Pekko Stream
      val stream = relNodeToStream(optimizedRelNode)
      val future = stream.runWith(Sink.foreach(row => println(s"Stream Output: $row")))
      Await.result(future, 5.seconds)

      // 清理资源
      system.terminate()
    }
  }
}
