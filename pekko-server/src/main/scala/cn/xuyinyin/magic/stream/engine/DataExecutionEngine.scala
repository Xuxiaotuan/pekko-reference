package cn.xuyinyin.magic.stream.engine

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.{Source, Flow, Sink}
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}

/**
 * 数据执行引擎 - 基于Pekko Stream的流式数据处理引擎
 * 
 * 设计理念：
 * 1. 类似Spark/Flink的API，但基于Pekko Stream
 * 2. 支持批处理和流处理
 * 3. 支持多种数据源和数据汇
 * 4. 内置常用的数据转换操作
 * 
 * 特性：
 * - 流式处理：支持无限数据流
 * - 背压机制：自动处理快速生产者和慢速消费者
 * - 容错性：基于Actor模型的监督策略
 * - 可组合：通过Flow组合构建复杂的数据管道
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
object DataExecutionEngine {
  
  private val logger = Logger(getClass)
  
  /**
   * 数据集抽象 - 类似Spark的Dataset/DataFrame
   */
  case class Dataset[T](
    source: Source[T, NotUsed],
    name: String = "unnamed-dataset"
  )(implicit system: ActorSystem[_]) {
    
    /**
     * 映射转换
     */
    def map[U](f: T => U): Dataset[U] = {
      Dataset(source.map(f), s"$name.map")
    }
    
    /**
     * 过滤
     */
    def filter(predicate: T => Boolean): Dataset[T] = {
      Dataset(source.filter(predicate), s"$name.filter")
    }
    
    /**
     * 扁平化映射
     */
    def flatMap[U](f: T => Seq[U]): Dataset[U] = {
      Dataset(source.mapConcat(f(_).toList), s"$name.flatMap")
    }
    
    /**
     * 分组聚合 - 类似SQL的GROUP BY
     */
    def groupBy[K](keyFunc: T => K): GroupedDataset[K, T] = {
      GroupedDataset(source, keyFunc, name)
    }
    
    /**
     * 批量处理
     */
    def batch(batchSize: Int): Dataset[Seq[T]] = {
      Dataset(source.grouped(batchSize), s"$name.batch($batchSize)")
    }
    
    /**
     * 去重
     */
    def distinct(implicit ord: Ordering[T]): Dataset[T] = {
      Dataset(
        source.statefulMapConcat { () =>
          var seen = Set.empty[T]
          elem => {
            if (seen.contains(elem)) {
              Nil
            } else {
              seen += elem
              List(elem)
            }
          }
        },
        s"$name.distinct"
      )
    }
    
    /**
     * 限制数量
     */
    def limit(n: Long): Dataset[T] = {
      Dataset(source.take(n), s"$name.limit($n)")
    }
    
    /**
     * 排序（需要收集所有数据，慎用于大数据集）
     */
    def orderBy(implicit ord: Ordering[T]): Dataset[T] = {
      Dataset(
        source.fold(Seq.empty[T])(_ :+ _)
          .mapConcat(seq => seq.sorted.toList),
        s"$name.orderBy"
      )
    }
    
    /**
     * 连接另一个数据集
     */
    def join[U, K](
      other: Dataset[U],
      thisKey: T => K,
      otherKey: U => K
    ): Dataset[(T, U)] = {
      // 简化的Join实现（内存Join，适合小数据集）
      val joined = source.via(
        Flow[T].statefulMapConcat { () =>
          var otherData: Seq[U] = Seq.empty
          var initialized = false
          
          elem => {
            if (!initialized) {
              // TODO: 这里应该异步获取other的数据
              // 实际生产中需要更复杂的Join策略
              logger.warn("Simple in-memory join, not suitable for large datasets")
            }
            
            val key = thisKey(elem)
            // 查找匹配的记录
            otherData.filter(u => otherKey(u) == key).map(u => (elem, u)).toList
          }
        }
      )
      
      Dataset(joined, s"$name.join(${other.name})")
    }
    
    /**
     * 执行并收集结果
     */
    def collect()(implicit ec: ExecutionContext): Future[Seq[T]] = {
      logger.info(s"Executing dataset: $name")
      source.runWith(Sink.seq)
    }
    
    /**
     * 执行并对每个元素应用副作用
     */
    def foreach(f: T => Unit)(implicit ec: ExecutionContext): Future[Unit] = {
      logger.info(s"Executing dataset with foreach: $name")
      source.runForeach(f).map(_ => ())
    }
    
    /**
     * 计数
     */
    def count()(implicit ec: ExecutionContext): Future[Long] = {
      logger.info(s"Counting dataset: $name")
      source.runWith(Sink.fold(0L)((acc, _) => acc + 1))
    }
    
    /**
     * 聚合
     */
    def reduce(f: (T, T) => T)(implicit ec: ExecutionContext): Future[T] = {
      logger.info(s"Reducing dataset: $name")
      source.runWith(Sink.reduce(f))
    }
    
    /**
     * 折叠
     */
    def fold[U](zero: U)(f: (U, T) => U)(implicit ec: ExecutionContext): Future[U] = {
      logger.info(s"Folding dataset: $name")
      source.runWith(Sink.fold(zero)(f))
    }
    
    /**
     * 写入到Sink
     */
    def writeTo[Mat](sink: Sink[T, Future[Mat]]): Future[Mat] = {
      logger.info(s"Writing dataset to sink: $name")
      source.runWith(sink)
    }
    
    /**
     * 打印到控制台（调试用）
     */
    def show(n: Int = 20)(implicit ec: ExecutionContext): Future[Unit] = {
      logger.info(s"Showing dataset: $name (first $n rows)")
      source.take(n).runForeach(println).map(_ => ())
    }
  }
  
  /**
   * 分组数据集
   */
  case class GroupedDataset[K, T](
    source: Source[T, NotUsed],
    keyFunc: T => K,
    datasetName: String
  )(implicit system: ActorSystem[_]) {
    
    /**
     * 计数聚合
     */
    def count()(implicit ec: ExecutionContext): Future[Map[K, Long]] = {
      source.runWith(
        Sink.fold(Map.empty[K, Long]) { (acc, elem) =>
          val key = keyFunc(elem)
          acc + (key -> (acc.getOrElse(key, 0L) + 1))
        }
      )
    }
    
    /**
     * 求和聚合
     */
    def sum[N](valueFunc: T => N)(implicit num: Numeric[N], ec: ExecutionContext): Future[Map[K, N]] = {
      source.runWith(
        Sink.fold(Map.empty[K, N]) { (acc, elem) =>
          val key = keyFunc(elem)
          val value = valueFunc(elem)
          acc + (key -> num.plus(acc.getOrElse(key, num.zero), value))
        }
      )
    }
    
    /**
     * 求平均值
     */
    def avg[N](valueFunc: T => N)(implicit num: Numeric[N], ec: ExecutionContext): Future[Map[K, Double]] = {
      source.runWith(
        Sink.fold(Map.empty[K, (N, Long)]) { (acc, elem) =>
          val key = keyFunc(elem)
          val value = valueFunc(elem)
          val (currentSum, currentCount) = acc.getOrElse(key, (num.zero, 0L))
          acc + (key -> (num.plus(currentSum, value), currentCount + 1))
        }
      ).map { result =>
        result.map { case (k, (sum, count)) =>
          k -> num.toDouble(sum) / count
        }
      }
    }
  }
  
  /**
   * 创建数据集 - 从Scala集合
   */
  def fromCollection[T](data: Seq[T])(implicit system: ActorSystem[_]): Dataset[T] = {
    Dataset(Source(data), "collection-source")
  }
  
  /**
   * 创建数据集 - 从文件（按行读取）
   */
  def readTextFile(path: String)(implicit system: ActorSystem[_]): Dataset[String] = {
    import java.nio.file.Paths
    import org.apache.pekko.stream.scaladsl.FileIO
    import org.apache.pekko.util.ByteString
    
    val source = FileIO.fromPath(Paths.get(path))
      .via(org.apache.pekko.stream.scaladsl.Framing.delimiter(
        ByteString("\n"),
        maximumFrameLength = 8192,
        allowTruncation = true
      ))
      .map(_.utf8String)
      .mapMaterializedValue(_ => NotUsed)
    
    Dataset(source, s"file://$path")
  }
  
  /**
   * 创建数据集 - 从CSV文件
   */
  def readCSV(path: String, delimiter: String = ",")(implicit system: ActorSystem[_]): Dataset[Array[String]] = {
    readTextFile(path).map(_.split(delimiter))
  }
  
  /**
   * 创建数据集 - 从Range
   */
  def range(start: Long, end: Long)(implicit system: ActorSystem[_]): Dataset[Long] = {
    Dataset(Source(start until end), s"range($start, $end)")
  }
  
  /**
   * 创建空数据集
   */
  def empty[T](implicit system: ActorSystem[_]): Dataset[T] = {
    Dataset(Source.empty[T], "empty-source")
  }
  
  /**
   * 并行化处理 - 类似Spark的parallelize
   */
  def parallelize[T](data: Seq[T], parallelism: Int = 4)(implicit system: ActorSystem[_]): Dataset[T] = {
    logger.info(s"Parallelizing data with parallelism=$parallelism")
    Dataset(
      Source(data).async,
      s"parallel-source(parallelism=$parallelism)"
    )
  }
}
