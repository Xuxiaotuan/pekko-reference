# pekko-reference

Personal learning projects

reference and application of pekko functions

## TODO

- [ ] cluster
- [ ] docker
- [ ] Distributed Data

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