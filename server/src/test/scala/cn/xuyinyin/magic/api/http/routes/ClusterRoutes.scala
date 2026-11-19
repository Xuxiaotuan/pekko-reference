package cn.xuyinyin.magic.api.http.routes

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.typed.Cluster
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import spray.json._

import scala.concurrent.ExecutionContext

/** Member data is observed locally; shard placement/counts have no collector yet. */
class ClusterRoutes(implicit system: ActorSystem[_], ec: ExecutionContext) {
  private val cluster = Cluster(system)
  private def unavailable: Map[String, JsValue] = Map(
    "availability" -> JsString("unavailable"),
    "reason" -> JsString("Shard statistics and ownership are not collected")
  )
  private def shardInfo: JsObject = JsObject(unavailable ++ Map(
    "totalShards" -> JsNull, "activeShards" -> JsNull, "totalEntities" -> JsNull
  ))

  val routes: Route = pathPrefix("api" / "v1" / "cluster") {
    concat(
      path("stats") { get {
        val state = cluster.state
        complete(JsObject(
          "clusterName" -> JsString(cluster.selfMember.address.system),
          "nodeCount" -> JsNumber(state.members.size),
          "leader" -> state.leader.map(a => JsString(a.toString)).getOrElse(JsNull),
          "selfAddress" -> JsString(cluster.selfMember.address.toString),
          "selfRoles" -> JsArray(cluster.selfMember.roles.toVector.sorted.map(JsString(_))),
          "members" -> JsArray(state.members.toVector.map(m => JsObject(
            "address" -> JsString(m.address.toString), "status" -> JsString(m.status.toString),
            "roles" -> JsArray(m.roles.toVector.sorted.map(JsString(_)))
          ))),
          "sharding" -> shardInfo
        ))
      } },
      path("shards") { get {
        complete(JsObject(shardInfo.fields ++ Map("entityType" -> JsString("Workflow"), "shards" -> JsNull)))
      } },
      path("workflow" / Segment / "location") { id => get {
        complete(JsObject(unavailable ++ Map(
          "workflowId" -> JsString(id), "shardId" -> JsNull, "nodeAddress" -> JsNull
        )))
      } }
    )
  }
}
