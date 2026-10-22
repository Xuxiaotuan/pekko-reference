package cn.xuyinyin.magic.workflow.integration

import CdcJournalWindowSupport._
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import spray.json._
import java.sql.Connection
import java.nio.file.Path

private[integration] object TableSchedulingExperimentSupport {
  val TableCount=16
  def provision(settings:Settings,schema:String):Unit={
    initialize(settings,schema)
    withConnection(settings,schema) { c =>
      Vector("db/mysql/pekko-snapshot-batch-evidence-schema.sql","db/mysql/pekko-table-scheduling-schema.sql").foreach { resource =>
        val in=getClass.getClassLoader.getResourceAsStream(resource);val text=try scala.io.Source.fromInputStream(in).mkString finally in.close()
        text.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c,_))
      }
      executeSql(c,"CREATE TABLE table_audit (audit_id BIGINT AUTO_INCREMENT PRIMARY KEY,table_id INT NOT NULL,op CHAR(1),row_id BIGINT,revision INT)")
      (0 until TableCount).foreach { i =>
        executeSql(c,s"CREATE TABLE source_$i LIKE source_rows")
        executeSql(c,s"CREATE TABLE target_$i LIKE target_rows")
        if(i!=15) {
          executeSql(c,s"INSERT INTO source_$i SELECT id*5-25,label,amount,note,updated_at,revision FROM source_rows")
          executeSql(c,s"INSERT INTO source_$i VALUES (9007199254740993,'大主键🙂',-123.4500,'','2026-10-21 23:59:59.999999',3)")
          if(i==0) (1 to 3).foreach(n=>executeSql(c,s"INSERT INTO source_$i SELECT id+${n*10000},label,amount,note,updated_at,revision FROM source_rows"))
        }
        executeSql(c,s"CREATE TRIGGER table_insert_$i AFTER INSERT ON target_$i FOR EACH ROW INSERT INTO table_audit (table_id,op,row_id,revision) VALUES ($i,'I',NEW.id,NEW.revision)")
        executeSql(c,s"CREATE TRIGGER table_update_$i AFTER UPDATE ON target_$i FOR EACH ROW INSERT INTO table_audit (table_id,op,row_id,revision) VALUES ($i,'U',NEW.id,NEW.revision)")
        executeSql(c,s"CREATE TRIGGER table_delete_$i AFTER DELETE ON target_$i FOR EACH ROW INSERT INTO table_audit (table_id,op,row_id,revision) VALUES ($i,'D',OLD.id,OLD.revision)")
      }
    }
  }
  def workflow(settings:Settings,schema:String,index:Int):Workflow={
    val common=Map[String,JsValue]("host"->JsString(if(index%2==0)settings.host else "localhost"),"port"->JsNumber(settings.port),
      "database"->JsString(schema),"username"->JsString(settings.user),"passwordEnv"->JsString("CDC_WINDOW_MYSQL_PASSWORD"))
    val source=Node("source","source","mysql.snapshot","source",Position(0,0),JsObject(common++Map(
      "table"->JsString(s"source_$index"),"columns"->JsArray(Vector("id","label","amount","note","updated_at","revision").map(JsString(_))),
      "primaryKey"->JsString("id"),"chunkSize"->JsNumber(2),"splitRows"->JsNumber(3),"splitParallelism"->JsNumber(2),
      "maxPageBytes"->JsNumber(4096),"snapshotConsistency"->JsString("quiescent"))))
    val target=Node("target","sink","mysql.write","target",Position(0,0),JsObject(common++Map("host"->JsString(settings.host),
      "table"->JsString(s"target_$index"),"batchSize"->JsNumber(2),"mode"->JsString("insert"))))
    Workflow(f"table-$index%02d",f"table-$index%02d","quiescent multi-table fixture","1","test",Nil,List(source,target),List(Edge("edge","source","target")),WorkflowMetadata("now","now",None,None))
  }
  def comparisonAll(settings:Settings,schema:String):JsArray=withConnection(settings,schema) { c =>
    c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false)
    val result=(0 until TableCount).map { i =>
      val source=query(c,s"SELECT * FROM source_$i ORDER BY id");val target=query(c,s"SELECT * FROM target_$i ORDER BY id")
      def indexed(rows:JsArray)=rows.elements.map(_.asJsObject).map(r=>r.fields("id").convertTo[String]->r).toMap
      val left=indexed(source);val right=indexed(target)
      val differences=(left.keySet intersect right.keySet).toVector.sorted.flatMap { id =>
        left(id).fields.toVector.sortBy(_._1).collect {case(field,v) if right(id).fields(field)!=v =>
          JsObject("id"->JsString(id),"field"->JsString(field),"source"->v,"target"->right(id).fields(field))}
      }
      val missing=(left.keySet--right.keySet).toVector.sorted.map(JsString(_));val extra=(right.keySet--left.keySet).toVector.sorted.map(JsString(_))
      JsObject("table"->JsNumber(i),"source"->source,"target"->target,"sourceCount"->JsNumber(source.elements.size),"targetCount"->JsNumber(target.elements.size),
        "missingKeys"->JsArray(missing),"extraKeys"->JsArray(extra),"fieldDifferences"->JsArray(differences),"equal"->JsBoolean(missing.isEmpty&&extra.isEmpty&&differences.isEmpty))
    }
    c.commit();JsArray(result.toVector)
  }
  def journal(settings:Settings,schema:String):JsArray=withConnection(settings,schema) { c =>
    query(c,"SELECT persistence_id,sequence_number,event_ser_id,event_ser_manifest,TO_BASE64(event_payload) AS payload_base64 FROM event_journal ORDER BY persistence_id,sequence_number")
  }
  def capture(settings:Settings,schema:String,child:Child):JsObject=JsObject(
    "control"->child.state(),"T"->withConnection(settings,schema)(query(_,"SELECT * FROM pekko_sync_batch_ledger ORDER BY workflow_id,partition_id,batch_sequence")),
    "digests"->withConnection(settings,schema)(query(_,"SELECT * FROM pekko_snapshot_batch_evidence ORDER BY batch_id")),
    "J"->journal(settings,schema),"D"->JsString("N/A: static snapshots, no binlog reader"),"comparison"->comparisonAll(settings,schema),
    "audit"->withConnection(settings,schema)(query(_,"SELECT * FROM table_audit ORDER BY audit_id")))
}
