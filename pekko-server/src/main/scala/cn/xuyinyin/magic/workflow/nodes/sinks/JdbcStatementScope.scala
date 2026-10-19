package cn.xuyinyin.magic.workflow.nodes.sinks

import java.sql.{Connection, PreparedStatement}
import scala.collection.mutable
import scala.util.control.NonFatal

/** Statements belong to one batch transaction, never to an adapter or table lifetime. */
private[sinks] final class JdbcStatementScope(connection: Connection) extends AutoCloseable {
  private val statements = mutable.LinkedHashMap.empty[String, PreparedStatement]

  def prepare(sql: String): PreparedStatement =
    statements.getOrElseUpdate(sql, connection.prepareStatement(sql))

  override def close(): Unit = {
    var failure: Throwable = null
    statements.valuesIterator.toVector.reverseIterator.foreach { statement =>
      try statement.close()
      catch {
        case NonFatal(error) =>
          if (failure == null) failure = error
          else if (failure ne error) failure.addSuppressed(error)
      }
    }
    statements.clear()
    if (failure != null) throw failure
  }
}
