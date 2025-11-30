package cn.xuyinyin.magic.datafusion

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spray.json._

/**
 * Arrow Converter测试
 * 
 * 注意：这些测试针对旧的Arrow格式实现
 * 当前实现使用JSON格式，这些测试已被禁用
 */
class ArrowConverterSpec extends AnyFlatSpec with Matchers {
  
  // 这些测试已被禁用，因为ArrowConverter现在使用JSON格式而不是Arrow格式
  /*
  
  "ArrowConverter" should "infer schema from JSON" in {
    val jsonLines = Seq(
      """{"id": 1, "name": "Alice", "value": 3.14}""",
      """{"id": 2, "name": "Bob", "value": 2.71}"""
    )
    
    val schema = ArrowConverter.inferSchema(jsonLines)
    
    schema.getFields.size shouldBe 3
    schema.findField("id") should not be null
    schema.findField("name") should not be null
    schema.findField("value") should not be null
  }
  
  it should "convert JSON to Arrow" in {
    val jsonLines = Seq(
      """{"id": 1, "name": "Alice"}""",
      """{"id": 2, "name": "Bob"}"""
    )
    
    val root = ArrowConverter.jsonToArrow(jsonLines)
    
    root.getRowCount shouldBe 2
    root.getFieldVectors.size shouldBe 2
    
    root.close()
  }
  
  it should "convert Arrow to JSON" in {
    val jsonLines = Seq(
      """{"id": 1, "name": "Alice"}""",
      """{"id": 2, "name": "Bob"}"""
    )
    
    val root = ArrowConverter.jsonToArrow(jsonLines)
    val result = ArrowConverter.arrowToJson(root)
    
    result.size shouldBe 2
    result.foreach { json =>
      json should include("id")
      json should include("name")
    }
    
    root.close()
  }
  
  it should "handle null values" in {
    val jsonLines = Seq(
      """{"id": 1, "name": "Alice"}""",
      """{"id": 2, "name": null}"""
    )
    
    val root = ArrowConverter.jsonToArrow(jsonLines)
    val result = ArrowConverter.arrowToJson(root)
    
    result.size shouldBe 2
    result(1) should include("null")
    
    root.close()
  }
  
  it should "handle different data types" in {
    val jsonLines = Seq(
      """{"int_val": 42, "long_val": 9223372036854775807, "double_val": 3.14, "str_val": "test", "bool_val": true}"""
    )
    
    val root = ArrowConverter.jsonToArrow(jsonLines)
    
    root.getRowCount shouldBe 1
    root.getFieldVectors.size shouldBe 5
    
    root.close()
  }
  
  it should "throw exception for empty data" in {
    assertThrows[IllegalArgumentException] {
      ArrowConverter.inferSchema(Seq.empty)
    }
    
    assertThrows[IllegalArgumentException] {
      ArrowConverter.jsonToArrow(Seq.empty)
    }
  }
  */
  
  // 新的JSON格式测试
  "ArrowConverter (JSON)" should "infer schema from JSON" in {
    val jsonLines = Seq(
      """{"id": 1, "name": "Alice", "value": 3.14}""",
      """{"id": 2, "name": "Bob", "value": 2.71}"""
    )
    
    val schema = ArrowConverter.inferSchema(jsonLines)
    
    schema.size shouldBe 3
    schema should contain key "id"
    schema should contain key "name"
    schema should contain key "value"
  }
  
  it should "convert JSON lines to Map" in {
    val jsonLines = Seq(
      """{"id": 1, "name": "Alice"}""",
      """{"id": 2, "name": "Bob"}"""
    )
    
    val result = ArrowConverter.jsonLinesToMap(jsonLines)
    
    result.size shouldBe 2
    result.head should contain key "id"
    result.head should contain key "name"
  }
  
  it should "convert Map to JSON lines" in {
    val data = Seq(
      Map("id" -> JsNumber(1), "name" -> JsString("Alice")),
      Map("id" -> JsNumber(2), "name" -> JsString("Bob"))
    )
    
    val result = ArrowConverter.mapToJsonLines(data)
    
    result.size shouldBe 2
    result.foreach { json =>
      json should include("id")
      json should include("name")
    }
  }
  
  it should "get data stats" in {
    val data = Seq(
      Map("id" -> JsNumber(1), "name" -> JsString("Alice")),
      Map("id" -> JsNumber(2), "name" -> JsString("Bob"))
    )
    
    val stats = ArrowConverter.getDataStats(data)
    
    stats.rowCount shouldBe 2
    stats.columnCount shouldBe 2
    stats.columnTypes should contain key "id"
    stats.columnTypes should contain key "name"
  }
}
