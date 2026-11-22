package cn.xuyinyin.magic.infrastructure.util

import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.testkit.STSpec

import java.nio.file.{Files,Paths}

class CommonSpec extends STSpec {
  private val library = sys.env.get("RUST_NATIVE_LIB").map(Paths.get(_)).getOrElse {
    val name=if(System.getProperty("os.name","").toLowerCase.contains("mac"))"libhello_rust.dylib"else"libhello_rust.so"
    Paths.get("/Users/xujiawei/magic/rust-workbench/hello_rust/target/release",name)
  }
  // 声明 native 方法
  @native def calculatePrimes(start: Long, end: Long): Long
  @native def sayHello(input: String): String
  "CommonSpec" should {
    "load native library" taggedAs(ExternalIntegration) in {
      if(!Files.isRegularFile(library)) cancel(s"external_blocked: native library not found at $library")
      try System.load(library.toAbsolutePath.toString)
      catch { case e: UnsatisfiedLinkError => cancel(s"external_blocked: native library unavailable: ${e.getMessage}") }
      val result = calculatePrimes(1, 100)
      println(s"Result: $result")
      val hello = sayHello("Scala Test")
      println(s"Hello result: $hello")
      result should be > 0L
    }
  }
}
