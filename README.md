# k8s

A compact kubeadm Kubernetes cluster on DigitalOcean, as a tri-colour Package
Skill (green, red, blue): one control plane, one worker, Flannel networking,
DigitalOcean cloud-controller integration, Flux, DNS/TLS, and end-to-end
acceptance.

The three implementations render byte-identical output: canonical Clojure in
`green/`, TypeScript/Bun in `red/`, and Python/uv in `blue/`, with
`scripts/parity.sh` as the cross-colour net.

```sh
./green build                # render .colors/<profile>/; contacts nothing
./green create --dry-run     # walk the DAG; touches nothing
./green create               # provision kubeadm, Flannel, CCM, and Flux
./green kubectl get nodes    # run kubectl securely over SSH
./green delete               # protected unless explicitly authorized
```

`./red` and `./blue` accept the same verbs.

## Install into a project

```sh
npx skills add getcolors/k8s --skill package-k8s-green
cp .agents/skills/package-k8s-green/green green
chmod +x green
```

The root launcher is a copy. Re-copy it after `npx skills update -p`. The red
and blue skills install the same way with their own payload names.

Desired state is the flat, non-secret `colors.yml` documented in
[`references/configuration.md`](skills/package-k8s-green/references/configuration.md).
Credentials live in ignored `.envrc.private` exports named `COLORS_PAR_*`.
Never set `COLORS_PAR_PROFILE`.

The Kubernetes API and SSH are CIDR-restricted. Workload ingress uses a
DigitalOcean Load Balancer created by the cloud controller. Delete removes that
Kubernetes-managed load balancer before destroying the VPC and Droplets.
`compute-prevent-destroy: true` protects all deployment-owned cloud resources.

The deployment owns its SSH keypair (the workspace SSH Keypair Standard,
keygen mode): with no `digitalocean-ssh-keys` in `colors.yml`, the first real
`create` generates `~/.ssh/<profile>` and `~/.ssh/<profile>.pub`, registers
the public key at DigitalOcean under the profile's name, names it in the
`~/.ssh/config` block that `ssh <profile>` and `./green kubectl` use, and
`delete` removes the key last, after the Droplets are gone. Supplying
`digitalocean-ssh-keys` (an id or fingerprint already registered on the
account; the key this package used to take as
`digitalocean-ssh-key-fingerprint`) opts out: nothing is generated or
uploaded.

## Development

```sh
cd green && bb test
cd green && bb golden
cd red && bun test && bun run typecheck
cd blue && uv run pytest
./scripts/parity.sh            # three colours, both state backends, byte for byte
./scripts/launcher.sh
```

Inspect golden changes before accepting them. Tests never provision resources.
The package depends on the SDK and on ONCE in every colour — the Compute
Cluster Standard's operations are ONCE's `compute-cluster`, called over this
package's own registry and topology; the golden render guards its firewall,
state-key, and no-rendered-secret invariants, and `scripts/parity.sh` is the
net across colours.
