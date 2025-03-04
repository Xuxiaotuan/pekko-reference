package cn.xuyinyin.magic.parser.calcite
import org.apache.calcite.config.Lex
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.sql.parser.ddl.SqlDdlParserImpl
import org.apache.calcite.sql._

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * @author : XuJiaWei
 * @since : 2024-06-25 17:01
 */
object CalciteMysqlSqlParser {

  val parserConfig: SqlParser.Config = SqlParser
    .config()
    .withCaseSensitive(false)
    .withLex(Lex.MYSQL)

  val ddlParserConfig: SqlParser.Config = SqlParser
    .config()
    .withCaseSensitive(false)
    .withParserFactory(SqlDdlParserImpl.FACTORY)
    .withLex(Lex.MYSQL)

  def parseSql(sql: String): SqlParser = SqlParser.create(iDonKnowWhyIHaveToDoIt(sql), parserConfig)

  // ( ˚ཫ˚ )
  private def iDonKnowWhyIHaveToDoIt(sql: String): String = {
    var cSql = sql
      .replace("\\\"", "\'")
      .replace("\"", "'")
      .replace("!=", "<>")
    if (cSql.contains("CONVERT") && cSql.contains("GBK")) {
      cSql = hackConflictTokenNoConvertMask.foldLeft(cSql) { (sqlhack, e) => sqlhack.replace(e._1, e._2) }
    } else {
      cSql = hackConflictTokenMask.foldLeft(cSql) { (sqlhack, e) => sqlhack.replace(e._1, e._2) }
    }
    cSql
  }
  // ( ˚ཫ˚ )
  val hackConflictToken: Set[String] = Set("convert", "day", "date", "json_array", "using")
  // ( ˚ཫ˚  don't know need add `convert` key to convert)
  val hackConflictTokenNoConvert: Set[String] = Set("day", "date", "json_array", "using")

  private val hackConflictTokenMask = hackConflictToken
    .flatMap(t =>
      Seq(
        s"${t.toUpperCase}("  -> s"`$t`(",
        s"${t.toUpperCase} (" -> s"`$t`(",
        s"$t("                -> s"`$t`(",
        s"$t ("               -> s"`$t`("
      ))
    .toMap

  private val hackConflictTokenNoConvertMask = hackConflictTokenNoConvert
    .flatMap(t =>
      Seq(
        s"${t.toUpperCase}("  -> s"`$t`(",
        s"${t.toUpperCase} (" -> s"`$t`(",
        s"$t("                -> s"`$t`(",
        s"$t ("               -> s"`$t`("
      ))
    .toMap

  // ( ˚ཫ˚ )
  private val hackMysqlTokenMap = hackConflictToken.flatMap(x => Seq(s"`$x`" -> x, s"`${x.toUpperCase}`" -> x)).toMap

  val hackCalciteTokenMap: Map[String, String] = Map(" BETWEEN ASYMMETRIC " -> " BETWEEN ")

  /**
   * Extract column identifier of top level select term.
   */
  def extractTopSelectColumnTerms(selectSql: String): Either[Throwable, Seq[String]] = Try {

    val colTokens = mutable.LinkedHashSet[String]()

    def caseNode(node: SqlNode): Unit = node match {
      case n: SqlIdentifier =>
        colTokens += n.toString
      case n: SqlSelect =>
        caseNode(n.getSelectList)
      case n: SqlOrderBy =>
        caseNode(n.query)
      case n: SqlJoin =>
        caseNode(n.getLeft)
        caseNode(n.getRight)
      case n: SqlBasicCall =>
        n.getKind match {
          case SqlKind.AS =>
            colTokens += n.getOperandList.get(1).toString
          case SqlKind.UNION =>
            caseNode(n.getOperandList.get(0))
            caseNode(n.getOperandList.get(1))
          case SqlKind.OTHER_FUNCTION =>
            if (n.toString.equalsIgnoreCase("""count(*)""") || n.toString.equalsIgnoreCase("""count(1)"""))
              colTokens += n.toString
            else
              n.getOperandList.forEach(caseNode(_))
          case _ =>
        }
      case n: SqlNodeList =>
        n.asScala.foreach(caseNode)
      case _ =>
    }

    val node = parseSql(selectSql).parseQuery()
    caseNode(node)
    colTokens.toSeq
  }.toEither

  def extractTableRepFromSelectSql(sql: String): Either[Throwable, Seq[String]] = Try {
    val parser  = parseSql(sql)
    val sqlNode = parser.parseStmt()
    val tid     = ListBuffer[String]()

    def retrieveNode(node: SqlNode, cteAliases: Set[String] = Set.empty, inTableContext: Boolean = false): Unit = node match {
      case n: SqlWith =>
        val newAliases = n.withList.asScala.map { case item: SqlWithItem =>
          item.name.getSimple
        }.toSet
        // 处理每个CTE定义（携带叠加后的别名集合）
        n.withList.forEach { item =>
          retrieveNode(item, cteAliases ++ newAliases, inTableContext = true)
        }
        // 处理主查询体
        retrieveNode(n.body, cteAliases ++ newAliases)

      // 处理 CTE 定义项
      case n: SqlWithItem =>
        // 递归处理CTE查询体时：
        // 1. 携带叠加后的CTE上下文
        // 2. 允许查询体访问当前CTE之前的别名
        retrieveNode(n.query, cteAliases, inTableContext = true)

      // 核心表标识符处理
      case n: SqlIdentifier =>
        if (inTableContext && !cteAliases.contains(n.toString)) {
          tid += n.toString
        }

      // SELECT语句处理
      case n: SqlSelect =>
        // FROM进入表上下文
        retrieveNode(n.getFrom, cteAliases, inTableContext = true)
        Option(n.getWhere).foreach(retrieveNode(_, cteAliases))
        n.getSelectList.forEach(retrieveNode(_, cteAliases))

      // JOIN处理
      case n: SqlJoin =>
        retrieveNode(n.getLeft, cteAliases, inTableContext = true)
        retrieveNode(n.getRight, cteAliases, inTableContext = true)

      // 表达式处理
      case n: SqlBasicCall =>
        n.getKind match {
          case SqlKind.AS =>
            retrieveNode(n.getOperandList.get(0), cteAliases, inTableContext)

          case SqlKind.UNION | SqlKind.INTERSECT | SqlKind.EXCEPT =>
            n.getOperandList.forEach(retrieveNode(_, cteAliases, inTableContext))

          case SqlKind.IN | SqlKind.EXISTS | SqlKind.SCALAR_QUERY =>
            n.getOperandList.forEach {
              case sub: SqlSelect => retrieveNode(sub, cteAliases)
              case other          => retrieveNode(other, cteAliases)
            }

          case _ =>
            n.getOperandList.forEach(retrieveNode(_, cteAliases))
        }

      // 其他结构处理
      case n: SqlOrderBy                      => retrieveNode(n.query, cteAliases)
      case n: SqlSnapshot                     => retrieveNode(n.getTableRef, cteAliases, inTableContext = true)
      case _: SqlLiteral | _: SqlDynamicParam => // 忽略字面量和参数
      case unexpected                         =>
        // 允许跳过节点列表
        if (!unexpected.isInstanceOf[SqlNodeList]) {
          throw new Exception(s"Unsupported node type: ${unexpected.getClass.getSimpleName}")
        }
    }

    retrieveNode(sqlNode)
    tid.distinct.toSeq
  }.toEither
}
