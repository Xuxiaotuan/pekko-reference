# 受管启动与重启：首次失败证据

首次实验在专用 MySQL 上从独立 CDC 入口启动受管 worker，重启 supervisor 后记录 guardian 隔离证明；source 排空，state 停留 drain_wait，未发放接管许可。详见 observation JSON。

state 的连接属性采集参数为 0，guardian 自身的 cdc_lab 空闲连接无法按属性识别；通过本机 lsof 将其连接归属到 guardian JVM。不能据此推断另外一条连接的归属，也不能当作自动接管成功。第一次 supervisor 日志还出现 SQLSyntaxErrorException，现有日志不足以定位。

本实验没有提交同步任务、没有执行目标逐字段对账。原始进程日志保存在本机隔离目录 /private/tmp/pcg19；不包含凭据的核心排空记录保存在本目录。
