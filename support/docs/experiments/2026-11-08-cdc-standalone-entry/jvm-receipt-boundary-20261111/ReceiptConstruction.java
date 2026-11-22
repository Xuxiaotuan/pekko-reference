public final class ReceiptConstruction {
  public static void main(String[] args) {
    cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor.PersistedCheckpoint receipt =
      new cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor.PersistedCheckpoint("workflow-probe", 1L, "execution", null);
    System.out.println("JAVA_CONSTRUCTION persistenceId=" + receipt.persistenceId() + " sequence=" + receipt.sequenceNr());
  }
}
