# Configuration

`colors.yml` is a flat non-secret YAML map. The reference deployment is
`k8s-digitalocean/colors.yml`. Validation reports all errors together and fixes
the supported topology at one kubeadm control plane and one worker.

## Credentials

| Purpose | Environment variable |
|---|---|
| DigitalOcean compute and cloud controller | `COLORS_PAR_DO_TOKEN` |
| Cloudflare ExternalDNS | `COLORS_PAR_CLOUDFLARE_API_TOKEN` |
| R2 backend | `COLORS_PAR_R2_ACCESS_KEY_ID`, `COLORS_PAR_R2_SECRET_ACCESS_KEY` |
| S3 backend | `COLORS_PAR_S3_ACCESS_KEY_ID`, `COLORS_PAR_S3_SECRET_ACCESS_KEY` |

Never export `COLORS_PAR_PROFILE`. Keep `compute-prevent-destroy: true` in YAML.
The DigitalOcean token is streamed into the `digitalocean` Kubernetes Secret;
the Cloudflare token is streamed into `external-dns/cloudflare-api-token`.
Neither is rendered.

## Required desired state

- Providers: `provider-compute: digitalocean`, DNS `cloudflare` or `no-infra`,
  and backend `local`, `s3`, or `r2`.
- Exact versions: `kubernetes-version`, `flannel-version`, `flux-version`, and
  `digitalocean-cloud-controller-version` as `vMAJOR.MINOR.PATCH`.
- Kubernetes: kubeadm, Flannel, pod/service CIDRs, one control plane, one worker.
- GitOps: public HTTPS `repository`, branch, and `./`-relative path.
- DigitalOcean: name, region, both sizes, Ubuntu image, existing SSH-key
  fingerprint, deployment-owned VPC CIDR, and administrative source CIDRs.
- DNS/TLS: application host, Cloudflare zone, ExternalDNS owner ID, and ACME
  environment.

## Lifecycle and generated output

Create provisions the VPC, firewalls and Droplets; records the control-plane SSH
alias; installs containerd and exact kubeadm packages; initializes the cluster;
joins the worker; installs Flannel, DigitalOcean CCM and Flux; then verifies
GitOps, DNS, TLS, and HTTPS.

Remote state is `<profile>/k8s-infrastructure.tfstate`. `build` renders:

```text
.colors/<profile>/
├── k8s-infrastructure/  backend.tf.json main.tf
├── k8s-ansible-local/   ansible.cfg inventory.ini main.yml
├── k8s-ansible-remote/  ansible.cfg inventory.json create.yml delete.yml gitops.yml
└── k8s-acceptance/      acceptance.sh
```

Generated output can contain public/private node addresses but never tokens or
kubeconfig. Do not edit or commit it.

## Networking and recovery

The VPC admits all node-to-node traffic. Public SSH and TCP 6443 admit only the
configured CIDRs. Flannel binds the private `eth1` interface. DigitalOcean CCM
creates the public LoadBalancer requested by ingress-nginx; backend traffic
stays on the VPC.

A repeated `create` converges the existing cluster. For recovery after replacing
nodes, retain the remote OpenTofu state and rerun `create`. If Kubernetes is too
damaged to remove its LoadBalancer, do not destroy the VPC first: restore API
access or remove only the deployment-owned LoadBalancer explicitly, then rerun
the guarded delete.
