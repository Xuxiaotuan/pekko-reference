package cn.xuyinyin.magic

import cn.xuyinyin.magic.common.PekkoBanner
import cn.xuyinyin.magic.config.{ConfigValidator, PekkoConfig}
import cn.xuyinyin.magic.config.PekkoConfig.pekkoSysName
import cn.xuyinyin.magic.server.PekkoClusterService
import com.typesafe.scalalogging.Logger

/**
 * Pekko服务器主程序
 * 
 * 简洁的启动入口，负责：
 * 1. 显示启动横幅
 * 2. 启动集群服务
 * 
 * @author : Xuxiaotuan
 * @since : 2024-09-21 22:18
 */
object PekkoServer extends App {
  private val logger = Logger(getClass)
  
  // 解析命令行参数获取端口
  val port = args.headOption.map(_.toInt)
  logger.info(s"Starting PekkoServer${port.fold("")(value => s" with remoting port override $value")}")
  
  // 显示启动横幅
  logger.info(PekkoBanner.pekkoServer)

  ConfigValidator.validateOrThrow(PekkoConfig.root)

  // 启动集群服务（包含所有复杂逻辑）
  PekkoClusterService.start(pekkoSysName, PekkoConfig.root, port)
}
