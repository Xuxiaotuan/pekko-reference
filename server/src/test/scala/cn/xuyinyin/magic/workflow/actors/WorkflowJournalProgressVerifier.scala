package cn.xuyinyin.magic.workflow.actors

import cn.xuyinyin.magic.cdc.persistence.CdcJournalProgressVerifier
import org.apache.pekko.actor.typed.ActorSystem
import java.sql.Connection

/** Legacy test entry delegates to the final CDC verifier. */
private[magic] object WorkflowJournalProgressVerifier {
  def create(system: ActorSystem[_], openState: () => Connection, openJournal: () => Connection): CdcJournalProgressVerifier =
    CdcJournalProgressVerifier.create(system, openState, openJournal)
}
