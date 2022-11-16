# SETUP

**Updated**: June 7, 2022

This guide is to get you setup with the tools you'll need for development.

1. Install _Java 11_, [link](https://openjdk.java.net/projects/jdk/11/).
2. Install _SBT_, [link](https://www.scala-sbt.org/).

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

# Running L0 & L1 on EKS cluster

## Prerequisites

1. [sbt](https://www.scala-sbt.org/)
2. [Docker Desktop](https://www.docker.com/get-started/) with [Kubernetes](https://docs.docker.com/desktop/kubernetes/) enabled
3. [Skaffold CLI](https://skaffold.dev/docs/install/#standalone-binary)
4. [AWS CLI version 2](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)

## Kubernetes cluster setup 

### Update your kubeconfig

```
aws eks --region us-west-1 update-kubeconfig --name eks-dev
kubectl config rename-context $(kubectl config current-context) eks-dev
```

### Create your namespace

```
IAM_USER=$(aws sts get-caller-identity --query Arn --output text | sed 's/.*\///g')

kubectl create namespace $IAM_USER
kubectl config set-context --current --namespace=$IAM_USER
```

### Verify kubernetes setup

```
kubectl get pods
```
Should return:

```
No resources found in <your-namespace-name> namespace.
```

## Docker image repository setup

### Install Docker credential helper

```
brew install docker-credential-helper-ecr
```

### Update Docker config

Add this to your `~/.docker/config.json`

```json
{
  "credHelpers": {
    "public.ecr.aws": "ecr-login",
    "150340915792.dkr.ecr.us-west-1.amazonaws.com": "ecr-login"
  }
}
```

### Update Skaffold config

```
skaffold config set default-repo 150340915792.dkr.ecr.us-west-1.amazonaws.com
```

### Verify docker setup

```
docker image ls 150340915792.dkr.ecr.us-west-1.amazonaws.com/l0-validator
```

Should list existing l0-validator images.

## Build images and start cluster

```
skaffold dev --trigger manual --tail false
```

You should see docker images successfully uploaded to the container registry
and then kubernetes resources successfully deployed on the EKS cluster. Open grafana
to monitor the L0 and L1 clusters performance [http://localhost:3000](http://localhost:3000).

To access the API of individual pods you can use an http proxy. First get the IP address
of a pod in a cluster.

```
kubectl get pods -o wide
```

Then set the env variable `http_proxy` and use curl to query a pod.

```
export http_proxy=8080

curl <pod-ip-address>:9000/cluster/info
```

##  Using profiles

### Profile activation

Activate profiles using option `-p`. Profiles can also be manually deactivated by prefixing the profile name with `-`.
```
skaffold dev -p foo,-bar
```

### Profiles

* chaos - inject chaos experiments into the cluster (like a failure of some number of pods) 