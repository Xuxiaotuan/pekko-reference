package cn.xuyinyin.magic.common

import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.testkit.STSpec

class CommonSpec extends STSpec {
  private val libraryPath = "/Users/xujiawei/magic/rust-workbench/hello_rust/target/release"

  // 辅助方法：添加库路径
  private def addLibraryPath(path: String): Unit = {
    val usrPaths = classOf[ClassLoader].getDeclaredField("usr_paths")
    usrPaths.setAccessible(true)
    val paths = usrPaths.get(null).asInstanceOf[Array[String]]

    if (!paths.contains(path)) {
      val newPaths = new Array[String](paths.length + 1)
      System.arraycopy(paths, 0, newPaths, 0, paths.length)
      newPaths(paths.length) = path
      usrPaths.set(null, newPaths)
    }
  }
  // 声明 native 方法
  @native def calculatePrimes(start: Long, end: Long): Long
  @native def sayHello(input: String): String
  "CommonSpec" should {
    "load native library" taggedAs(ExternalIntegration) in {
      addLibraryPath(libraryPath)
      System.loadLibrary("hello_rust")
      val result = calculatePrimes(1, 100)
      println(s"Result: $result")
      val hello = sayHello("Scala Test")
      println(s"Hello result: $hello")
      result should be > 0L
    }
  }
}
