#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
state="$root/test/fixtures/colors.yml"
goldens="$root/test/resources/golden"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
accept=0
[ "${1:-}" = --accept ] && accept=1

build() {
  local variant=$1
  shift
  (cd "$root" && env K8S_LIB_ROOT="$root" COLORS_PAR_WORKDIR="$tmp/$variant" "$@" \
    ./green build -f "$state" >/dev/null)
  if [ "$accept" = 1 ]; then
    rm -rf "$goldens/$variant"
    mkdir -p "$goldens/$variant"
    cp -r "$tmp/$variant/." "$goldens/$variant/"
    echo "  accepted — $variant"
  else
    diff -qr "$goldens/$variant" "$tmp/$variant"
    echo "  ok — $variant"
  fi
}

build local COLORS_PAR_PROVIDER_BACKEND=local
build r2

base="$tmp/local/k8s-fixture"
for stage in k8s-image k8s-infrastructure k8s-bootstrap k8s-acceptance; do
  [ -d "$base/$stage" ] || { echo "golden: missing stage $stage" >&2; exit 1; }
done
infra="$base/k8s-infrastructure/main.tf"
grep -q 'resource "hcloud_server" "control_plane"' "$infra"
grep -q 'resource "hcloud_server" "worker"' "$infra"
[ "$(grep -c 'ignore_changes = \[location\]' "$infra")" -eq 2 ] || {
  echo 'golden: both server roles must tolerate hcloud location state normalization' >&2; exit 1
}
grep -q 'cidrhost(local.private_cidr, 5)' "$infra"
grep -q 'cidrhost(local.private_cidr, 10 + i)' "$infra"
grep -q 'cidrhost(local.private_cidr, 20 + i)' "$infra"
grep -q '\${local.private_prefix_length}' "$infra"
if grep -Eq 'api_private_ip[[:space:]]+=[[:space:]]+"|private_ips[[:space:]]+=[^]]*"10\.' "$infra"; then
  echo 'golden: private node addresses are hard-coded instead of derived from the configured subnet' >&2; exit 1
fi
grep -q 'enable_public_interface = false' "$infra"
grep -q 'port       = "51871"' "$infra"
grep -q 'port       = "8472"' "$infra"
grep -q 'port       = "2379-2380"' "$infra"
grep -q '203.0.113.10/32' "$infra"
if grep -q '0.0.0.0/0' "$infra"; then
  echo 'golden: a node firewall is open to the world' >&2; exit 1
fi
grep -q 'sensitive = true' "$infra"
grep -q 'advertisedSubnets = \[local.private_cidr\]' "$infra"
grep -q 'output "worker_ipv4"' "$infra"
grep -q 'k8s-fixture/k8s-infrastructure.tfstate' \
  "$tmp/r2/k8s-fixture/k8s-infrastructure/backend.tf.json"
image="$base/k8s-image/create.sh"
grep -q 'curl -fsSL -o nocloud-amd64.raw.xz' "$image"
grep -q 'xz -t nocloud-amd64.raw.xz' "$image"
if grep -q '\.sha256' "$image"; then
  echo 'golden: image stage uses the enterprise-only Factory checksum endpoint' >&2; exit 1
fi
if awk '/hcloud server create-image/{capture=1} capture{print} capture && /"\$builder"/{exit}' "$image" \
    | grep -q -- '-o json'; then
  echo 'golden: create-image uses an output flag unsupported by hcloud' >&2; exit 1
fi
grep -q -- '--label colors-package=k8s' "$image"
grep -q -- '--label "talos-schematic-a=${schematic:0:32}"' "$image"
grep -q -- '--label "talos-schematic-b=${schematic:32:32}"' "$image"
if grep -q -- '--label "talos-schematic=$schematic"' "$image"; then
  echo 'golden: the 64-character schematic exceeds Hetzner label limits' >&2; exit 1
fi
grep -q 'talos_schematic.*talos-schematic-a.*talos-schematic-b' "$infra"
grep -q 'talos_version_label=${talos_version//./-}' "$image"
grep -q 'talos_version_label.*replace("v1.13.7", ".", "-")' "$infra"
grep -q 'talos-version=${local.talos_version_label}' "$infra"
bootstrap="$base/k8s-bootstrap/create.sh"
for pin in 1.20.0 1.34.0 2.22.1 1.21.1 v1.21.1 v2.9.3; do
  grep -q "$pin" "$bootstrap" || { echo "golden: missing component pin $pin" >&2; exit 1; }
done
grep -q 'env.HCLOUD_TOKEN' "$bootstrap"
grep -q 'env.CLOUDFLARE_API_TOKEN' "$bootstrap"
grep -q 'kubectl get --raw=/readyz' "$bootstrap"
grep -q 'type: wireguard' "$base/k8s-bootstrap/cilium-values.yaml"
grep -q 'kind: ClusterIssuer' "$base/k8s-bootstrap/platform.yaml"
grep -q 'kind: PersistentVolumeClaim' "$base/k8s-bootstrap/platform.yaml"
grep -q 'path: "./gitops"' "$base/k8s-bootstrap/gitops.yaml"
acceptance="$base/k8s-acceptance/acceptance.sh"
grep -q 'exactly six Kubernetes nodes' "$acceptance"
grep -q -- '--control-plane-nodes "$CONTROL_PLANE_NODES"' "$acceptance"
grep -q -- '--worker-nodes "$WORKER_NODES"' "$acceptance"
if rg -q 'client-certificate-data|client-key-data|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|REPLACE_ME|github_pat_|ghp_' "$tmp"; then
  echo 'golden: credential-shaped material was rendered' >&2; exit 1
fi
echo 'all K8s goldens and safety assertions pass'
