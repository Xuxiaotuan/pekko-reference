package cn.xuyinyin.magic.workflow.cdc

/** New platform connections only. Existing workflow/JDBC configuration is not rewritten. */
private[cdc] object CdcJdbc {
  def withTimeouts(url:String):String={
    try {
      require(url!=null && url.startsWith("jdbc:mysql://") && !url.contains("#"))
      val separator=url.indexOf('?')
      val base=if(separator<0)url else url.take(separator)
      var parameters=if(separator<0 || separator==url.length-1)Vector.empty[String]else url.drop(separator+1).split("&",-1).toVector
      Vector("connectTimeout","socketTimeout").foreach {name=>
        val matches=parameters.zipWithIndex.filter {case(value,_)=>
          java.net.URLDecoder.decode(value.takeWhile(_!='='),java.nio.charset.StandardCharsets.UTF_8).equalsIgnoreCase(name)
        }
        require(matches.size<=1)
        matches.headOption match {
          case None=>parameters=parameters:+(name+"=5000")
          case Some((parameter,index))=>
            val parts=parameter.split("=",-1)
            require(parts.length==2 && parts(0)==name && parts(1).matches("[0-9]+"))
            val value=parts(1).toLong;require(value<=Int.MaxValue)
            if(value==0)parameters=parameters.updated(index,name+"=5000")
        }
      }
      base+"?"+parameters.mkString("&")
    }catch {case scala.util.control.NonFatal(_)=>throw new CdcPlatformException("INVALID_STATE_JDBC")}
  }
}
