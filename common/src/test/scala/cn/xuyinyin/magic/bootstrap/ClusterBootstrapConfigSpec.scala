package cn.xuyinyin.magic.bootstrap

import cn.xuyinyin.magic.infrastructure.config.ConfigValidator
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ClusterBootstrapConfigSpec extends AnyWordSpec with Matchers {
  "Cluster Bootstrap configuration" should {
    "accept and select Bootstrap when it is enabled with no static seeds" in {
      val config = productionConfig(
        """
          |pekko.workflow.cluster-bootstrap.enabled = true
          |pekko.cluster.seed-nodes = []
          |""".stripMargin
      )

      ConfigValidator.validate(config) shouldBe None
      PekkoClusterService.shouldStartClusterBootstrap(config) shouldBe true
    }

    "reject Bootstrap mode with static seeds" in {
      val config = productionConfig(
        """
          |pekko.workflow.cluster-bootstrap.enabled = true
          |pekko.cluster.seed-nodes = ["pekko://pekko-cluster-system-prod@node1:2551"]
          |""".stripMargin
      )

      ConfigValidator.validate(config).get should contain(
        "pekko.cluster.seed-nodes must be empty when pekko.workflow.cluster-bootstrap.enabled is true"
      )
    }

    "reject static-seed mode with no static seeds" in {
      val config = productionConfig(
        """
          |pekko.workflow.cluster-bootstrap.enabled = false
          |pekko.cluster.seed-nodes = []
          |""".stripMargin
      )

      ConfigValidator.validate(config).get should contain(
        "pekko.cluster.seed-nodes cannot be empty when pekko.workflow.cluster-bootstrap.enabled is false"
      )
    }

    "keep the default production configuration in static-seed mode" in {
      val config = ConfigFactory.load("application-prod.conf")

      config.getBoolean("pekko.workflow.cluster-bootstrap.enabled") shouldBe false
      config.getStringList("pekko.cluster.seed-nodes") should not be empty
      ConfigValidator.validate(config) shouldBe None
      PekkoClusterService.shouldStartClusterBootstrap(config) shouldBe false
    }
  }

  private def productionConfig(overrides: String): Config =
    ConfigFactory.parseString(overrides).withFallback(ConfigFactory.load("application-prod.conf")).resolve()
  "workflow shard configuration" should {
    "honor an explicit count and reject invalid ownership mappings" in {
      import cn.xuyinyin.magic.infrastructure.cluster.sharding.WorkflowSharding
      val configured=ConfigFactory.parseString("pekko.cluster.sharding.number-of-shards=37")
      WorkflowSharding.configuredNumberOfShards(configured) shouldBe 37
      intercept[IllegalArgumentException] {WorkflowSharding.configuredNumberOfShards(ConfigFactory.parseString("pekko.cluster.sharding.number-of-shards=0"))}
    }
  }

}
