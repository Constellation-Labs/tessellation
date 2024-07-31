# Tessellation

![build](https://img.shields.io/github/actions/workflow/status/Constellation-Labs/tessellation/release.yml?label=build)
![Dynamic JSON Badge](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fl0-lb-testnet.constellationnetwork.io%2Fnode%2Finfo&query=%24.version&label=TestNet)
![Dynamic JSON Badge](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fl0-lb-integrationnet.constellationnetwork.io%2Fnode%2Finfo&query=%24.version&label=IntegrationNet)
![Dynamic JSON Badge](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fl0-lb-mainnet.constellationnetwork.io%2Fnode%2Finfo&query=%24.version&label=MainNet)

The Constellation Network Node Software, written in Scala, ready for Kubernetes Deployment.

## Documentation

Thorough resources are available on the [documentation site](https://docs.constellationnetwork.io).

The [Constellation Network Primer](https://docs.constellationnetwork.io/learn) provides an overview of the Constellation Network.

## Dev Setup / Contributing

* [Dev System Setup](SETUP.md)
* [Contributing Instructions and Guidelines](CONTRIBUTING.md)

## Quick Start

Run node-clusters from source-code, using a local kubernetes.

### Prerequisites

1. [sbt](https://www.scala-sbt.org/)
2. [Docker Desktop](https://www.docker.com/get-started/) with [Kubernetes](https://docs.docker.com/desktop/kubernetes/) enabled
3. [Skaffold CLI](https://skaffold.dev/docs/install/#standalone-binary)

### Starting clusters

```
# within tesselation root
skaffold dev --trigger=manual
```

This will start both L0 and L1 clusters on kubernetes using current kube-context.

Initial validators for L0 and L1 have their public ports mapped to local ports 9000 and 9010 respectively.

```
curl localhost:9000/cluster/info
curl localhost:9010/cluster/info
```

This will return a list of validators on L0 and L1. By default, both L0 and L1 clusters starts with 3 validators 
(1 initial and 2 regular).

### Scaling a cluster

```
kubectl scale deployment/l0-validator-deployment --replicas=9
```

This scales the L0 cluster to 10 validators total: 1 initial and 9 regular.

### Going Deeper

The full [Validator Node Documentation](https://docs.constellationnetwork.io/validate/)


## License

[Apache License 2.0](LICENSE)
