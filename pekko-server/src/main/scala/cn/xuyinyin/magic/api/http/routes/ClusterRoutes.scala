package cn.xuyinyin.magic.api.http.routes

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.cluster.typed.Cluster
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import spray.json._
import spray.json.DefaultJsonProtocol._
import com.typesafe.scalalogging.Logger
import cn.xuyinyin.magic.workflow.sharding.WorkflowSharding

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
 * 集群监控HTTP路由
 * 
 * 提供集群状态和分片信息的监控端点：
 * - GET /api/v1/cluster/shards - 查看Shard分布
 * - GET /api/v1/cluster/stats - 查看集群统计
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-28
 */
class ClusterRoutes(implicit system: ActorSystem[_], ec: ExecutionContext) {
  
  private val logger = Logger(getClass)
  private val cluster = Cluster(system)
  private val sharding = ClusterSharding(system)
  
  /**
   * 集群监控路由
   */
  val routes: Route = pathPrefix("api" / "v1" / "cluster") {
    concat(
      // 获取Shard分布信息
      path("shards") {
        get {
          logger.info("GET /api/v1/cluster/shards")
          
          complete {
            getShardInfo().map { shardInfo =>
              StatusCodes.OK -> JsObject(
                "entityType" -> JsString("Workflow"),
                "totalShards" -> JsNumber(shardInfo.totalShards),
                "activeShards" -> JsNumber(shardInfo.activeShards),
                "shards" -> JsArray(
                  shardInfo.shardDetails.map { detail =>
                    JsObject(
                      "shardId" -> JsString(detail.shardId),
                      "location" -> JsString(detail.location),
                      "entityCount" -> JsNumber(detail.entityCount)
                    )
                  }.toVector
                )
              )
            }.recover {
              case ex: Exception =>
                logger.error("Failed to get shard info", ex)
                StatusCodes.InternalServerError -> JsObject(
                  "error" -> JsString("Failed to get shard information"),
                  "message" -> JsString(ex.getMessage)
                )
            }
          }
        }
      },
      
      // 获取集群统计信息
      path("stats") {
        get {
          logger.info("GET /api/v1/cluster/stats")
          
          val clusterState = cluster.state
          val members = clusterState.members
          
          complete {
            getShardInfo().map { shardInfo =>
              StatusCodes.OK -> JsObject(
                "clusterName" -> JsString(cluster.selfMember.address.system),
                "nodeCount" -> JsNumber(members.size),
                "leader" -> clusterState.leader.map(addr => JsString(addr.toString)).getOrElse(JsNull),
                "selfAddress" -> JsString(cluster.selfMember.address.toString),
                "selfRoles" -> JsArray(cluster.selfMember.roles.map(JsString(_)).toVector),
                "members" -> JsArray(
                  members.toSeq.map { member =>
                    JsObject(
                      "address" -> JsString(member.address.toString),
                      "status" -> JsString(member.status.toString),
                      "roles" -> JsArray(member.roles.map(JsString(_)).toVector)
                    )
                  }.toVector
                ),
                "sharding" -> JsObject(
                  "totalShards" -> JsNumber(shardInfo.totalShards),
                  "activeShards" -> JsNumber(shardInfo.activeShards),
                  "totalEntities" -> JsNumber(shardInfo.totalEntities)
                )
              )
            }.recover {
              case ex: Exception =>
                logger.error("Failed to get cluster stats", ex)
                StatusCodes.InternalServerError -> JsObject(
                  "error" -> JsString("Failed to get cluster statistics"),
                  "message" -> JsString(ex.getMessage)
                )
            }
          }
        }
      },
      
      // 获取特定工作流的位置信息
      path("workflow" / Segment / "location") { workflowId =>
        get {
          logger.info(s"GET /api/v1/cluster/workflow/$workflowId/location")
          
          complete {
            getWorkflowLocation(workflowId).map { location =>
              StatusCodes.OK -> JsObject(
                "workflowId" -> JsString(workflowId),
                "shardId" -> JsString(location.shardId),
                "nodeAddress" -> location.nodeAddress.map(JsString(_)).getOrElse(JsNull)
              )
            }.recover {
              case ex: Exception =>
                logger.error(s"Failed to get location for workflow $workflowId", ex)
                StatusCodes.InternalServerError -> JsObject(
                  "error" -> JsString("Failed to get workflow location"),
                  "workflowId" -> JsString(workflowId),
                  "message" -> JsString(ex.getMessage)
                )
            }
          }
        }
      }
    )
  }
  
  /**
   * 获取Shard信息
   */
  private def getShardInfo(): Future[ShardInfo] = {
    Future {
      // 注意：Pekko Cluster Sharding没有直接的API来获取每个Shard的Entity数量
      // 这里我们提供基本的Shard信息
      // 在生产环境中，可能需要通过自定义的统计机制来收集这些信息
      
      val totalShards = 100 // 从配置中获取
      val activeShards = totalShards // 简化实现，假设所有Shard都是活跃的
      
      // 生成Shard详情（简化版本）
      val shardDetails = (0 until totalShards).map { shardId =>
        ShardDetail(
          shardId = shardId.toString,
          location = cluster.selfMember.address.toString, // 简化：假设都在本节点
          entityCount = 0 // 需要自定义统计机制来获取实际数量
        )
      }
      
      ShardInfo(
        totalShards = totalShards,
        activeShards = activeShards,
        totalEntities = 0,
        shardDetails = shardDetails.toList
      )
    }
  }
  
  /**
   * 获取工作流位置信息
   */
  private def getWorkflowLocation(workflowId: String): Future[WorkflowLocation] = {
    Future {
      val shardId = WorkflowSharding.extractShardId(workflowId)
      
      WorkflowLocation(
        shardId = shardId,
        nodeAddress = Some(cluster.selfMember.address.toString) // 简化：返回当前节点
      )
    }
  }
  
  // 数据模型
  case class ShardInfo(
    totalShards: Int,
    activeShards: Int,
    totalEntities: Int,
    shardDetails: List[ShardDetail]
  )
  
  case class ShardDetail(
    shardId: String,
    location: String,
    entityCount: Int
  )
  
  case class WorkflowLocation(
    shardId: String,
    nodeAddress: Option[String]
  )
}
