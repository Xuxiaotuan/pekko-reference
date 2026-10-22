package cn.xuyinyin.magic.workflow.scheduling

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

class TableSchedulingStoreSpec extends AnyWordSpec with Matchers {
  private def fixture(test: TableSchedulingStore => Unit, limits: SchedulingLimits = SchedulingLimits(2, 1, 4, 4, 1048576, 8)): Unit = {
    val c = new HikariConfig(); c.setJdbcUrl("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1")
    c.setUsername("sa"); c.setMaximumPoolSize(2); c.setMinimumIdle(0)
    val ds = new HikariDataSource(c)
    val connection = ds.getConnection
    try {
      val in = getClass.getClassLoader.getResourceAsStream("db/h2/pekko-table-scheduling-schema.sql")
      val sql = try scala.io.Source.fromInputStream(in).mkString finally in.close()
      sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(s => { val st = connection.createStatement(); try st.execute(s) finally st.close() })
    } finally connection.close()
    try test(new TableSchedulingStore(ds, limits)) finally ds.close()
  }
  private def definition(id: String, source: String = "source", target: String = "target", parallelism: Int = 2) =
    ScheduledTableDefinition(id, 1L, "{}", TableResourceDemand(source, target, parallelism, 1024))
  "durable table admission" should {
    "bound outstanding activations across polling rounds" in fixture({ store =>
      (1 to 3).foreach(i => store.enqueue(definition(s"w$i"),s"e$i",s"r$i"))
      val first=store.claim(1).head
      store.claim(1) shouldBe empty
      store.bind(first,"node-a") shouldBe true
      store.claim(1).size shouldBe 1
    }, SchedulingLimits(2,1,8,1,1048576,8))
    "reject an old request after a newer run has completed" in fixture { store =>
      store.enqueue(definition("a"),"e1","r1")
      store.release(store.claim(1).head,"completed",true)
      store.enqueue(definition("a"),"e2","r2")
      store.release(store.claim(1).head,"completed",true)
      intercept[IllegalStateException](store.enqueue(definition("a"),"e1","r1"))
      store.get("a").get.executionId shouldBe Some("e2")
    }

    "reject changing a completed definition under its original execution request" in fixture { store =>
      val original=definition("revision-request")
      store.enqueue(original,"execution-one","request-one")
      store.release(store.claim(1).head,"completed",true) shouldBe true
      val before=store.get(original.workflowId).get
      val revised=original.copy(revision=2L,workflowJson="{\"revision\":2}")
      intercept[IllegalStateException](store.enqueue(revised,"execution-one","request-one"))
      store.get(original.workflowId).get shouldBe before
      store.enqueue(original,"execution-one","request-one").state shouldBe "completed"
      store.enqueue(revised,"execution-two","request-two").state shouldBe "ready"
    }

    "reserve both connection vectors atomically and retain unknown releases" in fixture { store =>
      (1 to 4).foreach(i => store.enqueue(definition(s"w$i"), s"e$i", s"r$i"))
      val grants = store.claim(8)
      grants.size shouldBe 2
      store.claim(8) shouldBe empty
      store.bind(grants(0), "node-a") shouldBe true
      store.bind(grants(1), "node-a") shouldBe false
      store.bind(grants(1), "node-b") shouldBe true
      store.release(grants(0), "ready", releaseConfirmed = false)
      store.get("w1").get.state shouldBe "unknown"
      store.claim(8) shouldBe empty
      store.release(grants(1), "ready", releaseConfirmed = true)
      store.claim(8).map(_.task.workflowId) shouldBe Vector("w3")
    }
    "count source and sink on the same instance together" in fixture { store =>
      (1 to 3).foreach(i => store.enqueue(definition(s"w$i", "same", "same"), s"e$i", s"r$i"))
      store.claim(8).size shouldBe 1
    }
    "preserve the execution on redelivery and put yielded work behind other tables" in fixture { store =>
      store.enqueue(definition("a"), "ea", "ra")
      store.enqueue(definition("b"), "eb", "rb")
      val grant = store.claim(1).head
      store.enqueue(definition("a"), "ea", "ra").executionId shouldBe Some("ea")
      store.release(grant, "ready", releaseConfirmed = true)
      store.claim(1).head.task.workflowId shouldBe "b"
      store.release(grant, "completed", releaseConfirmed = true) shouldBe false
      store.get("a").get.state shouldBe "ready"
    }
    "keep future work durable and page metadata without activation" in fixture { store =>
      store.enqueue(definition("future"), "ef", "rf", Long.MaxValue)
      store.claim(8) shouldBe empty
      store.listAfter(None, 1).map(_.workflowId) shouldBe Vector("future")
      store.get("future").get.state shouldBe "waiting"
    }
    "reject inconsistent definitions and an overlapping execution" in fixture { store =>
      store.enqueue(definition("a"), "ea", "ra")
      intercept[IllegalStateException](store.enqueue(definition("a"), "other", "other"))
      intercept[IllegalStateException](store.register(definition("a").copy(workflowJson = "changed")))
      store.get("a").get.executionId shouldBe Some("ea")
    }
  }
}
