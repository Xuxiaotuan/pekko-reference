package cn.xuyinyin.magic.cdc.control


/** Test-only entry into the exact-account lock primitive. */
object LegacyAccountProbeAccess {
  def lock(url:String,user:String,password:String,serverUuid:String,legacyUser:String):Unit=
    LegacyMySqlAccountLock.lock(url,user,password,serverUuid,LegacyMySqlAccountLock.Account(legacyUser,"127.0.0.1"),
      Set(user),Set(LegacyMySqlAccountLock.Account("root","localhost")))
}
