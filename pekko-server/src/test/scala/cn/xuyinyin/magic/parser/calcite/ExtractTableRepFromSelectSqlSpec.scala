package cn.xuyinyin.magic.parser.calcite

import cn.xuyinyin.magic.parser.calcite.CalciteMysqlSqlParser.extractTableRepFromSelectSql
import cn.xuyinyin.magic.testkit.STSpec

class ExtractTableRepFromSelectSqlSpec extends STSpec {

  "calcite extractTableRepFromSelectSql" should {

    // 基础场景测试
    "extract table from simple select" in {
      val sql = "SELECT * FROM table1"
      val result = extractTableRepFromSelectSql(sql)
      result shouldBe Right(Seq("table1"))
    }

    "handle join tables" in {
      val sql = "SELECT a.id, b.name FROM table1 a JOIN table2 b ON a.id = b.id"
      val result = extractTableRepFromSelectSql(sql)
      result shouldBe Right(Seq("table1", "table2"))
    }

    "process subquery in FROM clause" in {
      val sql = "SELECT * FROM (SELECT * FROM temp) AS sub"
      val result = extractTableRepFromSelectSql(sql)
      result shouldBe Right(Seq("temp"))
    }

    // UNION 测试
    "handle union operations" in {
      val sql = "SELECT * FROM t1 UNION SELECT * FROM t2"
      val result = extractTableRepFromSelectSql(sql)
      result shouldBe Right(Seq("t1", "t2"))
    }

    // CTE 测试
    "extract tables from CTE" in {
      val sql =
        """
          |WITH cte1 AS (SELECT * FROM temp1),
          |     cte2 AS (SELECT * FROM temp2)
          |SELECT * FROM cte1, cte2
          |""".stripMargin
      val result = extractTableRepFromSelectSql(sql)
      result shouldBe Right(Seq("temp1", "temp2"))
    }

    "extract tables from CTE1" in {
      val sql =
        """
          |WITH
          |  cte1 AS (SELECT * FROM temp1),
          |  cte2 AS (SELECT * FROM temp2)
          |SELECT * FROM cte1
          |JOIN (SELECT * FROM cte2) AS sub
          |WHERE id IN (SELECT id FROM temp3)
          |""".stripMargin

      val result = extractTableRepFromSelectSql(sql)
      result shouldBe Right(Seq("temp1", "temp2", "temp3"))
    }

    "ignore CTE aliases in main query" in {
      val sql =
        """
          |WITH cte AS (SELECT * FROM real_table)
          |SELECT * FROM cte, other_table
          |""".stripMargin

      extractTableRepFromSelectSql(sql) shouldBe
        Right(Seq("real_table", "other_table"))
    }

    "handle nested CTE scopes" in {
      val sql =
        """
          |WITH outer_cte AS (
          |  WITH inner_cte AS (SELECT * FROM inner_table)
          |  SELECT * FROM inner_cte
          |)
          |SELECT * FROM outer_cte
          |""".stripMargin

      extractTableRepFromSelectSql(sql) shouldBe
        Right(Seq("inner_table"))
    }

    // 嵌套子查询测试
    "handle nested subqueries in WHERE" in {
      val sql = "SELECT * FROM main WHERE id IN (SELECT id FROM sub)"
      val result = extractTableRepFromSelectSql(sql)
      result shouldBe Right(Seq("main", "sub"))
    }

    // 复杂 JOIN 测试
    "process complex join structure" in {
      val sql =
        "SELECT * FROM (t1 LEFT JOIN t2 ON t1.id = t2.id) RIGHT JOIN t3 ON t1.id = t3.id"
      val result = extractTableRepFromSelectSql(sql)
      result shouldBe Right(Seq("t1", "t2", "t3"))
    }

    // 表别名测试
    "ignore table aliases" in {
      val sql = "SELECT a.id FROM table1 AS a"
      val result = extractTableRepFromSelectSql(sql)
      result shouldBe Right(Seq("table1"))
    }

    // 异常场景测试
    "reject non-select statements" in {
      val sql = "INSERT INTO table1 VALUES(1)"
      val result: Either[Throwable, Seq[String]] = extractTableRepFromSelectSql(sql)
      assert(result.isLeft)
    }

    // 复杂标识符测试
    "handle quoted identifiers" in {
      val sql = "SELECT * FROM `schema`.`table`"
      val result = extractTableRepFromSelectSql(sql)
      result shouldBe Right(Seq("schema.table"))
    }

    // 多层级 CTE 测试
    "handle nested CTE definitions" in {
      val sql =
        """
          |WITH cte1 AS (
          |  WITH cte2 AS (SELECT * FROM inner_table)
          |  SELECT * FROM cte2
          |)
          |SELECT * FROM cte1
          |""".stripMargin
      val result = extractTableRepFromSelectSql(sql)
      result shouldBe Right(Seq("inner_table"))
    }
  }
}