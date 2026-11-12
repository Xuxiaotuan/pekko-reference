package cn.xuyinyin.magic.cdc.bootstrap

import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import spray.json._

/** A versioned audit result. It grants no deletion permission until state-store activation. */
private[magic] object BootstrapGcCheckpoint {
  def create(capture:String,lineage:String,subscription:String,generation:String,state:BootstrapProgress,activation:String,
    target:String,database:String):String=
    MySQLCdcGroup.canonical(JsObject("version"->JsNumber(2),"capture"->JsString(capture),"lineage"->JsString(lineage),
      "target"->JsString(target),"database"->JsString(database),
      "subscription"->JsString(subscription),"generation"->JsString(generation),"definition"->JsString(state.definition),
      "execution"->JsString(state.executionId),"deltaAfter"->JsNumber(state.deltaAfter),"completionRoot"->JsString(state.completionRoot),
      "activation"->activation.parseJson))
  def validate(proof:String,journalId:String,state:BootstrapProgress,exact:Boolean=true):Unit={
    val f=proof.parseJson.asJsObject.fields
    require(f.keySet==Set("version","capture","lineage","subscription","generation","definition","execution","deltaAfter","completionRoot","activation","target","database") &&
      f("version")==JsNumber(2),"invalid GC checkpoint format")
    def text(k:String)=f(k).asInstanceOf[JsString].value
    require(text("capture")+"-"+text("subscription")==journalId && text("lineage").nonEmpty && text("generation").nonEmpty &&
      text("definition")==state.definition && state.definition.nonEmpty && text("execution")==state.executionId && state.executionId.nonEmpty &&
      (if(exact)f("deltaAfter")==JsNumber(state.deltaAfter) else f("deltaAfter").asInstanceOf[JsNumber].value<=state.deltaAfter) &&
      text("target").matches("[A-Za-z_][A-Za-z0-9_]*") && text("database").matches("[A-Za-z_][A-Za-z0-9_]*") &&
      text("completionRoot")==state.completionRoot,"GC checkpoint does not bind current Journal")
    val a=f("activation").asJsObject.fields
    require(a("bootstrap")==f("subscription") && a("definition")==f("definition") && a("baselineRoot")==f("completionRoot"),
      "GC checkpoint activation identity conflict")
  }
}
