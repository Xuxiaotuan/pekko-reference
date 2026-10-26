package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.testkit.STSpec
import java.io.UncheckedIOException
import java.nio.file.{NoSuchFileException,Path}

private[integration] object CdcMetadataDiskSize {
  final case class Sample(bytes:Long,retries:Int)
  def measure(root:Path,scan:()=>Long):Sample={
    var retries=0
    while(true) {
      try return Sample(scan(),retries)
      catch {case e:UncheckedIOException if retries<2 && (e.getCause match {
        case lost:NoSuchFileException=>
          val path=Path.of(lost.getFile).toAbsolutePath.normalize()
          path.getParent==root.toAbsolutePath.normalize().resolve("#innodb_redo") && path.getFileName.toString.startsWith("#ib_redo")
        case _=>false
      })=>retries+=1}
    }
    throw new IllegalStateException("unreachable disk scan")
  }
}
class CdcMetadataDiskSizeSpec extends STSpec {
  "metadata disk sampling" should {
    "retry a rotated redo path with a new complete scan" in {
      val root=Path.of("/owned/state/data");var calls=0
      val result=CdcMetadataDiskSize.measure(root,()=>{calls+=1
        if(calls==1)throw new UncheckedIOException(new NoSuchFileException(root.resolve("#innodb_redo/#ib_redo305").toString))
        1024L})
      result.bytes shouldBe 1024L;result.retries shouldBe 1;calls shouldBe 2
    }
    "fail rather than report zero after persistent rotation or an unrelated IO error" in {
      val root=Path.of("/owned/state/data");var calls=0
      intercept[UncheckedIOException](CdcMetadataDiskSize.measure(root,()=>{calls+=1
        throw new UncheckedIOException(new NoSuchFileException(root.resolve("#innodb_redo/#ib_redo306").toString))}))
      calls shouldBe 3
      calls=0
      intercept[UncheckedIOException](CdcMetadataDiskSize.measure(root,()=>{calls+=1
        throw new UncheckedIOException(new NoSuchFileException(root.resolve("lost_table.ibd").toString))}))
      calls shouldBe 1
    }
  }
}
