#!/usr/bin/env bash
set -euo pipefail

# Green's regression net against the committed goldens: render every fixture
# under both state backends and diff against committed output. scripts/parity.sh
# is the net across colours.
#
# Two fixtures, because the SSH Keypair Standard has two modes and a package
# conforms only if both hold. `colors.yml` is keygen mode (no
# digitalocean-ssh-keys): the compute template must declare the profile-named
# digitalocean_ssh_key resource and reference it by attribute, and the local
# stage and the inventory must name the generated key. `optout.yml` supplies
# an explicit key and must create nothing — its rendering is byte-for-byte what
# the package rendered before the standard, under its own profile, apart from
# the local stage whose marker migrated. Two backends: each fixture is
# rendered under local and again under r2 by overlaying
# COLORS_PAR_PROVIDER_BACKEND on the same file.
#
#   ./scripts/golden.sh            check
#   ./scripts/golden.sh --accept   regenerate after an intended change

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
goldens="$root/test/resources/golden"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
accept=0
[ "${1:-}" = --accept ] && accept=1
status=0

build() {
  local fixture=$1 backend=$2
  local state="$root/test/fixtures/$fixture.yml"
  local profile
  profile=$(sed -n 's/^profile: //p' "$state")
  (cd "$root/green" && env K8S_LIB_ROOT="$root" COLORS_PAR_WORKDIR="$tmp/$backend-$fixture" \
    COLORS_PAR_PROVIDER_BACKEND="$backend" ./green build -f "$state" >/dev/null)
  local actual="$tmp/$backend-$fixture/$profile"
  local golden="$goldens/$backend/$profile"

  checks "$actual" "$profile" "$fixture" "$backend"

  if [ "$accept" = 1 ]; then
    rm -rf "${golden:?}"; mkdir -p "$golden"
    cp -r "$actual/." "$golden/"
    echo "  accepted — $backend/$profile"
  else
    [ -d "$golden" ] || { echo "golden missing for $backend/$profile; inspect build then run bb golden:accept" >&2; exit 1; }
    if diff -qr "$golden" "$actual"; then
      echo "  ok — $backend/$profile"
    else
      status=1
    fi
  fi
}

checks() {
  local base=$1 profile=$2 fixture=$3 backend=$4
  for stage in k8s-infrastructure k8s-ansible-local k8s-ansible-remote k8s-acceptance; do
    [ -d "$base/$stage" ] || { echo "golden: $profile is missing stage $stage" >&2; exit 1; }
  done

  local infra="$base/k8s-infrastructure/main.tf"
  grep -q 'resource "digitalocean_vpc" "cluster"' "$infra"
  grep -q 'resource "digitalocean_droplet" "control_plane"' "$infra"
  grep -q 'resource "digitalocean_droplet" "worker"' "$infra"
  grep -q 'output "digitalocean_vpc_id"' "$infra"
  grep -q 'output "params"' "$infra"
  grep -q 'source_addresses = local.api_sources' "$infra"
  grep -q '203.0.113.10/32' "$infra"
  grep -q '10.20.0.0/20' "$infra"
  [ "$(grep -c 'prevent_destroy = true' "$infra")" -ge 5 ] || {
    echo "golden: $profile: deployment-owned infrastructure lost prevent_destroy" >&2; exit 1
  }
  if grep -q '0.0.0.0/0.*source' "$infra"; then
    echo "golden: $profile: node ingress is open to the world" >&2; exit 1
  fi
  # The SSH Keypair Standard, both modes: keygen declares the profile-named key
  # resource and references it by attribute on both droplet resources; opt-out
  # keeps the literal and creates nothing.
  if [ "$fixture" = colors ]; then
    grep -q 'resource "digitalocean_ssh_key" "machine"' "$infra" || { echo "golden: $profile: keygen mode declares no key resource" >&2; exit 1; }
    [ "$(grep -c 'ssh_keys = \[digitalocean_ssh_key.machine.id\]' "$infra")" -eq 2 ] || { echo "golden: $profile: keygen mode does not reference the key by attribute on both droplets" >&2; exit 1; }
    grep -q 'ssh_key_id = digitalocean_ssh_key.machine.id' "$infra" || { echo "golden: $profile: params carries no ssh_key_id" >&2; exit 1; }
    grep -q 'IdentityFile ~/.ssh/k8s-fixture' "$base/k8s-ansible-local/main.yml" || { echo "golden: $profile: the local stage names no identity file" >&2; exit 1; }
    grep -q '"ansible_ssh_private_key_file" : "/home/build-placeholder/.ssh/k8s-fixture"' "$base/k8s-ansible-remote/inventory.json" || { echo "golden: $profile: the inventory does not name the generated key" >&2; exit 1; }
  else
    ! grep -q 'digitalocean_ssh_key' "$infra" || { echo "golden: $profile: opt-out mode must create no key" >&2; exit 1; }
    [ "$(grep -c 'ssh_keys = \["c8:24:b0:7f:94:28:37:5a:23:d6:02:8b:b0:00:d7:7a"\]' "$infra")" -eq 2 ] || { echo "golden: $profile: opt-out mode lost the literal key" >&2; exit 1; }
    ! grep -qE '^\s+IdentityFile ' "$base/k8s-ansible-local/main.yml" || { echo "golden: $profile: opt-out mode must not guess an identity file" >&2; exit 1; }
    ! grep -q 'ansible_ssh_private_key_file' "$base/k8s-ansible-remote/inventory.json" || { echo "golden: $profile: opt-out mode must not name a key file" >&2; exit 1; }
  fi
  # ssh-config.md §8: the marker is the alias alone, and the superseded
  # package-prefixed block is removed for one pin cycle.
  grep -q 'marker: "# {mark} {{ host_alias }} ANSIBLE MANAGED BLOCK"' "$base/k8s-ansible-local/main.yml"
  grep -q 'marker: "# {mark} k8s {{ host_alias }} ANSIBLE MANAGED BLOCK"' "$base/k8s-ansible-local/main.yml"
  grep -q 'insertbefore: BOF' "$base/k8s-ansible-local/main.yml"

  if [ "$backend" = r2 ]; then
    grep -q "$profile/k8s-infrastructure.tfstate" "$base/k8s-infrastructure/backend.tf.json"
  fi

  local play="$base/k8s-ansible-remote/create.yml"
  for pin in v1.36.3 v0.28.8 v0.1.68 v2.9.3; do
    grep -q "$pin" "$play" || { echo "golden: $profile: missing component pin $pin" >&2; exit 1; }
  done
  grep -q 'cloud-provider=external' "$play"
  grep -q 'DO_CLUSTER_VPC_ID=00000000-0000-0000-0000-000000000000' "$play"
  grep -q -- '--iface=eth1' "$play"
  grep -q 'COLORS_PAR_DO_TOKEN' "$play"
  grep -q 'COLORS_PAR_CLOUDFLARE_API_TOKEN' "$play"
  grep -q 'no_log: true' "$play"
  grep -q 'path: "./clusters/k8s-digitalocean"' "$base/k8s-ansible-remote/gitops.yml"

  local delete="$base/k8s-ansible-remote/delete.yml"
  grep -q 'app.kubernetes.io/name=ingress-nginx' "$delete"
  # The application is pruned and its records withdrawn before the controllers
  # go, or the application host outlives the cluster.
  grep -q 'delete kustomization apps' "$delete"
  grep -q "action=DELETE record=hello.fixture.example" "$delete" || { echo "golden: $profile: the delete does not wait for the DNS withdrawal" >&2; exit 1; }

  local acceptance="$base/k8s-acceptance/acceptance.sh"
  grep -q 'expected exactly two Kubernetes nodes' "$acceptance"
  grep -q 'https://${host}/healthz' "$acceptance"

  # POSIX grep on purpose. rg is not declared in devenv.nix, and a missing binary
  # inside `if` is simply false — the guard would pass silently on a machine
  # without ripgrep, which is the one case it exists to cover.
  if grep -rEq 'client-certificate-data|client-key-data|BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY|REPLACE_ME|github_pat_|ghp_|gho_|ghu_|ghs_|ghr_' "$base"; then
    echo "golden: $profile: credential-shaped material was rendered" >&2; exit 1
  fi
  # A Selmer tag that survived rendering is a typo or an unsupplied key.
  if grep -rn '<{' "$base"; then
    echo "golden: $profile left an unrendered Selmer tag" >&2; exit 1
  fi
  # A build that reached the real ~/.ssh would leak the operator's home into
  # committed bytes and make the goldens workstation-specific.
  if grep -rq "$HOME/.ssh" "$base"; then
    echo "golden: $profile rendered a real home directory; build must use the placeholder" >&2; exit 1
  fi
  # SSH Config Standard §6: the local stage takes the address and the alias as
  # Ansible extra-vars, never through Selmer, so its rendered playbook carries
  # no address at all.
  if grep -rEq '([0-9]{1,3}\.){3}[0-9]{1,3}' "$base/k8s-ansible-local"; then
    echo "golden: $profile rendered an address into the local ssh_config stage" >&2; exit 1
  fi
}

grep -q 'k8s/ansible-remote' <(cd "$root/green" && bb -e '(require (quote io.github.getcolors.k8s.workflow)) (print io.github.getcolors.k8s.workflow/side-effecting-steps)')

for fixture in colors optout; do
  for backend in local r2; do
    build "$fixture" "$backend"
  done
done

[ "$status" = 0 ] && echo 'all k8s goldens and safety assertions pass'
exit "$status"
