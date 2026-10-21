# Repository and implementation layout

Baseline: bf249b1823040abc056b65c0c16e462d914c1d73. User authorizes complete layout implementation, verification, commit and push without further approval.

## Contract

- Canonical roots: server, console, scripts, deploy/{local,kubernetes}, docs/{architecture,guides,articles,development,archive,experiments}, project.
- Preserve PekkoServer main FQN, SBT project id pekko-server and aliases, root quick-test.sh and docker-compose.yml entry points. No runtime/recovery algorithm change.
- Preserve common.CborSerializable, actor/supervisor nested models, workflow events/DSL/checkpoints, execution failure/result value objects, scheduler nested messages/events/state and persistence IDs. Keep original packages for these compatibility surfaces; migrate ordinary implementations.
- Bootstrap owns service startup; infrastructure owns cluster/config/monitoring/SQL/utilities; workflow owns execution/SPI/entities/scheduling/query; cdc owns envelope/group protocol; connectors group MySQL/Kafka/file/builtin/JDBC implementations.
- All preexisting docs/experiments bytes and paths remain immutable. Historical source manifests describe their original version. Preserve hidden user/tool state; hide generated directories in the editor rather than delete data.
- Maintain real experiment process/probe main names through test-only adapters if their implementation package moves.

## Tasks and evidence

1. [x] Inventory all tracked files and complete old→new file/class maps. Freeze hashes of experiments and compatibility definitions. Capture old CBOR bytes, add decode regression and run it on baseline. Run intended layout checks RED before moving files.
2. [x] Move server/console, categorize documents, move deploy/k8s to kubernetes; centralize quick tests and retain root command adapters. Keep compose command behavior and volume identity.
3. [x] Rename ordinary implementation packages and update imports, package-private scopes and matching tests. Compile, preserve durable FQNs and old process entry names.
4. [x] Update active path references, relative Markdown links, build/deployment scripts and IDE display exclusions. Add section indexes and an explicit compatibility/navigation guide.
5. [x] Run complete backend test/package/Universal/Docker stage, old quick entry, console test/build/lint, old evidence verifier and real MySQL recovery regression. Independently verify fields/ACK/T/J/D; retain observed failures and limitations.
6. [x] Review full diff; verify old evidence hashes, CBOR fixtures, package/path agreement and commands. Commit explicit task paths, push and verify remote equality after the recorded checks; Git references provide the outcome rather than pre-writing a successful push here.

## Rulings

- Wire/durable objects retain their existing package even when this leaves small compatibility directories. A directory cleanup cannot authorize a journal migration.
- No new production abstraction or compatibility facade for every old implementation type. Old user commands and documented experiment process entry names are the compatibility contract, not arbitrary external imports of ordinary internals.
- Historical experimental source paths remain historical, not rewritten to pretend old captures used today's source.
- Preserve root compose invocation and project identity; validate local compose without starting services. No Kubernetes deployment or image publication.
- Code graph is empty and tools unavailable; use direct package/import and path inventory.
