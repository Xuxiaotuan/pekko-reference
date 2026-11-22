package cn.xuyinyin.magic.workflow.integration

import java.nio.file.{Files,Path}
import java.io.{InputStream,RandomAccessFile}

/** A supervisor EOF cannot release the source gate or stop this worker. Self-stop is test cleanup only. */
object SupervisorRecoveryWorker {
  def main(args:Array[String]):Unit={
    require(args.length==7,"schema boot directory scenario mode gate pre-bootstrap required")
    val Array(schema,boot,directory,scenario,mode,gate,preBootstrap)=args;val dir=Path.of(directory)
    val command=dir.resolve(boot+".commands");val in=new RandomAccessFile(command.toFile,"r")
    val cleanup=new Thread(()=>{while(true){if(Files.readString(command).contains("STOP_TEST"))Runtime.getRuntime.halt(0);Thread.sleep(50)}},"test-only-worker-cleanup")
    cleanup.setDaemon(true);cleanup.start()
    val end=System.nanoTime()+300000000000L
    def selfHalt():Unit={CdcJournalWindowSupport.emit("TEST_CLEANUP_SELF_HALT");Runtime.getRuntime.halt(0)}
    System.setIn(new InputStream {
      private var pending=Array.emptyByteArray;private var index=0
      override def read(bytes:Array[Byte],offset:Int,length:Int):Int={
        if(length==0)0 else {val value=read();if(value<0)-1 else {bytes(offset)=value.toByte;1}}
      }
      override def read():Int={
        if(index<pending.length){val v=pending(index)&255;index+=1;return v}
        while(System.nanoTime()<end){
          val line=in.readLine()
          if(line!=null){if(line=="STOP_TEST"){selfHalt();return -1};pending=(line+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);index=1;return pending(0)&255}
          Thread.sleep(10)
        };throw new IllegalStateException("test worker lifetime exceeded")
      }
    })
    if(preBootstrap=="true") {
      CdcJournalWindowSupport.emit("BEFORE_BOOTSTRAP")
      while(!Files.exists(dir.resolve(boot+".bootstrap")) && System.nanoTime()<end){
        if(Files.readString(command).contains("STOP_TEST"))selfHalt()
        Thread.sleep(10)
      }
      require(System.nanoTime()<end,"bootstrap test gate expired")
    }
    if(scenario=="PERMIT_REPLY_LOST")SupervisorPermitReplyLossDriver.install(dir,boot)
    try SupervisorRecoveryExecution.main(Array(schema,boot,mode,if(scenario=="PERMIT_REPLY_LOST")"KILL_READ" else scenario,gate,"self"))finally in.close()
  }
}
