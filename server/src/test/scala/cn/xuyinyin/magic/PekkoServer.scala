package cn.xuyinyin.magic

import cn.xuyinyin.magic.infrastructure.util.PekkoBanner
import cn.xuyinyin.magic.infrastructure.config.{ConfigValidator, PekkoConfig}
import cn.xuyinyin.magic.infrastructure.config.PekkoConfig.pekkoSysName
import cn.xuyinyin.magic.bootstrap.PekkoClusterService
import com.typesafe.scalalogging.Logger

/**
 * Legacy Workflow compatibility launcher.
 * 
 * 简洁的启动入口，负责：
 * 1. 显示启动横幅
 * 2. 启动集群服务
 * 
 * @author : Xuxiaotuan
 * @since : 2024-09-21 22:18
 */
@deprecated("Use cn.xuyinyin.magic.cdc.runtime.CdcRuntimeMain for the production runtime", "0.2")
object PekkoServer extends App {
  private val logger = Logger(getClass)
  
  // 解析命令行参数获取端口
  val port = args.headOption.map(_.toInt)
  logger.info(s"Starting PekkoServer${port.fold("")(value => s" with remoting port override $value")}")
  
  // 显示启动横幅
  logger.info(PekkoBanner.pekkoServer)

  require(!(PekkoConfig.root.hasPath("pekko.cdc-standalone.managed-worker") &&
    PekkoConfig.root.getBoolean("pekko.cdc-standalone.managed-worker")),
    "managed CDC worker must use CdcStandalone")

  ConfigValidator.validateOrThrow(PekkoConfig.root)

  // 启动集群服务（包含所有复杂逻辑）
  PekkoClusterService.start(pekkoSysName, PekkoConfig.root, port)
}
