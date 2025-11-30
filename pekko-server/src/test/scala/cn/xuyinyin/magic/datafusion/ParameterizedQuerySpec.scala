package cn.xuyinyin.magic.datafusion

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * 参数化查询测试
 */
class ParameterizedQuerySpec extends AnyFlatSpec with Matchers {
  
  "ParameterizedQuery" should "process colon-style named parameters" in {
    val sql = "SELECT * FROM users WHERE name = :name AND age > :age"
    val params = Map("name" -> "Alice", "age" -> 25)
    
    val result = ParameterizedQuery.processQuery(sql, params)
    result.isSuccess shouldBe true
    
    val processedSQL = result.get
    processedSQL should include("'Alice'")
    processedSQL should include("25")
    processedSQL should not include(":name")
    processedSQL should not include(":age")
  }
  
  it should "process brace-style named parameters" in {
    val sql = "SELECT * FROM users WHERE name = {{name}} AND age > {{age}}"
    val params = Map("name" -> "Bob", "age" -> 30)
    
    val result = ParameterizedQuery.processQuery(sql, params)
    result.isSuccess shouldBe true
    
    val processedSQL = result.get
    processedSQL should include("'Bob'")
    processedSQL should include("30")
    processedSQL should not include("{{name}}")
    processedSQL should not include("{{age}}")
  }
  
  it should "process mixed parameter styles" in {
    val sql = "SELECT * FROM users WHERE name = :name AND age > {{age}}"
    val params = Map("name" -> "Charlie", "age" -> 35)
    
    val result = ParameterizedQuery.processQuery(sql, params)
    result.isSuccess shouldBe true
    
    val processedSQL = result.get
    processedSQL should include("'Charlie'")
    processedSQL should include("35")
  }
  
  it should "process positional parameters" in {
    val sql = "SELECT * FROM users WHERE name = ? AND age > ?"
    val params = List("David", 40)
    
    val result = ParameterizedQuery.processPositionalQuery(sql, params)
    result.isSuccess shouldBe true
    
    val processedSQL = result.get
    processedSQL should include("'David'")
    processedSQL should include("40")
    processedSQL should not include("?")
  }
  
  it should "handle different data types" in {
    val sql = "SELECT * FROM table WHERE str = :str AND int = :int AND bool = :bool AND null_val = :null"
    val params = Map(
      "str" -> "test",
      "int" -> 42,
      "bool" -> true,
      "null" -> null
    )
    
    val result = ParameterizedQuery.processQuery(sql, params)
    result.isSuccess shouldBe true
    
    val processedSQL = result.get
    processedSQL should include("'test'")
    processedSQL should include("42")
    processedSQL should include("TRUE")
    processedSQL should include("NULL")
  }
  
  it should "escape single quotes in strings" in {
    val sql = "SELECT * FROM users WHERE name = :name"
    val params = Map("name" -> "O'Connor")
    
    val result = ParameterizedQuery.processQuery(sql, params)
    result.isSuccess shouldBe true
    
    val processedSQL = result.get
    processedSQL should include("'O''Connor'")
  }
  
  it should "fail on missing parameters" in {
    val sql = "SELECT * FROM users WHERE name = :name AND age > :age"
    val params = Map("name" -> "Alice") // missing age
    
    val result = ParameterizedQuery.processQuery(sql, params)
    result.isFailure shouldBe true
    result.failed.get.getMessage should include("Missing parameter: age")
  }
  
  it should "fail on parameter count mismatch for positional" in {
    val sql = "SELECT * FROM users WHERE name = ? AND age > ?"
    val params = List("Alice") // missing second parameter
    
    val result = ParameterizedQuery.processPositionalQuery(sql, params)
    result.isFailure shouldBe true
    result.failed.get.getMessage should include("Parameter count mismatch")
  }
  
  it should "detect SQL injection attempts with DROP" in {
    val sql = "SELECT * FROM users WHERE name = :name"
    val params = Map("name" -> "'; DROP TABLE users; --")
    
    val result = ParameterizedQuery.processQuery(sql, params)
    result.isFailure shouldBe true
    result.failed.get shouldBe a[SecurityException]
  }
  
  it should "detect SQL injection attempts with OR" in {
    val sql = "SELECT * FROM users WHERE name = :name"
    val params = Map("name" -> "' OR '1'='1")
    
    val result = ParameterizedQuery.processQuery(sql, params)
    result.isFailure shouldBe true
    result.failed.get shouldBe a[SecurityException]
  }
  
  it should "extract parameter names from colon style" in {
    val sql = "SELECT * FROM users WHERE name = :name AND age > :age"
    
    val paramNames = ParameterizedQuery.extractParameterNames(sql)
    paramNames should contain("name")
    paramNames should contain("age")
    paramNames.size shouldBe 2
  }
  
  it should "extract parameter names from brace style" in {
    val sql = "SELECT * FROM users WHERE name = {{name}} AND age > {{age}}"
    
    val paramNames = ParameterizedQuery.extractParameterNames(sql)
    paramNames should contain("name")
    paramNames should contain("age")
    paramNames.size shouldBe 2
  }
  
  it should "extract parameter names from mixed styles" in {
    val sql = "SELECT * FROM users WHERE name = :name AND age > {{age}} AND city = :city"
    
    val paramNames = ParameterizedQuery.extractParameterNames(sql)
    paramNames should contain("name")
    paramNames should contain("age")
    paramNames should contain("city")
    paramNames.size shouldBe 3
  }
  
  it should "detect if SQL has parameters" in {
    ParameterizedQuery.hasParameters("SELECT * FROM users") shouldBe false
    ParameterizedQuery.hasParameters("SELECT * FROM users WHERE name = :name") shouldBe true
    ParameterizedQuery.hasParameters("SELECT * FROM users WHERE name = {{name}}") shouldBe true
    ParameterizedQuery.hasParameters("SELECT * FROM users WHERE name = ?") shouldBe true
  }
  
  it should "validate parameters" in {
    val sql = "SELECT * FROM users WHERE name = :name AND age > :age"
    val validParams = Map("name" -> "Alice", "age" -> 25)
    val invalidParams = Map("name" -> "Alice") // missing age
    
    ParameterizedQuery.validateParameters(sql, validParams).isSuccess shouldBe true
    ParameterizedQuery.validateParameters(sql, invalidParams).isFailure shouldBe true
  }
  
  "QueryBuilder" should "build parameterized queries" in {
    val builder = QueryBuilder()
      .setSql("SELECT * FROM users WHERE name = :name AND age > :age")
      .addParameter("name", "Alice")
      .addParameter("age", 25)
    
    val result = builder.build()
    result.isSuccess shouldBe true
    
    val sql = result.get
    sql should include("'Alice'")
    sql should include("25")
  }
  
  it should "add multiple parameters at once" in {
    val params = Map("name" -> "Bob", "age" -> 30)
    val builder = QueryBuilder("SELECT * FROM users WHERE name = :name AND age > :age")
      .addParameters(params)
    
    val result = builder.build()
    result.isSuccess shouldBe true
  }
  
  it should "fail when SQL not set" in {
    val builder = QueryBuilder().addParameter("name", "Alice")
    
    val result = builder.build()
    result.isFailure shouldBe true
    result.failed.get.getMessage should include("SQL not set")
  }
  
  it should "clear parameters" in {
    val builder = QueryBuilder()
      .addParameter("name", "Alice")
      .addParameter("age", 25)
    
    builder.getParameters.size shouldBe 2
    
    builder.clearParameters()
    builder.getParameters shouldBe empty
  }
  
  it should "support BigDecimal parameters" in {
    val sql = "SELECT * FROM products WHERE price = :price"
    val params = Map("price" -> BigDecimal("19.99"))
    
    val result = ParameterizedQuery.processQuery(sql, params)
    result.isSuccess shouldBe true
    
    val processedSQL = result.get
    processedSQL should include("19.99")
  }
}
