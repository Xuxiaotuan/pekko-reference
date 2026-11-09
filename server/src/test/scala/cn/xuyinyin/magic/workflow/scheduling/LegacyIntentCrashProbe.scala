package cn.xuyinyin.magic.workflow.scheduling

import com.zaxxer.hikari.{HikariConfig,HikariDataSource}

/** Test-only fixed window: exit after account DDL and before recording its observation. */
object LegacyIntentCrashProbe {
  def main(args:Array[String]):Unit={
    require(args.length==2)
    val url=sys.env("CDC_CONTROL_TEST_URL");val user=sys.env("CDC_CONTROL_TEST_USER");val password=sys.env("CDC_CONTROL_TEST_PASSWORD")
    val cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername(user);cfg.setPassword(password);cfg.setMaximumPoolSize(3)
    val ds=new HikariDataSource(cfg)
    val store=new TableSchedulingStore(ds,SchedulingLimits(2,1,8,1,1048576,8),ownershipEnabled=true)
    val metadata=new SnapshotSupervisorStore(store,GuardianBinding("d","h","g","key"),1)
    val control=metadata.claimController("crash-controller")
    val plan=LegacyAccountLockIntent.Plan("op-test",args(0),LegacyMySqlAccountLock.Account(args(1),"127.0.0.1"),Set(user),Set(LegacyMySqlAccountLock.Account("root","localhost")))
    new LegacyAccountLockIntent(metadata).prepare(control,plan)
    LegacyMySqlAccountLock.lock(url,user,password,plan.serverUuid,plan.account,plan.protectedUsers,plan.trustedProxyPrincipals)
    Runtime.getRuntime.halt(37)
  }
}
