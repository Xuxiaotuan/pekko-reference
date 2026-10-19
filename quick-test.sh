#!/bin/sh
# Focused local checks only; real MySQL recovery is a separate opt-in suite.
set -eu
cd "$(dirname "$0")"
exec sbt "$@" 'project pekko-server' 'Test / compile' \
  'testOnly cn.xuyinyin.magic.workflow.engine.WorkflowValidatorSpec cn.xuyinyin.magic.workflow.nodes.sources.MySQLCdcGroupAssemblerSpec cn.xuyinyin.magic.workflow.nodes.sinks.MySQLCdcApplySinkNodeSpec'
