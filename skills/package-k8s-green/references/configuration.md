# Configuration

`colors.yml` is a flat non-secret YAML map. The reference deployment is
`k8s-hetzner/colors.yml`; package validation intentionally requires three
control planes, three workers, Hetzner compute and Cloudflare DNS.

## Credentials

| Purpose | Environment variable |
|---|---|
| Hetzner compute, image, CCM and CSI | `COLORS_PAR_HCLOUD_TOKEN` |
| Cloudflare DNS, ExternalDNS and ACME DNS-01 | `COLORS_PAR_CLOUDFLARE_API_TOKEN` |
| R2 backend | `COLORS_PAR_R2_ACCESS_KEY_ID`, `COLORS_PAR_R2_SECRET_ACCESS_KEY` |
| S3 backend | `COLORS_PAR_S3_ACCESS_KEY_ID`, `COLORS_PAR_S3_SECRET_ACCESS_KEY` |

Never export `COLORS_PAR_PROFILE`. Keep `compute-prevent-destroy: true` in YAML.

## Lifecycle and generated output

Create runs package-owned image, infrastructure/Talos bootstrap, platform
bootstrap and acceptance stages. The only Tofu state key is
`<profile>/k8s-infrastructure.tfstate`. It contains sensitive Talos state and
must be protected.

`build` renders:

```text
.colors/<profile>/
├── k8s-image/           schematic.yaml create.sh delete.sh
├── k8s-infrastructure/  backend.tf.json main.tf
├── k8s-bootstrap/       create.sh values files platform.yaml gitops.yaml
└── k8s-acceptance/      acceptance.sh
```

No token, kubeconfig, talosconfig or private key is rendered. Operator commands
initialize the selected backend, load sensitive outputs into 0600 temporary
files, run the requested binary without a shell, and erase the files.

## Networking

The API hostname resolves to a private HA load balancer for cluster nodes.
`hcloud-node-subnet-cidr` must be contained by `hcloud-network-cidr` and provide
at least 32 addresses; API and node addresses are derived from that subnet.
Public control-plane ports 50000 and 6443 accept only `admin-cidr`. Cluster-only
rules cover etcd, kubelet, Cilium health, VXLAN and WireGuard. The public ingress
LB sends 80/443 to Cilium NodePorts 32080/32443 over the private network.

ExternalDNS is restricted to `cloudflare-zone` and `upsert-only`. cert-manager
uses the selected Let's Encrypt environment. Flux requires a public HTTPS
repository, branch and `./`-relative path.
