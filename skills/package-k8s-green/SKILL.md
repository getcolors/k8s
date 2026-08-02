---
name: package-k8s-green
description: Build and operate a six-node Talos Kubernetes cluster on Hetzner with Green, Cilium WireGuard/ingress, CCM/CSI, Flux, ExternalDNS, cert-manager, and acceptance.
license: MIT
---

# Talos Kubernetes on Hetzner

Read [references/configuration.md](references/configuration.md) before changing
state or running a lifecycle command.

## Safety

- Keep secrets out of `colors.yml`; use gitignored `COLORS_PAR_*` exports.
- Never set `COLORS_PAR_PROFILE` and never edit generated `.colors/` files.
- Default to `build` and `create --dry-run`; real create/delete needs explicit
  authorization.
- Keep `compute-prevent-destroy: true`. Lift it for one intentional delete with
  `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false`.
- Do not copy kubeconfig or talosconfig. The operator commands create private
  temporary files from remote state and erase them.

## Commands

```sh
./green build
./green create --dry-run
./green create
./green kubectl get nodes
./green talosctl health
./green delete
```

A real create requires `hcloud`, `tofu`, `helm`, `kubectl`, `talosctl`, `curl`,
`jq`, `xz`, and OpenSSH. The provided `devenv.nix` supplies them.

Flux watches a public HTTPS repository. Ensure `repository-path` exists and is
a valid Kustomization before provisioning; acceptance requires the Flux source
and Kustomization to become Ready.
