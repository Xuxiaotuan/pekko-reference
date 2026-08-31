# bigdata-lab Pekko workflow topology

This Kustomize package creates the two-replica Pekko StatefulSet, its stable
Artery discovery service, an internal HTTP service, a disruption budget,
namespace-scoped Pod discovery RBAC, and the runtime ConfigMap. It targets
Kubernetes 1.23.17 and deliberately uses no NodePort for Pekko.

The pods use Pekko Cluster Bootstrap instead of static seed nodes. The
dedicated `pekko-workflow` ServiceAccount may only get, list, and watch Pods in
`bigdata-lab`. Bootstrap discovers `app=pekko-workflow` and waits for two
stable contact points on the internal named `management` port 7626 before
forming a new cluster. Consequently, a one-pod cold start intentionally does
not form a cluster.

Build and import `pekko-reference:cluster-bootstrap-20260830` into every Kubernetes
node before applying these resources. The image is intentionally configured
with `imagePullPolicy: Never`.

## Existing topology prerequisites

The existing `pekko_workflow` database, account, and
`pekko-workflow-db/password` Secret are prerequisites. The CDC bootstrap does
not change the workflow account identity/password, remove or replace its
existing grants, or rewrite the workflow database or Secret. It does add one
new database-scoped application grant on `pekko_cdc_acceptance.*`. The script
compares the supplied workflow password with the existing Secret through two
mode-0600 temporary files and fails without printing either value when they
differ or the Secret is absent.

## Isolated single-node CDC acceptance runtime

Build `pekko-reference:0.1` from the current Docker configuration (Temurin
Java 17), tag it as `pekko-reference:mysql-cdc-single-20260831`, and import it
on node `xjw`. The dedicated tag prevents the existing two-node runtime from
silently changing image content. The following
checks are local/client dry-runs and do not execute the bootstrap:

```bash
bash -n deploy/k8s/bigdata-lab/bootstrap-mysql.sh
kubectl kustomize deploy/k8s/bigdata-lab/cdc-single > /tmp/pekko-cdc-single-rendered.yaml
kubectl apply --dry-run=client -f /tmp/pekko-cdc-single-rendered.yaml
```

### Live approval gate

Do not run the bootstrap or apply the runtime until this exact live scope is
approved:

- create/reconcile only the `pekko_cdc_acceptance` database and these eight
  tables: `event_journal`, `event_tag`, `snapshot`,
  `pekko_sync_batch_ledger`, `debezium_offset_storage`,
  `debezium_database_history`, `pekko_cdc_source_acceptance`, and
  `pekko_cdc_target_acceptance`;
- add `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `CREATE`, `ALTER`, `INDEX`, and
  `DROP` on `pekko_cdc_acceptance.*` to the existing `pekko_workflow` account;
  its identity/password, pre-existing grants, database, and Secret remain
  unchanged;
- drop and immediately recreate only the dedicated `'pekko_cdc'@'%'` account, then
  grant exactly `SELECT`, `RELOAD`, `SHOW DATABASES`, `REPLICATION SLAVE`, and
  `REPLICATION CLIENT` globally;
- reconcile only the `pekko-cdc-db` Secret; and
- create the four rendered runtime resources: ConfigMap
  `pekko-cdc-single-config`, Services `pekko-cdc-single-headless` and
  `pekko-cdc-single-api`, and the single-replica `pekko-cdc-single` StatefulSet
  pinned to `xjw`.

Dropping and recreating `pekko_cdc` can briefly interrupt acceptance CDC
connections. This account must not be shared with another workload. The script
fails closed unless global `mandatory_roles` is empty, no role is assigned to
the account, and its post-create global privilege set is exactly the five items
above.

The bootstrap is not transactional. Database/account/grant/schema operations
can succeed even if a later schema or Kubernetes Secret operation fails; a
rerun reconciles the same named targets. Passwords containing CR or LF are
rejected before the first Kubernetes call. NUL cannot be represented in a
process environment.

Specifically, failure after `DROP USER` but before `CREATE USER` or `GRANT` can
leave `'pekko_cdc'@'%'` persistently absent or without the required privileges;
failure after the new password takes effect but before `pekko-cdc-db` is applied
can leave the account and Secret persistently inconsistent and unable to
connect. Dropping the account discards its previous password, grants, and
authentication attributes unless they were backed up beforehand. A rerun is
forward reconciliation, not automatic rollback or restoration of that prior
account state.

The worst-case boundary is a partially initialized or stale isolated database,
temporary acceptance-reader interruption during account recreation, a retained
CDC Secret, or retained isolated runtime resources. The existing workflow
database/tables, workflow identity/password/Secret, application tables, and
two-node StatefulSet are not overwrite or deletion targets. The workflow
account's authorization set is intentionally expanded by the new isolated
database grant.

Only after approving that scope, read both values without echo and run:

```bash
read -r -s -p "Workflow DB password: " PEKKO_WORKFLOW_DB_PASSWORD_INPUT; printf '\n'
read -r -s -p "CDC account password: " PEKKO_MYSQL_CDC_PASSWORD_INPUT; printf '\n'
WORKFLOW_DB_PASSWORD="${PEKKO_WORKFLOW_DB_PASSWORD_INPUT}" \
MYSQL_CDC_PASSWORD="${PEKKO_MYSQL_CDC_PASSWORD_INPUT}" \
  ./deploy/k8s/bigdata-lab/bootstrap-mysql.sh
unset PEKKO_WORKFLOW_DB_PASSWORD_INPUT PEKKO_MYSQL_CDC_PASSWORD_INPUT
kubectl apply -k deploy/k8s/bigdata-lab/cdc-single
```

The runtime reuses the existing Pod-reader ServiceAccount and workflow database
Secret, and reads the CDC password only from `pekko-cdc-db/password`. It does
not include or patch the existing `pekko-workflow` StatefulSet.

The two unscoped Debezium tables above are bootstrap compatibility tables. A
workflow connector does not write shared state into them: at runtime it creates
its own `debezium_offset_storage_<hash>` and
`debezium_database_history_<hash>` tables, where `<hash>` is the first 32
lowercase hexadecimal characters of SHA-256(`connectorId`). Connector IDs
therefore cannot overwrite one another's offset or schema history. These
per-connector tables are durable evidence and are not deleted automatically.

Rollback is layered. Deleting the `cdc-single` Kustomize package removes only
its four runtime resources. It does not remove or revert the database, account,
grants, tables, or separately bootstrapped `pekko-cdc-db` Secret. Removing any
of those persistent targets, including revoking the newly added
`pekko_cdc_acceptance.*` grant from `pekko_workflow`, requires a separate
explicit cleanup approval. The workflow account identity/password, its
pre-existing grants, workflow database, and workflow Secret remain unchanged.

### Deterministic CDC acceptance

`run-cdc-e2e.sh` is the live acceptance harness for this isolated runtime. It
fails before writing data unless exactly `pekko-cdc-single-0` is Ready, the
StatefulSet has one replica, the pod is running with
`CDC_OFFSET_FLUSH_INTERVAL_MS=60000`, both database Secrets are non-empty, the
API is ready, and MySQL reports `log_bin=1`, `binlog_format=ROW`, and
`binlog_row_image=FULL`.

Each run reserves four empty IDs in both dedicated acceptance tables. The
first ID is also the run-specific replication server ID in the documented
`540000000..549999999` acceptance range, so retained source rows prevent a
later run from reusing that server ID. The harness creates a unique two-node
workflow, verifies its initial snapshot, applies an insert/update/delete
transaction, locks only that connector's offset row inside a MySQL transaction,
and verifies with `FOR UPDATE NOWAIT` that the lock is active. After the target
and actor ledger advance while the durable offset is blocked, it deletes only
`pekko-cdc-single-0`, releases the lock, and proves that the pre-crash offset
payload was unchanged. It then requires a new pod UID, recovery of the same
running execution, an advanced durable offset, exact source/target equality,
and a final post-restart update/delete.

This command performs real MySQL writes and one recoverable restart of the
dedicated acceptance pod. It never deletes the existing `pekko-workflow-0` or
`pekko-workflow-1` pods, MySQL, a PVC, a Secret, or rows outside its generated
ID range. It reads both passwords from Kubernetes Secrets into mode-0600
temporary files and does not print them. Run it twice; both invocations must
finish with distinct `CDC_E2E_PASS` records:

```bash
./deploy/k8s/bigdata-lab/run-cdc-e2e.sh
./deploy/k8s/bigdata-lab/run-cdc-e2e.sh
```

`NAMESPACE`, `MYSQL_POD`, `API_SERVICE`, and `LOCAL_API_PORT` may override the
resource lookup and local port when required. Run-scoped rows, workflow
journal state, ledger rows, and Debezium state are deliberately retained as
acceptance evidence; removing them is a separate cleanup action. A failed run
may therefore leave partial isolated acceptance data, but a later run selects
a fresh range and cannot pass from those retained rows.

## Existing two-node topology maintenance

The existing topology and Gravitino commands remain separate from the CDC
bootstrap. Use them only within their already-approved scope:

```bash
kubectl create namespace bigdata-lab --dry-run=client -o yaml | kubectl apply -f -
kubectl --namespace bigdata-lab port-forward service/gravitino 18090:8090
# In another terminal:
GRAVITINO_API_BASE=http://127.0.0.1:18090/api ./deploy/k8s/bigdata-lab/bootstrap-gravitino.sh
kubectl apply -k deploy/k8s/bigdata-lab
```

The StatefulSet uses the `OnDelete` update strategy, so applying an updated
template does not restart existing pods. For the approved cold migration from
static seeds to Cluster Bootstrap, separately delete exactly both old Pekko
pods together; the StatefulSet then recreates both pods from the new template
without running mixed static-seed and Bootstrap modes.

The CDC bootstrap drops and recreates only the dedicated `pekko_cdc` account;
the other scripts create or reconcile their named resources. None of these
commands deletes Kafka, Gravitino, application tables, the workflow account,
the workflow Secret, or the existing two-node StatefulSet.

## Acceptance

After the two Pekko pods are ready, run:

```bash
WORKFLOW_DB_PASSWORD='set-in-your-shell' ./deploy/k8s/bigdata-lab/run-e2e.sh
```

It creates the bounded acceptance topic only if absent, produces 50 uniquely
identified events, then verifies the exact ID range, per-run payload marker,
target-row delta, and ledger batch/source/target counts for its newly accepted
execution ID. The chosen target ID range must be empty before production, so
retained rows cannot make a later run pass. It preserves all existing Kafka,
MySQL, and Gravitino resources. The script port-forwards only the Pekko API; the
submitted workflow runs inside the Pekko pod, where `http://gravitino:8090`
resolves through the namespace-local Gravitino Service DNS. A normal run
generates and prints a unique `workflow_id=...`; `WORKFLOW_ID` can override
that value for traceability, but definition creation still requires HTTP 201.

The shown inline assignment applies only to that one command. For the approved
continuation check, append exactly IDs `event-0051` through `event-0062` and
execute the exact workflow created by the first run. Copy the printed ID into
`EXECUTE_EXISTING_WORKFLOW_ID`; this skips the definition POST and calls only
the execution endpoint. The expected result is two ledger batches covering 12
source and 12 target rows, while the target table grows by exactly 12:

```bash
WORKFLOW_DB_PASSWORD='set-in-your-shell' \
  EXECUTE_EXISTING_WORKFLOW_ID='workflow_id-from-first-run' \
  PRODUCE_START=51 PRODUCE_COUNT=12 \
  ./deploy/k8s/bigdata-lab/run-e2e.sh
```

For another rerun, keep using the same workflow ID and choose the next fresh,
non-overlapping `PRODUCE_START`; `PRODUCE_COUNT` is limited to 1 through 50.
If the selected IDs already exist, the script stops before producing anything.
