import cn.xuyinyin.magic.workflow.bootstrap.BootstrapJournal
import com.typesafe.config.ConfigFactory
import org.apache.pekko.serialization.SerializationExtension
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.{Behaviors,adapter}
import org.apache.pekko.actor.typed.scaladsl.adapter._
import scala.concurrent.Await
import scala.concurrent.duration._
import java.nio.file.{Files,Path}
import spray.json._
import spray.json.DefaultJsonProtocol._

/** Offline decoder. It starts no persistence actors, opens no JDBC connection and edits no source evidence. */
object DecodeBootstrapJournal {
  def main(args:Array[String]):Unit={
    val config=ConfigFactory.parseString("pekko.actor.provider=local\npekko.remote.artery.canonical.port=0\npekko.cluster.seed-nodes=[]\npekko.loglevel=ERROR\npekko.stdout-loglevel=ERROR")
      .withFallback(ConfigFactory.load("application-test"))
    val system=ActorSystem[Nothing](Behaviors.empty,"journal-evidence-decoder",config)
    val serializer=SerializationExtension(system.classicSystem)
    def json(value:Any):JsValue=value match {
      case null | None=>JsNull
      case Some(v)=>json(v)
      case s:String=>JsString(s)
      case b:Boolean=>JsBoolean(b)
      case n:Long=>JsNumber(n)
      case n:Int=>JsNumber(n)
      case other=>throw new IllegalArgumentException("unsupported progress field type: "+other.getClass.getName)
    }
    try {
      val output=Vector.newBuilder[JsValue]
      args.foreach {name=>
        val root=Files.readString(Path.of(name)).parseJson.asJsObject
        val worker=root.fields.getOrElse("worker",root).asJsObject
        worker.fields.get("J").collect {case JsArray(rows)=>rows}.getOrElse(Vector.empty).foreach {value=>
          val row=value.asJsObject.fields
          val event=serializer.deserialize(java.util.HexFormat.of().parseHex(row("payload_hex").convertTo[String]),
            row("event_ser_id").convertTo[String].toInt,row("event_ser_manifest").convertTo[String]).get
          val state=event match {
            case e:BootstrapJournal.Advanced=>JsObject(e.state.productElementNames.zip(e.state.productIterator).map {case(k,v)=>k->json(v)}.toMap)
            case _=>throw new IllegalArgumentException("unexpected persisted event: "+event.getClass.getName)
          }
          output+=JsObject("file"->JsString(name),"persistenceId"->row("persistence_id"),"sequenceNumber"->row("sequence_number"),
            "manifest"->row("event_ser_manifest"),"state"->state)
        }
      }
      println("DECODED "+JsArray(output.result()).compactPrint)
    }finally {system.terminate();Await.result(system.whenTerminated,10.seconds)}
  }
}
