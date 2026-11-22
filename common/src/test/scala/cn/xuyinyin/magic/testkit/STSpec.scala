package cn.xuyinyin.magic.testkit

import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Basic standard ScalaTest testkit.
 *
 * @author : Xuxiaotuan
 * @since : 2024-03-30 10:08
*/
trait STSpec extends AnyWordSpec
  with Matchers
  with BeforeAndAfterEach
  with BeforeAndAfterAll
