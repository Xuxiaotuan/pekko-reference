package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.cdc.runtime.CdcRuntimeMain
import com.typesafe.config.ConfigFactory
import scala.jdk.CollectionConverters._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.io.StdIn
import spray.json._

/** Real standalone entry, including settings/extension, HTTP, coordinator and shutdown. */
object CdcRuntimeGcWorker {
  import CdcJournalWindowSupport._
  def main(args: Array[String]): Unit = {
    val Array(schema, capture, srcUser, sdkUser, gcUser, sourceUuid, stateUuid, enabled) = args
    validateSchema(schema); require(sys.env.get("RUN_RUNTIME_GC_MATRIX").contains("1"))
    val source = Settings.environment(); val state = source.copy(port = sys.env("CAPTURE_STATE_PORT").toInt)
    val captureConfig = Map[String, AnyRef](
      "id"                       -> capture,
      "host"                     -> source.host,
      "port"                     -> Int.box(source.port),
      "database"                 -> schema,
      "username"                 -> srcUser,
      "password"                 -> source.password,
      "server-id"                -> Long.box((capture.hashCode.toLong & 0x7FFFFFFFL) + 100000L),
      "source-lineage"           -> sourceUuid,
      "state-lineage"            -> stateUuid,
      "sdk-state-username"       -> sdkUser,
      "sdk-state-password"       -> state.password,
      "exclusive-sdk-accounts"   -> Boolean.box(true),
      "source-observer-username" -> source.user,
      "source-observer-password" -> source.password,
      "chunk-rows"               -> Int.box(16),
      "page-rows"                -> Int.box(4),
      "batch-size"               -> Int.box(128),
      "gc" -> Map[String, AnyRef](
        "enabled"                -> Boolean.box(enabled.toBoolean),
        "username"               -> gcUser,
        "password"               -> state.password,
        "account-host"           -> "127.0.0.1",
        "ledger-definer"         -> "root@localhost",
        // The disposable MySQL image retains its bootstrap accounts. The
        // state/control connection is the trusted observer for this probe,
        // while the bootstrap accounts remain explicit inventory entries.
        "trusted-admin-accounts" -> Vector(
          "root@localhost",
          "root@%",
          "cdc_lab@%").asJava,
        "producer-accounts"      -> Vector(sdkUser + "@127.0.0.1").asJava,
        "enable-legacy"          -> Boolean.box(true),
        "interval-ms"            -> Long.box(300),
        "batch-frames"           -> Int.box(2),
        "page-size"              -> Int.box(2)).asJava)
    val cfg = ConfigFactory
      .parseMap(
        Map[String, AnyRef](
          "pekko.pekko-sys"                                              -> ("runtime-gc-" + capture),
          "pekko.actor.provider"                                         -> "local",
          "pekko.cdc-standalone.http.port"                               -> Int.box(0),
          "pekko.workflow.cdc-platform.enabled"                          -> Boolean.box(true),
          "pekko.workflow.cdc-platform.captures"                         -> Vector(captureConfig.asJava).asJava,
          "pekko.workflow.cdc-platform.tail-delay-ms"                    -> Long.box(30),
          "pekko.workflow.cdc-platform.poll-interval-ms"                 -> Long.box(30),
          // RG02 holds one target table lock while the other table must keep
          // making progress. One snapshot slot would turn that test into a
          // global scheduler stall rather than a slow-table isolation probe.
          "pekko.workflow.cdc-platform.snapshot-steps"                   -> Int.box(2),
          "pekko.workflow.cdc-platform.tail-steps"                       -> Int.box(2),
          "pekko.workflow.mysql-cdc.state-jdbc.url"                      -> state.url(schema),
          "pekko.workflow.mysql-cdc.state-jdbc.username"                 -> state.user,
          "pekko.workflow.mysql-cdc.state-jdbc.password"                 -> state.password,
          "pekko.workflow.mysql-cdc.state-jdbc.offset-table"             -> "capture_offsets",
          "pekko.workflow.mysql-cdc.state-jdbc.history-table"            -> "capture_history",
          "pekko.workflow.mysql-cdc.state-jdbc.offset-flush-interval-ms" -> Int.box(0),
          "pekko.persistence.journal.plugin"                             -> "jdbc-journal",
          "jdbc-journal.use-shared-db"                                   -> "slick",
          "pekko-persistence-jdbc.shared-databases.slick.profile"        -> "slick.jdbc.MySQLProfile$",
          "pekko-persistence-jdbc.shared-databases.slick.db.driver"      -> "com.mysql.cj.jdbc.Driver",
          "pekko-persistence-jdbc.shared-databases.slick.db.url"         -> state.url(schema),
          "pekko-persistence-jdbc.shared-databases.slick.db.user"        -> state.user,
          "pekko-persistence-jdbc.shared-databases.slick.db.password"    -> state.password,
          "pekko.loglevel"                                               -> "WARNING",
          "pekko.stdout-loglevel"                                        -> "WARNING").asJava)
      .withFallback(ConfigFactory.load("application-test"))
    val running = Await.result(CdcRuntimeMain.start(cfg), 30.seconds)
    emit("PROCESS_READY", "pid" -> JsNumber(ProcessHandle.current().pid()), "httpPort" -> JsNumber(running.binding.localAddress.getPort))
    try {
      Iterator.continually(StdIn.readLine()).takeWhile(x => x != null && x != "STOP").foreach { cmd =>
        if (cmd == "CRASH") { System.out.flush(); Runtime.getRuntime.halt(86) }
      }
    } finally {
      running.system.terminate(); Await.result(running.system.whenTerminated, 40.seconds)
      emit("PROCESS_STOPPED")
    }
  }
}
