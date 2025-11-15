package cn.xuyinyin.magic.stream.engine

import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, FileIO, Flow}
import org.apache.pekko.util.ByteString
import com.typesafe.scalalogging.Logger

import java.nio.file.Paths
import scala.concurrent.Future

/**
 * 数据汇 - 支持多种数据输出目标
 * 
 * 类似Spark的DataFrameWriter
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
object DataSink {
  
  private val logger = Logger(getClass)
  
  /**
   * 文件数据汇
   */
  object File {
    
    /**
     * 写入文本文件
     */
    def text(path: String)(implicit system: ActorSystem[_]): Sink[String, Future[Done]] = {
      logger.info(s"Writing to text file: $path")
      
      Flow[String]
        .map(line => ByteString(line + "\n"))
        .toMat(FileIO.toPath(Paths.get(path)))(
          (_, ioResult) => ioResult.map(_ => Done)(system.executionContext)
        )
    }
    
    /**
     * 写入CSV文件
     */
    def csv(
      path: String,
      delimiter: String = ","
    )(implicit system: ActorSystem[_]): Sink[Seq[String], Future[Done]] = {
      logger.info(s"Writing to CSV file: $path")
      
      Flow[Seq[String]]
        .map(fields => ByteString(fields.mkString(delimiter) + "\n"))
        .toMat(FileIO.toPath(Paths.get(path)))(
          (_, ioResult) => ioResult.map(_ => Done)(system.executionContext)
        )
    }
    
    /**
     * 追加到文件
     */
    def append(path: String)(implicit system: ActorSystem[_]): Sink[String, Future[Done]] = {
      logger.info(s"Appending to file: $path")
      
      import java.nio.file.StandardOpenOption
      
      Flow[String]
        .map(line => ByteString(line + "\n"))
        .toMat(FileIO.toPath(
          Paths.get(path),
          Set(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        ))(
          (_, ioResult) => ioResult.map(_ => Done)(system.executionContext)
        )
    }
  }
  
  /**
   * 内存数据汇
   */
  object Memory {
    
    /**
     * 收集到Seq
     */
    def collect[T](): Sink[T, Future[Seq[T]]] = {
      Sink.seq[T]
    }
    
    /**
     * 收集到List
     */
    def toList[T](): Sink[T, Future[Seq[T]]] = {
      Sink.collection[T, List[T]]
    }
    
    /**
     * 收集到Set
     */
    def toSet[T](): Sink[T, Future[Set[T]]] = {
      Sink.collection[T, Set[T]]
    }
  }
  
  /**
   * 控制台输出
   */
  object Console {
    
    /**
     * 打印到标准输出
     */
    def println[T](): Sink[T, Future[Done]] = {
      Sink.foreach[T](elem => scala.Predef.println(elem))
    }
    
    /**
     * 打印前N个元素
     */
    def show[T](n: Int): Sink[T, Future[Done]] = {
      Flow[T]
        .take(n)
        .toMat(Sink.foreach(elem => scala.Predef.println(elem)))(
          (_, done) => done
        )
    }
    
    /**
     * 只记录日志
     */
    def log[T](name: String)(implicit system: ActorSystem[_]): Sink[T, Future[Done]] = {
      val logger = Logger(s"DataSink.Console.$name")
      Sink.foreach[T](elem => logger.info(s"$elem"))
    }
  }
  
  /**
   * 聚合数据汇
   */
  object Aggregate {
    
    /**
     * 计数
     */
    def count[T](): Sink[T, Future[Long]] = {
      Sink.fold(0L)((acc, _) => acc + 1)
    }
    
    /**
     * 求和
     */
    def sum[N: Numeric](): Sink[N, Future[N]] = {
      val num = implicitly[Numeric[N]]
      Sink.fold(num.zero)((acc, elem) => num.plus(acc, elem))
    }
    
    /**
     * 求最大值
     */
    def max[T: Ordering](): Sink[T, Future[Option[T]]] = {
      Sink.fold[Option[T], T](None) { (acc, elem) =>
        acc match {
          case None => Some(elem)
          case Some(current) =>
            if (implicitly[Ordering[T]].gt(elem, current)) Some(elem)
            else Some(current)
        }
      }
    }
    
    /**
     * 求最小值
     */
    def min[T: Ordering](): Sink[T, Future[Option[T]]] = {
      Sink.fold[Option[T], T](None) { (acc, elem) =>
        acc match {
          case None => Some(elem)
          case Some(current) =>
            if (implicitly[Ordering[T]].lt(elem, current)) Some(elem)
            else Some(current)
        }
      }
    }
    
    /**
     * 求平均值
     */
    def avg[N: Numeric](): Sink[N, Future[Double]] = {
      val num = implicitly[Numeric[N]]
      Sink.fold[(N, Long), N]((num.zero, 0L)) { case ((sum, count), elem) =>
        (num.plus(sum, elem), count + 1)
      }.mapMaterializedValue { future =>
        import scala.concurrent.ExecutionContext.Implicits.global
        future.map { case (sum, count) =>
          if (count == 0) 0.0
          else num.toDouble(sum) / count
        }
      }
    }
  }
  
  /**
   * 数据库数据汇（示例）
   */
  object Database {
    
    /**
     * 批量插入数据库
     * 
     * TODO: 集成Slick或其他数据库连接池
     */
    def batchInsert(
      table: String,
      batchSize: Int = 100
    )(implicit system: ActorSystem[_]): Sink[Map[String, Any], Future[Done]] = {
      logger.warn(s"Database batch insert not implemented yet: table=$table")
      Sink.ignore.mapMaterializedValue { _ =>
        import scala.concurrent.ExecutionContext.Implicits.global
        Future.successful(Done)
      }
    }
  }
  
  /**
   * Kafka数据汇（示例）
   */
  object Kafka {
    
    /**
     * 发送到Kafka主题
     * 
     * TODO: 集成Alpakka Kafka
     */
    def producer(
      topic: String,
      bootstrapServers: String = "localhost:9092"
    )(implicit system: ActorSystem[_]): Sink[String, Future[Done]] = {
      logger.warn(s"Kafka producer not implemented yet: topic=$topic")
      Sink.ignore.mapMaterializedValue { _ =>
        import scala.concurrent.ExecutionContext.Implicits.global
        Future.successful(Done)
      }
    }
  }
  
  /**
   * 忽略所有输入
   */
  def ignore[T](): Sink[T, Future[Done]] = {
    Sink.ignore
  }
}
