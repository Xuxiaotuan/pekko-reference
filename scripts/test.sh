#!/bin/sh
# Focused local checks only; real MySQL recovery is a separate opt-in suite.
set -eu
cd "$(dirname "$0")/.."
exec sbt "$@" 'project pekko-server' 'Test / compile' \
  'testOnly cn.xuyinyin.magic.workflow.execution.WorkflowValidatorSpec cn.xuyinyin.magic.connectors.mysql.source.MySQLCdcGroupAssemblerSpec cn.xuyinyin.magic.connectors.mysql.sink.MySQLCdcApplySinkNodeSpec cn.xuyinyin.magic.workflow.actors.WorkflowStopSpec cn.xuyinyin.magic.infrastructure.cluster.ReadinessCheckSpec cn.xuyinyin.magic.bootstrap.HttpServerLifecycleSpec'
