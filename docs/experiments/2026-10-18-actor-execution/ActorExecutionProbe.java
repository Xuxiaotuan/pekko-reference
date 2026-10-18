import com.typesafe.config.*;
import org.apache.pekko.actor.typed.*;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.sharding.ClusterShardingSettings;
import cn.xuyinyin.magic.workflow.nodes.sources.DebeziumBatchBridge;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Local mechanism probes; no cluster, database, persistence or capacity benchmark. */
public class ActorExecutionProbe {
  static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
  static void await(CountDownLatch latch) throws Exception {
    require(latch.await(30, TimeUnit.SECONDS), "latch timeout");
  }
  static Config profile(String file) {
    return ConfigFactory.parseResources(file).withFallback(ConfigFactory.defaultReference()).resolve();
  }
  static Object strategy(ClusterShardingSettings settings) throws Exception {
    return settings.getClass().getMethod("passivationStrategy").invoke(settings);
  }
  public static void main(String[] args) throws Exception {
    for (String file : List.of("application.conf", "application-dev.conf", "application-prod.conf")) {
      Config cfg = profile(file);
      ClusterShardingSettings s = ClusterShardingSettings.create(cfg.getConfig("pekko.cluster.sharding"));
      Object effective = strategy(s);
      Object control = strategy(s.withRememberEntities(false));
      require(s.rememberEntities() && effective.toString().equals("NoPassivationStrategy"), "unexpected strategy");
      require(control.toString().startsWith("IdlePassivationStrategy"), "unexpected control");
      System.out.printf("CONFIG %s remember=%s configuredIdle=%s effective=%s rememberOff=%s jdbcWorkers=%d recovery=%s%n",
        file, s.rememberEntities(), cfg.getString("pekko.cluster.sharding.passivate-idle-entity-after"),
        effective, control, cfg.getInt("pekko.workflow.jdbc-dispatcher.thread-pool-executor.fixed-pool-size"),
        cfg.getString("pekko.cluster.sharding.entity-recovery-strategy"));
    }
    Config cfg = ConfigFactory.parseString("""
      pekko.actor.provider = local
      pekko.coordinated-shutdown.exit-jvm = off
      pekko.loglevel = WARNING
      probe-dispatcher {
        type = Dispatcher
        executor = "thread-pool-executor"
        thread-pool-executor.fixed-pool-size = 2
        throughput = 5
      }
      """).withFallback(profile("application.conf").withOnlyPath("pekko.workflow.jdbc-dispatcher"))
      .withFallback(ConfigFactory.defaultReference()).resolve();
    ActorSystem<Void> system = ActorSystem.create(Behaviors.empty(), "actor-mechanism-probe", cfg);
    try {
      final int count = 10000, messages = 4;
      CountDownLatch done = new CountDownLatch(count * messages);
      Set<String> threads = ConcurrentHashMap.newKeySet();
      AtomicInteger inFlight = new AtomicInteger(), max = new AtomicInteger();
      AtomicInteger overlaps = new AtomicInteger(), ordering = new AtomicInteger(), handled = new AtomicInteger();
      List<ActorRef<Integer>> refs = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        AtomicInteger local = new AtomicInteger(), expected = new AtomicInteger();
        refs.add(system.systemActorOf(Behaviors.<Integer>receiveMessage(n -> {
          if (local.incrementAndGet() != 1) overlaps.incrementAndGet();
          int active = inFlight.incrementAndGet(); max.accumulateAndGet(active, Math::max);
          threads.add(Thread.currentThread().getName());
          if (expected.getAndIncrement() != n) ordering.incrementAndGet();
          handled.incrementAndGet();
          inFlight.decrementAndGet(); local.decrementAndGet(); done.countDown();
          return Behaviors.same();
        }), "table-" + i, DispatcherSelector.fromConfig("probe-dispatcher")));
      }
      for (int n = 0; n < messages; n++) for (ActorRef<Integer> ref : refs) ref.tell(n);
      await(done);
      int before = handled.get(); Thread.sleep(300);
      require(threads.size() == 2 && max.get() <= 2 && overlaps.get() == 0 && ordering.get() == 0, "actor invariants");
      require(handled.get() == before, "unexpected idle handler");
      System.out.printf("ACTORS residents=%d messages=%d handlerThreads=%d maxConcurrent=%d perActorOverlap=%d orderErrors=%d idle300msExtraHandlers=%d%n",
        refs.size(), handled.get(), threads.size(), max.get(), overlaps.get(), ordering.get(), handled.get()-before);
      System.out.println("ACTOR_THREADS " + new TreeSet<>(threads));

      CountDownLatch occupied = new CountDownLatch(2), release = new CountDownLatch(1), third = new CountDownLatch(1);
      for (int i=0;i<2;i++) system.systemActorOf(Behaviors.<Integer>receiveMessage(n -> {
        occupied.countDown(); release.await(10, TimeUnit.SECONDS); return Behaviors.same();
      }), "blocking-"+i, DispatcherSelector.fromConfig("probe-dispatcher")).tell(1);
      try {
        await(occupied);
        system.systemActorOf(Behaviors.<Integer>receiveMessage(n -> {third.countDown(); return Behaviors.same();}),
          "third", DispatcherSelector.fromConfig("probe-dispatcher")).tell(1);
        boolean progressed = third.await(300, TimeUnit.MILLISECONDS);
        require(!progressed, "blocked dispatcher unexpectedly progressed");
        System.out.println("BLOCKING occupiedWorkers=2 thirdHandlerDuring300ms=false");
      } finally {release.countDown();}
      await(third);
      System.out.println("BLOCKING thirdHandlerAfterRelease=true");

      var jdbc = system.dispatchers().lookup(DispatcherSelector.fromConfig("pekko.workflow.jdbc-dispatcher"));
      int workers = cfg.getInt("pekko.workflow.jdbc-dispatcher.thread-pool-executor.fixed-pool-size");
      List<DebeziumBatchBridge> bridges = new ArrayList<>();
      Set<Thread> readers = ConcurrentHashMap.newKeySet();
      CountDownLatch started = new CountDownLatch(workers), finished = new CountDownLatch(workers), sentinel = new CountDownLatch(1);
      AtomicInteger unexpected = new AtomicInteger();
      for (int i=0;i<workers;i++) {
        DebeziumBatchBridge b = new DebeziumBatchBridge(); bridges.add(b);
        jdbc.execute(() -> {
          readers.add(Thread.currentThread()); started.countDown();
          try { b.take(); unexpected.incrementAndGet(); }
          catch (IllegalStateException expected) { /* close wakes the empty reader */ }
          finally {finished.countDown();}
        });
      }
      try {
        await(started);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long blocked;
        do {
          blocked = readers.stream().filter(t -> Arrays.stream(t.getStackTrace()).anyMatch(f ->
            f.getClassName().equals("java.util.concurrent.ArrayBlockingQueue") && f.getMethodName().equals("take"))).count();
          if (blocked != workers) Thread.sleep(10);
        } while (blocked != workers && System.nanoTime() < until);
        require(blocked == workers, "reader stack gate not reached");
        jdbc.execute(sentinel::countDown);
        boolean progressed = sentinel.await(300, TimeUnit.MILLISECONDS);
        require(!progressed, "shared pool unexpectedly progressed");
        CountDownLatch control = new CountDownLatch(1);
        system.systemActorOf(Behaviors.<Integer>receiveMessage(n -> {control.countDown(); return Behaviors.same();}),
          "control", DispatcherSelector.defaultDispatcher()).tell(1);
        await(control);
        System.out.printf("BRIDGE emptyReaders=%d stackConfirmedQueueTake=%d sharedPoolSentinelDuring300ms=false defaultDispatcherControl=true%n", workers, blocked);
      } finally { for (DebeziumBatchBridge b : bridges) b.close(); }
      await(finished); await(sentinel); require(unexpected.get()==0, "unexpected batch");
      System.out.println("BRIDGE externalCloseReleasedReaders=true sharedPoolSentinelAfterClose=true");
      System.out.println("RESULT all mechanism assertions passed; no CDC E2E or 700000-table capacity claim");
    } finally {
      system.terminate(); system.getWhenTerminated().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
  }
}
