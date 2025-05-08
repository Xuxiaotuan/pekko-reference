package cn.xuyinyin.magic.parser.calcite.lineage

import cn.xuyinyin.magic.parser.calcite.SingleMySchema
import cn.xuyinyin.magic.testkit.STSpec
import org.apache.calcite.rel.core.{Aggregate, Filter, Join, Project, TableScan}
import org.apache.calcite.rel.rules.CoreRules
import org.apache.calcite.rel.{RelNode, RelVisitor}
import org.apache.calcite.rex.{RexInputRef, RexNode, RexShuttle}
import org.apache.calcite.schema.SchemaPlus
import org.apache.calcite.tools.Planner

import scala.annotation.tailrec
import scala.collection.mutable
import scala.jdk.CollectionConverters._

class DataLineageSpec extends STSpec {

  // 定义血缘关系存储结构
  case class ColumnOrigin(table: String, column: String)
  type LineageMap = mutable.Map[String, Set[ColumnOrigin]]

  // 自定义血缘分析Visitor
  class LineageVisitor extends RelVisitor {
    val lineageMap: LineageMap = mutable.Map.empty

    // 用于跟踪当前处理字段的上下文（如Project后的字段名）
    private var currentContext: List[String] = Nil

    override def visit(node: RelNode, ordinal: Int, parent: RelNode): Unit = {
      node match {
        case scan: TableScan =>
          handleTableScan(scan)

        case project: Project =>
          handleProject(project)

        case filter: Filter =>
          handleFilter(filter)

        case join: Join =>
          handleJoin(join)

        case agg: Aggregate =>
          handleAggregate(agg)
        case _ =>
      }
      super.visit(node, ordinal, parent)
    }

    private def handleTableScan(scan: TableScan): Unit = {
      val tableName = scan.getTable.getQualifiedName.asScala.mkString(".")
      scan.getRowType.getFieldList.asScala.foreach { field =>
        val fullName = s"$tableName.${field.getName}"
        lineageMap(fullName) = Set(ColumnOrigin(tableName, field.getName))
      }
    }

    private def handleProject(project: Project): Unit = {
      val oldContext = currentContext
      currentContext = project.getRowType.getFieldNames.asScala.toList

      println(s"\n=== Processing Project ===")
      println(s"Output fields: ${currentContext.mkString(", ")}")
      println(s"Input fields: ${project.getInput.getRowType.getFieldNames.asScala.mkString(", ")}")

      go(project.getInput)
      project.getProjects.asScala.zipWithIndex.foreach { case (expr, idx) =>
        val targetField = currentContext(idx)
        val inputRefs = extractInputRefs(expr)
        // 处理复合表达式的情况
        val origins = inputRefs.flatMap { ref =>
          val originPath = getOriginPath(project.getInput, ref.getIndex)
          lineageMap.getOrElse(originPath, Set.empty)
        }.toSet
        // 只有输入字段为空时才标记为表达式
        lineageMap(targetField) = if (origins.nonEmpty) origins
        else Set(ColumnOrigin("expression", expr.toString))
      }
      currentContext = oldContext
    }

    @tailrec
    private def getOriginPath(rel: RelNode, index: Int): String = {
      rel match {
        case scan: TableScan =>
          val tableName = scan.getTable.getQualifiedName.asScala.mkString(".")
          s"$tableName.${scan.getRowType.getFieldList.get(index).getName}"

        case project: Project =>
          project.getProjects.get(index) match {
            case ref: RexInputRef =>
              getOriginPath(project.getInput, ref.getIndex)
            case _ =>
              // 处理表达式嵌套的情况
              val inputIndex = project.getInput.getRowType.getFieldList.get(index).getIndex
              getOriginPath(project.getInput, inputIndex)
          }

        case join: Join =>
          if (index < join.getLeft.getRowType.getFieldCount) {
            getOriginPath(join.getLeft, index)
          } else {
            getOriginPath(join.getRight, index - join.getLeft.getRowType.getFieldCount)
          }

        case _ =>
          // 递归查找直到找到表扫描
          if (rel.getInputs.size() > 0) {
            getOriginPath(rel.getInput(0), index)
          } else {
            s"unknown.${rel.getRowType.getFieldList.get(index).getName}"
          }
      }
    }

    private def getQualifiedInputName(inputRel: RelNode, index: Int): String = {
      @tailrec
      def findOrigin(rel: RelNode, idx: Int): String = rel match {
        case scan: TableScan =>
          s"${scan.getTable.getQualifiedName.asScala.mkString(".")}.${scan.getRowType.getFieldList.get(idx).getName}"

        case project: Project =>
          project.getProjects.get(idx) match {
            case ref: RexInputRef => findOrigin(project.getInput, ref.getIndex)
            case _ => findOrigin(project.getInput, idx) // 表达式索引处理
          }

        case join: Join =>
          if (idx < join.getLeft.getRowType.getFieldCount) findOrigin(join.getLeft, idx)
          else findOrigin(join.getRight, idx - join.getLeft.getRowType.getFieldCount)

        case _ =>
          rel.getRowType.getFieldList.get(idx).getName
      }
      findOrigin(inputRel, index)
    }

    private def extractInputRefs(rexNode: RexNode): List[RexInputRef] = {
      val refs = mutable.ListBuffer[RexInputRef]()
      rexNode.accept(new RexShuttle {
        override def visitInputRef(inputRef: RexInputRef): RexNode = {
          refs += inputRef
          inputRef
        }
      })
      refs.toList
    }

    private def handleFilter(filter: Filter): Unit = {
      // 过滤器不影响字段的血缘关系，只需要处理子节点
      filter.getInputs.asScala.foreach(go)
    }

    private def handleJoin(join: Join): Unit = {
      join.getRowType.getFieldList.asScala.zipWithIndex.foreach { case (field, idx) =>
        val origin = if (idx < join.getLeft.getRowType.getFieldCount) {
          getQualifiedInputName(join.getLeft, idx)
        } else {
          getQualifiedInputName(join.getRight, idx - join.getLeft.getRowType.getFieldCount)
        }
        lineageMap(field.getName) = lineageMap.getOrElse(origin, Set.empty)
      }

      // 处理左右输入
      go(join.getLeft)
      go(join.getRight)

      // 处理Join条件中的字段引用（可选）
      val conditionRefs = extractInputRefs(join.getCondition)
      conditionRefs.foreach { ref =>
        // 可以记录Join条件字段的使用情况
      }
    }

    private def handleAggregate(agg: Aggregate): Unit = {
      // 处理聚合字段
      agg.getInput.getRowType.getFieldList.asScala.zipWithIndex.foreach {
        case (field, idx) =>
          val origin = getQualifiedInputName(agg.getInput, idx)
          lineageMap(field.getName) = lineageMap.getOrElse(origin, Set.empty)
      }
    }

  }

  "Data Lineage Analysis" should {
    /**
     * === Processing Project ===
     * Output fields: employee_id, ENAME
     * Input fields: EMPNO, ENAME
     *
     * Processing expression 0: $0
     * Target field: employee_id
     * Input refs: 0
     * Resolved input 0: MYSCHEMA.EMP.EMPNO
     * Input field MYSCHEMA.EMP.EMPNO has origins: Some(Set(ColumnOrigin(MYSCHEMA.EMP,EMPNO)))
     * Set employee_id -> ColumnOrigin(MYSCHEMA.EMP,EMPNO)
     *
     * Processing expression 1: $1
     * Target field: ENAME
     * Input refs: 1
     * Resolved input 1: MYSCHEMA.EMP.ENAME
     * Input field MYSCHEMA.EMP.ENAME has origins: Some(Set(ColumnOrigin(MYSCHEMA.EMP,ENAME)))
     * Set ENAME -> ColumnOrigin(MYSCHEMA.EMP,ENAME)
     *
     * Final Lineage Map:
     * MYSCHEMA.EMP.ENAME -> ColumnOrigin(MYSCHEMA.EMP,ENAME)
     * ENAME -> ColumnOrigin(MYSCHEMA.EMP,ENAME)
     * employee_id -> ColumnOrigin(MYSCHEMA.EMP,EMPNO)
     * MYSCHEMA.EMP.EMPNO -> ColumnOrigin(MYSCHEMA.EMP,EMPNO)
     *
     */
    "trace column origins with aliases" in {
      val sql =
        """
          |SELECT e.EMPNO AS employee_id, e.ENAME
          |FROM MYSCHEMA.EMP e
          |WHERE e.EMPNO > 100
          |""".stripMargin
      val (optimizedRelNode: RelNode, _) = parseAndOptimize(sql)
      val visitor = new LineageVisitor()
      visitor.go(optimizedRelNode)
      val lineage = visitor.lineageMap
      // 打印血缘关系
      println("\nFinal Lineage Map:")
      lineage.foreach { case (field, origins) => println(s"$field -> ${origins.mkString(", ")}")}
      // 验证结果
      lineage.getOrElse("employee_id", Set.empty) should contain (ColumnOrigin("MYSCHEMA.EMP", "EMPNO"))
      lineage.getOrElse("ENAME", Set.empty) should contain (ColumnOrigin("MYSCHEMA.EMP", "ENAME"))
    }

    /**
     * === Processing Project ===
     * Output fields: emp_id_plus, lower_name
     * Input fields: EMPNO, ENAME
     *
     * Processing expression 0: +($0, 1)
     * Target field: emp_id_plus
     * Input refs: 0
     * Resolved input 0: MYSCHEMA.EMP.EMPNO
     * Input field MYSCHEMA.EMP.EMPNO has origins: Some(Set(ColumnOrigin(MYSCHEMA.EMP,EMPNO)))
     * Set emp_id_plus -> ColumnOrigin(MYSCHEMA.EMP,EMPNO)
     *
     * Processing expression 1: LOWER($1)
     * Target field: lower_name
     * Input refs: 1
     * Resolved input 1: MYSCHEMA.EMP.ENAME
     * Input field MYSCHEMA.EMP.ENAME has origins: Some(Set(ColumnOrigin(MYSCHEMA.EMP,ENAME)))
     * Set lower_name -> ColumnOrigin(MYSCHEMA.EMP,ENAME)
     */
    "trace complex expressions" in {
      val sql =
        """
          |SELECT e.EMPNO + 1 AS emp_id_plus,
          |       LOWER(e.ENAME) AS lower_name
          |FROM MYSCHEMA.EMP e
          |""".stripMargin
      val (rel, _) = parseAndOptimize(sql)
      val visitor = new LineageVisitor()
      visitor.go(rel)
      val lineage = visitor.lineageMap
      // 打印血缘关系
      println(s"\nFinal Lineage Map: $lineage")
      lineage.foreach { case (field, origins) => println(s"$field -> ${origins.mkString(", ")}")}
      lineage.getOrElse("emp_id_plus", Set.empty) should contain (ColumnOrigin("MYSCHEMA.EMP", "EMPNO"))
      lineage.getOrElse("lower_name", Set.empty) should contain (ColumnOrigin("MYSCHEMA.EMP", "ENAME"))
    }
  }

  private def parseAndOptimize(sql: String): (RelNode, String) = {
    import org.apache.calcite.config.Lex
    import org.apache.calcite.plan.hep.{HepPlanner, HepProgramBuilder}
    import org.apache.calcite.sql.parser.SqlParser
    import org.apache.calcite.tools.Frameworks

    // 1. 初始化配置
    val rootSchema: SchemaPlus = Frameworks.createRootSchema(true)
    rootSchema.add("MYSCHEMA", new SingleMySchema())

    val config = Frameworks.newConfigBuilder()
      .defaultSchema(rootSchema.getSubSchema("MYSCHEMA"))
      .parserConfig(
        SqlParser.config()
          .withLex(Lex.MYSQL)
          .withCaseSensitive(false)
      )
      .build()

    // 2. 解析和优化SQL
    var planner: Planner = null
    try {
      planner = Frameworks.getPlanner(config)

      val parsedSql = planner.parse(sql)
      val validatedSql = planner.validate(parsedSql)
      val originalRel = planner.rel(validatedSql).project

      val hepProgram = new HepProgramBuilder()
        .addRuleInstance(CoreRules.FILTER_INTO_JOIN)
        .build()

      val hepPlanner = new HepPlanner(hepProgram)
      hepPlanner.setRoot(originalRel)
      val optimizedRel = hepPlanner.findBestExp()

      (optimizedRel, sql)
    } finally {
      if (planner != null) {
        planner.close()
      }
    }
  }
}