# k8s

A green-only Package Skill for a production-shaped six-node Talos Kubernetes
cluster on Hetzner Cloud.

It owns a pinned Talos snapshot; a private network; three control planes and
three workers; private HA API and public ingress load balancers; restricted
firewalls; Talos bootstrap; Cilium ingress with WireGuard; Hetzner CCM/CSI;
Flux; ExternalDNS; cert-manager; hello-world/PVC health fixtures; and acceptance.

```sh
./green build                 # render .colors/<profile>; contact nothing
./green create --dry-run      # walk the complete DAG; touch nothing
./green create                # provision and converge (explicit authorization)
./green kubectl get nodes     # temporary kubeconfig, erased on exit
./green talosctl health       # temporary talosconfig, erased on exit
./green delete                # protected by compute-prevent-destroy
```

## Install

```sh
npx skills add getcolors/k8s
cp .agents/skills/package-k8s-green/green green
chmod +x green
```

The root launcher is a copy in consumers; re-copy it after every skill update.
Desired state is the flat, non-secret `colors.yml` described in
[the configuration reference](skills/package-k8s-green/references/configuration.md).
Credentials belong in a gitignored `.envrc.private` as `COLORS_PAR_*` exports.
Never set `COLORS_PAR_PROFILE`.

## Security model

The HA Kubernetes endpoint is private. The ephemeral operator kubeconfig uses a
control-plane public address whose Talos and Kubernetes ports admit only
`admin-cidr`. Node ingress permits only private cluster traffic; the public
Hetzner ingress LB forwards 80/443 to fixed Cilium NodePorts. Provider tokens
are child-process environment values and streamed Kubernetes Secrets, never
rendered files. Kubeconfig and talosconfig exist only as 0600 temporary files.

Remote state contains Talos machine secrets and must use a protected backend.
State is keyed `<profile>/k8s-infrastructure.tfstate`.

## Development

```sh
bb test
bb golden
./scripts/launcher.sh
```

Read golden diffs before `bb golden:accept`. Do not provision from tests.
