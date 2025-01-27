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

  val parserConfig: SqlParser.Config = SqlParser.config()
    .withCaseSensitive(false)
    .withLex(Lex.MYSQL)

  val ddlParserConfig: SqlParser.Config = SqlParser.config()
    .withCaseSensitive(false)
    .withParserFactory(SqlDdlParserImpl.FACTORY)
    .withLex(Lex.MYSQL)


  def parseSql(sql: String): SqlParser = SqlParser.create(iDonKnowWhyIHaveToDoIt(sql), parserConfig)

  // ( ˚ཫ˚ )
  private def iDonKnowWhyIHaveToDoIt(sql: String): String = {
    var cSql = sql
      .replace ("\\\"", "\'")
      .replace("\"", "'")
      .replace("!=", "<>")
    if (cSql.contains("CONVERT") && cSql.contains("GBK")){
      cSql = hackConflictTokenNoConvertMask.foldLeft(cSql) { (sqlhack, e) => sqlhack.replace(e._1, e._2) }
    }else {
      cSql = hackConflictTokenMask.foldLeft(cSql) { (sqlhack, e) => sqlhack.replace(e._1, e._2) }
    }
    cSql
  }
  // ( ˚ཫ˚ )
  val hackConflictToken: Set[String] = Set("convert", "day", "date","json_array", "using")
  // ( ˚ཫ˚  don't know need add `convert` key to convert)
  val hackConflictTokenNoConvert: Set[String] = Set("day", "date","json_array", "using")

  private val hackConflictTokenMask = hackConflictToken.flatMap(t => Seq(
    s"${t.toUpperCase}(" -> s"`$t`(",
    s"${t.toUpperCase} (" -> s"`$t`(",
    s"$t(" -> s"`$t`(",
    s"$t (" -> s"`$t`("
  )).toMap

  private val hackConflictTokenNoConvertMask = hackConflictTokenNoConvert.flatMap(t => Seq(
    s"${t.toUpperCase}(" -> s"`$t`(",
    s"${t.toUpperCase} (" -> s"`$t`(",
    s"$t(" -> s"`$t`(",
    s"$t (" -> s"`$t`("
  )).toMap

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
      case n: SqlBasicCall => n.getKind match {
        case SqlKind.AS =>
          colTokens += n.getOperandList.get(1).toString
        case SqlKind.UNION =>
          caseNode(n.getOperandList.get(0))
          caseNode(n.getOperandList.get(1))
        case SqlKind.OTHER_FUNCTION =>
          if(n.toString.equalsIgnoreCase("""count(*)""") || n.toString.equalsIgnoreCase("""count(1)"""))
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

  /**
   * Extract effective table name from select statement.
   */
  def extractTableRepFromSelectSql(sql: String): Either[Throwable, Seq[String]] = Try {
    val parser = parseSql(sql)
    val sqlNode = parser.parseStmt()
    val tid = ListBuffer[String]()
    retrieveNode(sqlNode, tid)

    def retrieveNode(node: SqlNode, tid: ListBuffer[String]): Unit = node match {
      case n: SqlIdentifier => tid += n.toString
      case n: SqlSelect => retrieveNode(n.getFrom, tid)
      case n: SqlOrderBy => retrieveNode(n.query, tid)
      case n: SqlJoin =>
        retrieveNode(n.getLeft, tid)
        retrieveNode(n.getRight, tid)
      case n: SqlBasicCall =>
        n.getKind match {
          case SqlKind.AS => retrieveNode(n.getOperandList.get(0), tid)
          case SqlKind.UNION =>
            retrieveNode(n.getOperandList.get(0), tid)
            retrieveNode(n.getOperandList.get(1), tid)
          case _ =>
        }
      case n => throw new Exception(s"The sql should be a select statement, token=${n.getKind}")
    }

    tid.toSeq
  }.toEither

}