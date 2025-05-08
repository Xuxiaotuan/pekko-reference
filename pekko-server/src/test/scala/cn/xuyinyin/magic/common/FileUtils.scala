package cn.xuyinyin.magic.common

import scala.io.Source
import scala.util.Using

/**
 * @author : XuJiaWei
 * @since : 2024-06-26 09:58
 */
object FileUtils {

  def readFileContents(filename: String): String = {
    Using
      .Manager { use =>
        val source = use(Source.fromFile(filename))
        source.mkString
      }
      .getOrElse(throw new RuntimeException("Cannot read file"))
  }

}
