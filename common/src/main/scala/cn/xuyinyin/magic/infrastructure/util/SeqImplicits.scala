package cn.xuyinyin.magic.infrastructure.util

/**
 * @author : Xuxiaotuan
 * @since : 2024-09-21 22:18
 */
object SeqImplicits {

  implicit class EnhanceSeq[A](seq: Seq[A]) {
    def ?(elem: A): Boolean = seq.contains(elem)
  }

  implicit class EnhanceSet[A](seq: Set[A]) {
    def ?(elem: A): Boolean = seq.contains(elem)
  }

}
