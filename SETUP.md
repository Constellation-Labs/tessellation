# SETUP

**Updated**: June 7, 2022

This guide is to get you setup with the tools you'll need for development.

1. Install _Java 11_, [link](https://openjdk.java.net/projects/jdk/11/).
2. Install _SBT_, [link](https://www.scala-sbt.org/).

# Starting A Local Cluster (Optional)

To spin up a local cluster, we'll need a way to run [Kubernetes](https://kubernetes.io/) containers. One way is with [Docker](https://www.docker.com/). Running a local cluster is needed to run the integration tests.

After installing _Docker_ and enabling _Kubernetes_, register for a _Docker Hub_ account, [link](https://hub.docker.com/).

## Fedora Linux

1. Install [_Docker Engine_](https://docs.docker.com/engine/install/fedora/).
2. Carry out the post install steps, [link](https://docs.docker.com/engine/install/linux-postinstall/).
3. Install [_kubectl_](https://docs.docker.com/desktop/kubernetes/).

Alternatively, simply install [_Docker Desktop_](https://docs.docker.com/desktop/linux/install/fedora/) and enable [_Kubernetes_](https://docs.docker.com/desktop/kubernetes/).

## MacOS

1. Install [_Docker Desktop_](https://docs.docker.com/desktop/mac/install/).
2. Enable _Kubernetes_ via _Docker Desktop_, [link](https://docs.docker.com/desktop/kubernetes/).

# Installing Java 11

Installing _Java 11_ can vary depending on your operating system and chosen methodology. The recommended version to install is _OpenJDK 11_.

## Fedora Linux

Installing via _DNF_ is the recommended method for Fedora Linux. Instructions for that can be found [here](https://docs.fedoraproject.org/en-US/quick-docs/installing-java/).

## Mac OS

Installing via [_Homebrew_](https://brew.sh/) is the recommended method for Mac, though it can be used for other operating systems as well. To install _OpenJDK 11_, go [here](https://formulae.brew.sh/formula/openjdk@11#default).

## SDKMan

[_SDKMan_](https://sdkman.io/) is a cross-platform tool for managing SDK versions. Instructions for using it are [here](https://sdkman.io/usage). As with the other methods, _OpenJDK 11_ is the recommended Java version to install. For instances where that is not an option, other quality _JDK 11_ implementations (e.g., _Coretto Java 11_, _Liberica Java 11_, or _Zulu Java 11_) will suffice.

# Installing SBT

Install the latest version of _SBT_ in order to compile the code base and run unit tests.

After _SBT_ is installed, it needs to be configured. The instructions for that are in _CONTRIBUTING.md_.

## Fedora Linux

On Linux, you can follow the instructions provided by _SBT_, [link](https://www.scala-sbt.org/1.x/docs/Installing-sbt-on-Linux.html).

## Mac OS

On Mac OS, _SBT_ provides instructions [here](https://www.scala-sbt.org/1.x/docs/Installing-sbt-on-Mac.html). It can be installed via [_Homebrew_](https://formulae.brew.sh/formula/sbt#default) as well. It is also recommended that _SBTEnv_ be installed as well, [link](https://formulae.brew.sh/formula/sbtenv#default). It can help configure the _SBT_ environment.
