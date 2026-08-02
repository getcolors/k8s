# CLAUDE.md

## What this is

`k8s` is a green-only Package Skill for a fixed six-node Talos Kubernetes
cluster on Hetzner Cloud. It owns the Talos snapshot, private network, three
control planes, three workers, API and ingress load balancers, Talos bootstrap,
Cilium, Hetzner CCM/CSI, Flux, ExternalDNS, cert-manager, fixtures and
acceptance. The consumer is `../k8s-hetzner`.

## Commands

```sh
bb test
bb golden
./scripts/launcher.sh
./green build
./green create --dry-run
```

Never run a real create/delete without explicit authorization. Never edit or
read `.colors/`; it is generated output. Never read `.envrc.private`.

## Architecture and safety

Create is `start -> image -> infrastructure -> bootstrap -> acceptance`.
Delete destroys infrastructure before the package-owned snapshot. Stage names
are remote-state keys and must remain package-specific.

Build and dry-run are credential-free. Credentials use only `COLORS_PAR_*` and
must never render. `COLORS_PAR_PROFILE` is always refused. Keep
`compute-prevent-destroy: true`; a real delete is enabled for one invocation by
`COLORS_PAR_COMPUTE_PREVENT_DESTROY=false`.

The private API load balancer is advertised to cluster nodes. Operator access
uses a control-plane public address restricted by `admin-cidr`. `kubectl` and
`talosctl` credentials are loaded from OpenTofu state into 0600 temporary files
and erased in `finally`; they are never written under `.colors`.

The package is self-contained apart from Green. Golden output is the safety net
for firewall, state-key, version-pin and no-rendered-secret invariants. Inspect
every golden diff before accepting it.

## Git

The launcher pin is managed only by `bb pin` after a clean pushed commit. Never
invent or hand-edit `k8s-sha`. Do not commit or push unless explicitly asked.
