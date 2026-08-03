# k8s

A green-only Package Skill for a compact kubeadm Kubernetes cluster on
DigitalOcean: one control plane, one worker, Flannel networking, DigitalOcean
cloud-controller integration, Flux, DNS/TLS, and end-to-end acceptance.

```sh
./green build
./green create --dry-run
./green create
./green kubectl get nodes
./green delete
```

## Install

```sh
npx skills add getcolors/k8s
cp .agents/skills/package-k8s-green/green green
chmod +x green
```

The root launcher is a copy in deployments; re-copy it after each skill update.
Desired state is the flat, non-secret `colors.yml` documented in
[`references/configuration.md`](skills/package-k8s-green/references/configuration.md).
Credentials live in ignored `.envrc.private` exports named `COLORS_PAR_*`.
Never set `COLORS_PAR_PROFILE`.

The Kubernetes API and SSH are CIDR-restricted. Workload ingress uses a
DigitalOcean Load Balancer created by the cloud controller. Delete removes that
Kubernetes-managed load balancer before destroying the VPC and Droplets.
`compute-prevent-destroy: true` protects all deployment-owned cloud resources.

## Development

```sh
bb test
bb golden
./scripts/launcher.sh
```

Inspect golden changes before accepting them. Tests never provision resources.
