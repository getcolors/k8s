#!/usr/bin/env bash
set -euo pipefail
selector='colors-package=k8s,colors-profile=k8s-fixture,talos-version=v1.13.7'
hcloud image list --selector "$selector" -o json \
  | jq -r '.[].id' \
  | while read -r image_id; do
      [ -n "$image_id" ] && hcloud image delete "$image_id"
    done
