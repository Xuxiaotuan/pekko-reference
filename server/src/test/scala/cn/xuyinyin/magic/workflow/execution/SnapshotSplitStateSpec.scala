package cn.xuyinyin.magic.workflow.execution

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.checkpoint._
import cn.xuyinyin.magic.workflow.snapshot._

class SnapshotSplitStateSpec extends STSpec {
  private val plan = SnapshotSplitPlanState("p", "s", "fingerprint", Some("20"), 2, 4, 2, 1024)
  private val first = SnapshotSplit("p:0", 0, None, "10")
  private def update(s: SnapshotSplitState, u: SnapshotSplitUpdate) = SnapshotSplitState.advance(Some(s), u).toOption.get
  private def cp(split: String, sequence: Long, cursor: String) = BatchCheckpoint("s", split, sequence,
    BatchId.sha256("e", "s", split, sequence), SourceCursor(SnapshotSplitState.CursorKind, cursor, "10"), 2, 2)

  "durable split progress" should {
    "keep independent checkpoints and reject an unknown or gapped split" in {
      val registered = update(SnapshotSplitState(plan), RegisterSnapshotSplit(first))
      val advanced = update(registered, CommitSnapshotPage(cp(first.id, 0, "2")))
      advanced.splits.head.checkpoint.map(_.cursor.value) shouldBe Some("2")
      SnapshotSplitState.advance(Some(advanced), CommitSnapshotPage(cp(first.id, 2, "5"))).isLeft shouldBe true
      SnapshotSplitState.advance(Some(advanced), CommitSnapshotPage(cp("unknown", 0, "2"))).isLeft shouldBe true
      update(advanced, CommitSnapshotPage(cp(first.id, 0, "2"))) shouldBe advanced
    }
    "persist retries without changing identity and pause after three attempts" in {
      val initial = update(SnapshotSplitState(plan), RegisterSnapshotSplit(first))
      val retried = (1 to 3).foldLeft(initial)((s, n) => update(s, RetrySnapshotSplit(first.id, n)))
      retried.splits.head.attempts shouldBe 3
      SnapshotSplitState.advance(Some(retried), RetrySnapshotSplit(first.id, 4)).isLeft shouldBe true
      update(retried, BlockSnapshotSplit(first.id, "protocol_conflict")).splits.head.blocked shouldBe Some("protocol_conflict")
    }
    "retire only completed windows and preserve enumeration position" in {
      val initial = update(SnapshotSplitState(plan), RegisterSnapshotSplit(first))
      SnapshotSplitState.advance(Some(initial), RetireSnapshotWindow).isLeft shouldBe true
      val retired = update(update(initial, CompleteSnapshotSplit(first.id)), RetireSnapshotWindow)
      retired.splits shouldBe empty
      retired.nextIndex shouldBe 1L
      retired.nextLower shouldBe Some("10")
      SnapshotSplitState.advance(Some(retired), RegisterSnapshotSplit(first)).isLeft shouldBe true
    }
    "reject progress outside a range and mutations of an initialized plan" in {
      val initial = update(SnapshotSplitState(plan), RegisterSnapshotSplit(first))
      SnapshotSplitState.advance(Some(initial), CommitSnapshotPage(cp(first.id, 0, "11"))).isLeft shouldBe true
      SnapshotSplitState.advance(Some(initial), InitializeSnapshotSplits(plan.copy(pageRows = 3))).isLeft shouldBe true
    }
  }
}
