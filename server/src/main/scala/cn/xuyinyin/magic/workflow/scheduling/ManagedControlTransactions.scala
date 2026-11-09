package cn.xuyinyin.magic.workflow.scheduling

import java.sql.{Connection,PreparedStatement,ResultSet}
import javax.sql.DataSource
import scala.util.control.NonFatal

/** Shared lock and nested transactions keep legacy scheduling and guardian mutations serialized. */
abstract class ManagedControlTransactions(dataSource:DataSource, policyHash:String, allowPolicyInitialization:Boolean=true) {
  private[scheduling] lazy val cutoverStore:SnapshotCutoverStore=new SnapshotCutoverStore(this)
  @volatile private[scheduling] var onBorrow: Connection => Unit = _ => ()
  private val admissionConnection = new ThreadLocal[Connection]()
  private[scheduling] def read[A](f: Connection => A): A = {
    val nested = admissionConnection.get()
    if (nested != null) return f(nested)
    val c = dataSource.getConnection
    try { onBorrow(c); f(c) } finally c.close()
  }
  private[scheduling] def transaction[A](f: Connection => A): A = {
    val nested = admissionConnection.get()
    if (nested != null) f(nested)
    else read(c => transactionOn(c) { current =>
      admissionConnection.set(current)
      try f(current) finally admissionConnection.remove()
    })
  }
  private[scheduling] def transactionOn[A](c:Connection)(f:Connection=>A):A = {
    c.setAutoCommit(false)
    var transactionEnded=false
    try {
      val policy = query(c, "SELECT policy_hash FROM pekko_table_admission_lock WHERE id=1 FOR UPDATE")(rs => Option(rs.getString(1))).headOption
        .getOrElse(throw new IllegalStateException("table admission schema is not provisioned"))
      if (policy.exists(_ != policyHash)) throw new IllegalStateException("table admission policy differs from the provisioned policy")
      if (policy.isEmpty) {
        if (!allowPolicyInitialization) throw new IllegalStateException("migrated control policy has not been committed")
        update(c,"UPDATE pekko_table_admission_lock SET policy_hash=? WHERE id=1",policyHash)
      }
      val result = f(c); c.commit(); transactionEnded=true; result
    } catch { case NonFatal(e) => try {c.rollback();transactionEnded=true} catch { case NonFatal(cleanup) => e.addSuppressed(cleanup) }; throw e }
    finally if(transactionEnded)c.setAutoCommit(true)
  }
  private[scheduling] def admission[A](f:Connection=>A):A=transaction(f)
  private def bindValue(s: PreparedStatement, i: Int, v: Any): Unit = v match {
    case null => s.setNull(i, java.sql.Types.VARCHAR)
    case n: Int => s.setInt(i, n)
    case n: Long => s.setLong(i, n)
    case b: Boolean => s.setBoolean(i, b)
    case value => s.setString(i, value.toString)
  }
  private[scheduling] def query[A](c: Connection, sql: String, args: Seq[Any] = Nil)(f: ResultSet => A): Vector[A] = {
    val s = c.prepareStatement(sql); s.setQueryTimeout(30)
    try {
      args.zipWithIndex.foreach { case (v, i) => bindValue(s, i + 1, v) }
      val rs = s.executeQuery(); val b = Vector.newBuilder[A]
      try { while (rs.next()) b += f(rs); b.result() } finally rs.close()
    } finally s.close()
  }
  private[scheduling] def update(c: Connection, sql: String, args: Any*): Int = {
    val s = c.prepareStatement(sql); s.setQueryTimeout(30)
    try { args.zipWithIndex.foreach { case (v, i) => bindValue(s, i + 1, v) }; s.executeUpdate() } finally s.close()
  }
  private[scheduling] def next(c: Connection): Long = {
    update(c, "UPDATE pekko_table_admission_lock SET queue_sequence=queue_sequence+1 WHERE id=1")
    query(c, "SELECT queue_sequence FROM pekko_table_admission_lock WHERE id=1")(_.getLong(1)).head
  }
}
