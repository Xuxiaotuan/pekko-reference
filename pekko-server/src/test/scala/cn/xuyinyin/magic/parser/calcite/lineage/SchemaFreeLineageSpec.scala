package cn.xuyinyin.magic.parser.calcite.lineage

import cn.xuyinyin.magic.testkit.STSpec
import org.apache.calcite.config.Lex
import org.apache.calcite.jdbc.CalciteSchema
import org.apache.calcite.plan.hep.{HepPlanner, HepProgramBuilder}
import org.apache.calcite.rel.`type`.RelDataTypeFactory
import org.apache.calcite.rel.core._
import org.apache.calcite.rel.rules.CoreRules
import org.apache.calcite.rel.{RelNode, RelVisitor}
import org.apache.calcite.rex.{RexInputRef, RexNode, RexShuttle}
import org.apache.calcite.schema.Table
import org.apache.calcite.schema.impl.{AbstractSchema, AbstractTable}
import org.apache.calcite.sql.`type`.SqlTypeName
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.sql.util.SqlBasicVisitor
import org.apache.calcite.sql.{SqlCall, SqlIdentifier, SqlNode}
import org.apache.calcite.tools.{Frameworks, Planner}

import java.util
import scala.annotation.tailrec
import scala.collection.convert.ImplicitConversions.`collection AsScalaIterable`
import scala.collection.mutable
import scala.jdk.CollectionConverters._

class SchemaFreeLineageSpec extends STSpec {

  private class DynamicSchema extends AbstractSchema {
    private val tableMap = new util.HashMap[String, Table]()
    override def getTableMap: util.Map[String, Table] = tableMap
    def addFakeTable(tableName: String, fields: Set[String]): Unit = {
      tableMap.put(tableName, new FakeTable(fields))
    }
  }

  private class FakeTable(fields: Set[String]) extends AbstractTable {
    override def getRowType(typeFactory: RelDataTypeFactory): org.apache.calcite.rel.`type`.RelDataType = {
      val builder = typeFactory.builder()
      fields.toSeq.sorted.foreach { fieldName =>
        builder.add(fieldName, typeFactory.createSqlType(SqlTypeName.VARCHAR))
      }
      builder.build()
    }
  }

  private def extractTablesAndAliases(parsedSql: SqlNode): Map[String, String] = {
    val aliasToRealTable = mutable.Map[String, String]()
    var inFromOrJoinContext: Boolean = false

    parsedSql.accept(new SqlBasicVisitor[Unit]() {
      override def visit(call: SqlCall): Unit = {
        call.getKind.toString match {
          case "AS" =>
            if (inFromOrJoinContext) {
              val leftNode: SqlNode = call.operand(0)
              val rightNode: SqlNode = call.operand(1)

              if (leftNode.isInstanceOf[SqlIdentifier] && rightNode.isInstanceOf[SqlIdentifier]) {
                val real: SqlIdentifier = leftNode.asInstanceOf[SqlIdentifier]
                val alias: SqlIdentifier = rightNode.asInstanceOf[SqlIdentifier]

                if (real.names.size() == 2) {
                  aliasToRealTable.put(alias.getSimple, real.names.mkString("."))
                }

                real.accept(this)
              }
            }


          case "SELECT" =>
            val select = call.asInstanceOf[org.apache.calcite.sql.SqlSelect]
            val prev = inFromOrJoinContext
            inFromOrJoinContext = true
            if (select.getFrom != null) select.getFrom.accept(this)
            inFromOrJoinContext = prev
            if (select.getWhere != null) select.getWhere.accept(this)
            if (select.getSelectList != null) select.getSelectList.asScala.foreach(_.accept(this))

          case "JOIN" =>
            val prev = inFromOrJoinContext
            inFromOrJoinContext = true
            call.getOperandList.asScala.foreach {
              case sub if sub != null => sub.accept(this)
              case _ =>
            }
            inFromOrJoinContext = prev

          case _ =>
            call.getOperandList.asScala.foreach {
              case sub if sub != null => sub.accept(this)
              case _ =>
            }
        }
      }

      override def visit(id: SqlIdentifier): Unit = {
        if (inFromOrJoinContext && id.names.size() == 2) {
          val alias = id.names.get(0)
          if (!aliasToRealTable.contains(alias)) {
            aliasToRealTable.put(alias, s"MYSCHEMA.$alias")
          }
        }
      }
    })

    aliasToRealTable.toMap
  }

  private def parseAndOptimize(sql: String): RelNode = {
    val rootSchema1 = CalciteSchema.createRootSchema(true)
    val dynamicSchema1 = new DynamicSchema()
    rootSchema1.add("MYSCHEMA", dynamicSchema1)

    val config1 = Frameworks
      .newConfigBuilder()
      .defaultSchema(rootSchema1.plus())
      .parserConfig(SqlParser.config().withLex(Lex.MYSQL).withCaseSensitive(false))
      .build()

    val planner1 = Frameworks.getPlanner(config1)
    val parsedSql = planner1.parse(sql)
    planner1.close()

    val aliasToRealTable = extractTablesAndAliases(parsedSql)
    println(s"\n>>> 提取到表和别名: $aliasToRealTable")

    val rootSchema2 = CalciteSchema.createRootSchema(true)
    val dynamicSchema2 = new DynamicSchema()
    rootSchema2.add("MYSCHEMA", dynamicSchema2)

    aliasToRealTable.foreach { case (alias, realTable) =>
      val fields = Set("EMPNO", "DNAME", "DEPTNO")
      println(s"注册虚表: $alias -> $realTable，字段: $fields")
      dynamicSchema2.addFakeTable(alias, fields)

      val tableOnly = realTable.split("\\.").last
      if (!dynamicSchema2.getTableMap.containsKey(tableOnly)) {
        println(s"注册真实表: $tableOnly -> $realTable，字段: $fields")
        dynamicSchema2.addFakeTable(tableOnly, fields)
      }
    }

    val config2 = Frameworks
      .newConfigBuilder()
      .defaultSchema(rootSchema2.plus())
      .parserConfig(SqlParser.config().withLex(Lex.MYSQL).withCaseSensitive(false))
      .build()

    val planner2 = Frameworks.getPlanner(config2)
    val parsed = planner2.parse(sql)
    val validated = try {
      planner2.validate(parsed)
    } catch {
      case e: Exception =>
        println(s">>> 校验失败: ${e.getMessage}")
        throw e
    }
    val originalRel = planner2.rel(validated).project

    val hepProgram = new HepProgramBuilder()
      .addRuleInstance(CoreRules.FILTER_INTO_JOIN)
      .build()

    val hepPlanner = new HepPlanner(hepProgram)
    hepPlanner.setRoot(originalRel)
    val best = hepPlanner.findBestExp()
    planner2.close()
    best
  }

  case class ColumnOrigin(table: String, column: String)
  type LineageMap = mutable.Map[String, Set[ColumnOrigin]]

  class LineageVisitor extends RelVisitor {
    val lineageMap: LineageMap = mutable.Map.empty
    private var currentContext: List[String] = Nil

    override def visit(node: RelNode, ordinal: Int, parent: RelNode): Unit = {
      node match {
        case scan: TableScan  => handleTableScan(scan)
        case project: Project => handleProject(project)
        case filter: Filter   => handleFilter(filter)
        case join: Join       => handleJoin(join)
        case agg: Aggregate   => handleAggregate(agg)
        case _                =>
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

      go(project.getInput)
      project.getProjects.asScala.zipWithIndex.foreach { case (expr, idx) =>
        val targetField = currentContext(idx)
        val inputRefs = extractInputRefs(expr)
        val origins = inputRefs.flatMap { ref =>
          val originPath = getOriginPath(project.getInput, ref.getIndex)
          lineageMap.getOrElse(originPath, Set.empty)
        }.toSet
        lineageMap(targetField) =
          if (origins.nonEmpty) origins
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
            case ref: RexInputRef => getOriginPath(project.getInput, ref.getIndex)
            case _ =>
              val inputIndex = project.getInput.getRowType.getFieldList.get(index).getIndex
              getOriginPath(project.getInput, inputIndex)
          }

        case join: Join =>
          if (index < join.getLeft.getRowType.getFieldCount) {
            getOriginPath(join.getLeft, index)
          } else {
            getOriginPath(join.getRight, index - join.getLeft.getRowType.getFieldCount)
          }

        case agg: Aggregate =>
          getOriginPath(agg.getInput, index)

        case _ =>
          if (rel.getInputs.size() > 0) getOriginPath(rel.getInput(0), index)
          else s"unknown.${rel.getRowType.getFieldList.get(index).getName}"
      }
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
      filter.getInputs.asScala.foreach(go)
    }

    private def handleJoin(join: Join): Unit = {
      join.getRowType.getFieldList.asScala.zipWithIndex.foreach { case (field, idx) =>
        val origin = if (idx < join.getLeft.getRowType.getFieldCount) {
          getOriginPath(join.getLeft, idx)
        } else {
          getOriginPath(join.getRight, idx - join.getLeft.getRowType.getFieldCount)
        }
        lineageMap(field.getName) = lineageMap.getOrElse(origin, Set.empty)
      }
      go(join.getLeft)
      go(join.getRight)
    }

    private def handleAggregate(agg: Aggregate): Unit = {
      go(agg.getInput)
      agg.getRowType.getFieldList.asScala.zipWithIndex.foreach { case (field, idx) =>
        val inputOrigin = getOriginPath(agg.getInput, agg.getGroupSet.asList().get(idx))
        lineageMap(field.getName) = lineageMap.getOrElse(inputOrigin, Set.empty)
      }
    }
  }

  "Schema-Free Parser" should {
    "parse SQL dynamically" in {
      val sql =
        """
          |SELECT e.EMPNO AS employee_id,
          |       d.DNAME AS dept_name
          |FROM MYSCHEMA.EMP e
          |JOIN MYSCHEMA.DEPT d ON e.DEPTNO = d.DEPTNO
          |""".stripMargin
      val rel = parseAndOptimize(sql)
      val visitor = new LineageVisitor()
      visitor.go(rel)
      val lineage = visitor.lineageMap
      println("\n===== 血缘关系 =====")
      lineage.foreach { case (field, origins) =>
        println(s"字段: $field -> 来源: ${origins.mkString(", ")}")
      }
    }
  }
}
