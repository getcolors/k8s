---
name: package-k8s-green
description: Build and operate a two-node kubeadm Kubernetes cluster on DigitalOcean with Green, Flannel, DigitalOcean CCM, Flux, ExternalDNS, cert-manager, and acceptance.
license: MIT
---

# kubeadm Kubernetes on DigitalOcean

Read [references/configuration.md](references/configuration.md) before changing
desired state or running a lifecycle command.

## Safety

- Keep secrets out of `colors.yml`; use ignored `COLORS_PAR_*` exports.
- Never set `COLORS_PAR_PROFILE` and never edit generated `.colors/` files.
- Default to `build` and `create --dry-run`; real create/delete needs explicit
  authorization.
- Keep `compute-prevent-destroy: true`. Lift it for one authorized delete with
  `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false`.
- Restrict `digitalocean-ssh-sources` and `digitalocean-api-sources`; do not use
  `0.0.0.0/0` for administrative access.
- Do not copy `/etc/kubernetes/admin.conf`. `./green kubectl` uses it over SSH.

## Commands

```sh
./green build
./green create --dry-run
./green create
./green kubectl get nodes
./green kubectl get pods -A
./green delete
```

A real lifecycle run requires Babashka, OpenTofu, Ansible, and SSH. The provided
`devenv.nix` supplies them. Flux watches a public HTTPS repository and path.
Ensure that path contains the controller/config/application reconciliation
objects before provisioning.

Delete first asks Kubernetes to remove the ingress LoadBalancer and waits for
its service finalizer, then destroys only the deployment-owned Droplets,
firewalls, and VPC.
