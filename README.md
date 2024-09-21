# pekko-reference

Personal learning projects

reference and application of pekko functions

## TODO

- [x] cluster
- [ ] docker
- [ ] Distributed Data

1. pekko-reference run to docker
2. add dispatcher-pump, worker-pump, http-query, http-cmd node
3. pekko custon stream As a workflow implementation

## Dependencies

#### Technology stack

* Scala - 2.13
* Pekko-Actor / Pekko-Cluster / Pekko-Sharding
* Pekko-HTTP
* Pekko-Stream
* Slick

#### Runtime

* JDK 8

## Build
Pekko-reference is built using the `sbt-native packager` plugin

`Binary builds` or `Docker Image builds` are currently supported
### Binary Build

```bash
cd prism
sbt prism-server / Universal / packageBin
```