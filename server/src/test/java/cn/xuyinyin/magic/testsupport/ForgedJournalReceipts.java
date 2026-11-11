package cn.xuyinyin.magic.testsupport;

import cn.xuyinyin.magic.cdc.bootstrap.BootstrapJournal;
import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor;

/** Deliberately bypass Scala's qualified-private constructor checks from Java. */
public final class ForgedJournalReceipts {
    private ForgedJournalReceipts() {}

    public static cn.xuyinyin.magic.cdc.persistence.JournalCheckpointReceipt checkpoint(
            cn.xuyinyin.magic.cdc.persistence.JournalCheckpointReceipt original) {
        return new cn.xuyinyin.magic.cdc.persistence.JournalCheckpointReceipt(
                original.persistenceId(), original.sequenceNr(), original.executionId(), original.checkpoint());
    }

    public static EventSourcedWorkflowActor.PersistedCheckpoint workflow(
            EventSourcedWorkflowActor.PersistedCheckpoint original) {
        return new EventSourcedWorkflowActor.PersistedCheckpoint(
                original.persistenceId(), original.sequenceNr(), original.executionId(), original.checkpoint());
    }

    public static BootstrapJournal.PersistedProgress bootstrap(BootstrapJournal.PersistedProgress original) {
        return new BootstrapJournal.PersistedProgress(original.persistenceId(), original.sequenceNr(), original.state());
    }

    public static BootstrapJournal.PersistedRecoveryReceipt recovery(BootstrapJournal.PersistedRecoveryReceipt original) {
        return new BootstrapJournal.PersistedRecoveryReceipt(original.persistenceId(), original.sequenceNr(), original.revision(),
                original.executionId(), original.permitId(), original.receiptDigest(), original.phase());
    }
}
