#!/usr/bin/env bash
set -euo pipefail
umask 077

profile='<{ profile }>'
talos_version='<{ talos-version }>'
location='<{ hcloud-location }>'
selector="colors-package=k8s,colors-profile=${profile},talos-version=${talos_version}"

if hcloud image list --selector "$selector" -o json \
    | jq -e 'any(.[]; .status == "available")' >/dev/null; then
  echo "Talos snapshot already exists for ${profile} ${talos_version}"
  exit 0
fi

schematic=$(curl --fail --silent --show-error \
  -X POST --data-binary @"$(dirname "$0")/schematic.yaml" \
  https://factory.talos.dev/schematics | jq -er '.id')
image_url="https://factory.talos.dev/image/${schematic}/${talos_version}/nocloud-amd64.raw.xz"

scratch=$(mktemp -d)
builder="colors-${profile}-talos-image"
key="${builder}-$(date +%s)"
cleanup() {
  hcloud server delete "$builder" >/dev/null 2>&1 || true
  hcloud ssh-key delete "$key" >/dev/null 2>&1 || true
  rm -rf "$scratch"
}
trap cleanup EXIT INT TERM

# A killed prior run may leave only this deterministic package-owned builder.
hcloud server delete "$builder" >/dev/null 2>&1 || true
ssh-keygen -q -t ed25519 -N '' -C "$key" -f "$scratch/id_ed25519"
hcloud ssh-key create --name "$key" --public-key-from-file "$scratch/id_ed25519.pub" >/dev/null
hcloud server create --name "$builder" --type cx23 --location "$location" \
  --image debian-13 --ssh-key "$key" --start-after-create=false >/dev/null
hcloud server enable-rescue --type linux64 --ssh-key "$key" "$builder" >/dev/null
hcloud server poweron "$builder" >/dev/null
ip=$(hcloud server describe "$builder" -o json | jq -er '.public_net.ipv4.ip')

connected=0
for _ in $(seq 1 90); do
  if ssh -i "$scratch/id_ed25519" -o BatchMode=yes -o ConnectTimeout=3 \
      -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile="$scratch/known_hosts" \
      root@"$ip" true 2>/dev/null; then
    connected=1
    break
  fi
  sleep 2
done
[ "$connected" = 1 ] || { echo 'Talos image builder never became reachable' >&2; exit 1; }

# The public Image Factory checksum endpoint is enterprise-only. HTTPS protects
# transport authenticity; xz -t verifies the complete compressed stream before
# anything is written to the builder disk.
ssh -i "$scratch/id_ed25519" -o BatchMode=yes \
  -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile="$scratch/known_hosts" \
  root@"$ip" "set -euo pipefail; cd /tmp; \
    curl -fsSL -o nocloud-amd64.raw.xz '${image_url}'; \
    xz -t nocloud-amd64.raw.xz; \
    xz -dc nocloud-amd64.raw.xz | dd of=/dev/sda bs=4M conv=fsync status=progress; sync"

hcloud server shutdown "$builder" >/dev/null
for _ in $(seq 1 90); do
  [ "$(hcloud server describe "$builder" -o json | jq -r '.status')" = off ] && break
  sleep 2
done
[ "$(hcloud server describe "$builder" -o json | jq -r '.status')" = off ] || {
  echo 'Talos image builder did not power off' >&2
  exit 1
}

image_id=$(hcloud server create-image --type snapshot \
  --description "Talos ${talos_version} (${schematic}) for Colors ${profile}" \
  "$builder" -o json | jq -er '.image.id')
hcloud image add-label "$image_id" colors-package=k8s
hcloud image add-label "$image_id" "colors-profile=$profile"
hcloud image add-label "$image_id" "talos-version=$talos_version"
hcloud image add-label "$image_id" "talos-schematic=$schematic"

for _ in $(seq 1 180); do
  [ "$(hcloud image describe "$image_id" -o json | jq -r '.status')" = available ] && exit 0
  sleep 2
done
echo 'Talos snapshot did not become available' >&2
exit 1
