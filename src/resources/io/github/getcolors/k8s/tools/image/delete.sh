#!/usr/bin/env bash
set -euo pipefail
selector='colors-package=k8s,colors-profile=<{ profile }>,talos-version=<{ talos-version }>'
hcloud image list --selector "$selector" -o json \
  | jq -r '.[].id' \
  | while read -r image_id; do
      [ -n "$image_id" ] && hcloud image delete "$image_id"
    done
