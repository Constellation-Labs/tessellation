# tesselation

![build](https://img.shields.io/github/workflow/status/Constellation-Labs/tessellation/Create%20Release?label=build)
![version](https://img.shields.io/github/v/release/Constellation-Labs/tessellation?sort=semver)

### Running dockerized node cluster on kind (k8s) cluster

#### start kind cluster

```bash
cat <<EOF | kind create cluster --config=-
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
- role: control-plane
  kubeadmConfigPatches:
  - |
    kind: InitConfiguration
    nodeRegistration:
      kubeletExtraArgs:
        node-labels: "ingress-ready=true"
  extraPortMappings:
  - containerPort: 80
    hostPort: 80
    protocol: TCP
  - containerPort: 443
    hostPort: 443
    protocol: TCP
EOF
```

#### run garden deploy

```bash
garden deploy
```
