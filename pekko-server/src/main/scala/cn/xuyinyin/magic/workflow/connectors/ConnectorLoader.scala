package cn.xuyinyin.magic.workflow.connectors

import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
import com.typesafe.scalalogging.Logger

/**
 * 连接器加载器
 * 
 * 在运行时动态加载pekko-connectors中的连接器
 * 使用反射机制避免编译时循环依赖
 * 
 * @author : Xuxiaotuan
 * @since : 2024-03-22
 */
object ConnectorLoader {
  
  private val logger = Logger(getClass)
  
  /**
   * 加载所有可用的连接器
   */
  def loadConnectors(): Unit = {
    logger.info("开始加载外部连接器...")
    
    // 加载MySQL连接器
    loadMySQLConnectors()
    
    // 未来可以加载更多连接器
    // loadPostgreSQLConnectors()
    // loadKafkaConnectors()
    
    logger.info("连接器加载完成")
  }
  
  /**
   * 加载MySQL连接器（使用反射避免编译时依赖）
   */
  private def loadMySQLConnectors(): Unit = {
    try {
      // 尝试通过反射加载MySQLSourceNode
      val sourceClass = Class.forName("cn.xuyinyin.magic.connectors.mysql.MySQLSourceNode")
      val sourceInstance = sourceClass.getDeclaredConstructor().newInstance()
      NodeRegistry.registerSource(sourceInstance.asInstanceOf[cn.xuyinyin.magic.workflow.nodes.base.NodeSource])
      logger.info("✅ MySQL Source连接器已注册（真实JDBC实现）")
      
      // 尝试通过反射加载MySQLSinkNode
      val sinkClass = Class.forName("cn.xuyinyin.magic.connectors.mysql.MySQLSinkNode")
      val sinkInstance = sinkClass.getDeclaredConstructor().newInstance()
      NodeRegistry.registerSink(sinkInstance.asInstanceOf[cn.xuyinyin.magic.workflow.nodes.base.NodeSink])
      logger.info("✅ MySQL Sink连接器已注册（真实JDBC实现）")
      
    } catch {
      case _: ClassNotFoundException =>
        logger.warn("⚠️  pekko-connectors模块未找到，使用内置的模拟MySQL连接器")
      case ex: Exception =>
        logger.error(s"加载MySQL连接器失败: ${ex.getMessage}", ex)
    }
  }
}
