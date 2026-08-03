# CLAUDE.md

## What this is

`k8s` is a green-only Package Skill for a two-node kubeadm Kubernetes cluster
on DigitalOcean: one control plane, one worker, a deployment-owned VPC and
firewalls, Flannel, DigitalOcean CCM, Flux, and a public GitOps repository. The
consumer is `../k8s-digitalocean`.

## Commands

```sh
bb test
bb golden
./scripts/launcher.sh
./green build
./green create --dry-run
```

Never run a real create/delete without explicit authorization. Never edit or
read `.colors/`, and never read `.envrc.private`.

## Architecture and safety

Create is `start -> infrastructure -> ansible-local -> ansible-remote ->
acceptance`. Delete loads node addresses from remote state without changing
infrastructure, removes the Kubernetes-managed DigitalOcean Load Balancer,
then removes local SSH configuration and destroys infrastructure.
Stage names are remote-state keys and remain package-specific.

Build and dry-run are credential-free. Credentials use only `COLORS_PAR_*` and
must never render. `COLORS_PAR_PROFILE` is always refused. Keep
`compute-prevent-destroy: true`; lift it for one authorized delete with
`COLORS_PAR_COMPUTE_PREVENT_DESTROY=false`.

The public Kubernetes API and SSH admit only configured operator CIDRs. Cluster
traffic uses the deployment-owned VPC. `./green kubectl` invokes the root-owned
admin kubeconfig through SSH; no kubeconfig is copied locally. DigitalOcean and
Cloudflare tokens are streamed from process environment into Kubernetes
Secrets with Ansible `no_log` and never enter generated files or GitOps.

The package depends only on Green. Its own multi-node DigitalOcean template is
preferable to coupling to ONCE's single-server templates. Golden output guards
firewall, state-key, pin, and no-rendered-secret invariants.

## Git

The launcher pin is managed only by `bb pin` after a clean pushed commit. Never
invent or hand-edit `k8s-sha`. Do not commit or push unless explicitly asked.
