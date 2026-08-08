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
for stage in k8s-infrastructure k8s-ansible-local k8s-ansible-remote k8s-acceptance; do
  [ -d "$base/$stage" ] || { echo "golden: missing stage $stage" >&2; exit 1; }
done

infra="$base/k8s-infrastructure/main.tf"
grep -q 'resource "digitalocean_vpc" "cluster"' "$infra"
grep -q 'resource "digitalocean_droplet" "control_plane"' "$infra"
grep -q 'resource "digitalocean_droplet" "worker"' "$infra"
grep -q 'output "digitalocean_vpc_id"' "$infra"
grep -q 'source_addresses = local.api_sources' "$infra"
grep -q '203.0.113.10/32' "$infra"
grep -q '10.20.0.0/20' "$infra"
[ "$(grep -c 'prevent_destroy = true' "$infra")" -ge 5 ] || {
  echo 'golden: deployment-owned infrastructure lost prevent_destroy' >&2; exit 1
}
if grep -q '0.0.0.0/0.*source' "$infra"; then
  echo 'golden: node ingress is open to the world' >&2; exit 1
fi
grep -q 'k8s-fixture/k8s-infrastructure.tfstate' \
  "$tmp/r2/k8s-fixture/k8s-infrastructure/backend.tf.json"

play="$base/k8s-ansible-remote/create.yml"
for pin in v1.36.3 v0.28.8 v0.1.68 v2.9.3; do
  grep -q "$pin" "$play" || { echo "golden: missing component pin $pin" >&2; exit 1; }
done
grep -q 'cloud-provider=external' "$play"
grep -q 'DO_CLUSTER_VPC_ID=00000000-0000-0000-0000-000000000000' "$play"
grep -q -- '--iface=eth1' "$play"
grep -q 'COLORS_PAR_DO_TOKEN' "$play"
grep -q 'COLORS_PAR_CLOUDFLARE_API_TOKEN' "$play"
grep -q 'no_log: true' "$play"
grep -q 'path: "./clusters/k8s-digitalocean"' "$base/k8s-ansible-remote/gitops.yml"

delete="$base/k8s-ansible-remote/delete.yml"
grep -q 'app.kubernetes.io/name=ingress-nginx' "$delete"
grep -q 'k8s/ansible-remote' <(cd "$root" && bb -e '(require (quote io.github.getcolors.k8s.workflow)) (print io.github.getcolors.k8s.workflow/side-effecting-steps)')

acceptance="$base/k8s-acceptance/acceptance.sh"
grep -q 'expected exactly two Kubernetes nodes' "$acceptance"
grep -q 'https://${host}/healthz' "$acceptance"

# POSIX grep on purpose. rg is not declared in devenv.nix, and a missing binary
# inside `if` is simply false — the guard would pass silently on a machine
# without ripgrep, which is the one case it exists to cover.
if grep -rEq 'client-certificate-data|client-key-data|BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY|REPLACE_ME|github_pat_|ghp_|gho_|ghu_|ghs_|ghr_' "$tmp"; then
  echo 'golden: credential-shaped material was rendered' >&2; exit 1
fi

echo 'all K8s goldens and safety assertions pass'
