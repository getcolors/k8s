#!/usr/bin/env bash
set -euo pipefail
umask 077

for command in kubectl helm jq sed; do
  command -v "$command" >/dev/null || { echo "required command is missing: $command" >&2; exit 1; }
done
: "${KUBECONFIG:?KUBECONFIG is required}"
: "${HCLOUD_TOKEN:?HCLOUD_TOKEN is required}"
: "${HCLOUD_NETWORK:?HCLOUD_NETWORK is required}"
: "${CLOUDFLARE_API_TOKEN:?CLOUDFLARE_API_TOKEN is required}"
: "${INGRESS_IPV4:?INGRESS_IPV4 is required}"

dir=$(cd "$(dirname "$0")" && pwd)

helm repo add cilium https://helm.cilium.io/ --force-update >/dev/null
helm repo add hcloud https://charts.hetzner.cloud --force-update >/dev/null
helm repo add external-dns https://kubernetes-sigs.github.io/external-dns/ --force-update >/dev/null
helm repo add jetstack https://charts.jetstack.io --force-update >/dev/null
helm repo update >/dev/null

helm upgrade --install cilium cilium/cilium \
  --namespace kube-system --version '<{ cilium-chart }>' \
  --values "$dir/cilium-values.yaml" --wait --timeout 10m
kubectl -n kube-system rollout status daemonset/cilium --timeout=10m

# jq reads credentials from the environment and streams Secret manifests to the
# API. Tokens are never command arguments or files.
jq -n '{apiVersion:"v1",kind:"Secret",metadata:{name:"hcloud",namespace:"kube-system"},type:"Opaque",stringData:{token:env.HCLOUD_TOKEN,network:env.HCLOUD_NETWORK}}' \
  | kubectl apply -f - >/dev/null
kubectl create namespace external-dns --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl create namespace cert-manager --dry-run=client -o yaml | kubectl apply -f - >/dev/null
jq -n '{apiVersion:"v1",kind:"Secret",metadata:{name:"cloudflare-api-token",namespace:"external-dns"},type:"Opaque",stringData:{"api-token":env.CLOUDFLARE_API_TOKEN}}' \
  | kubectl apply -f - >/dev/null
jq -n '{apiVersion:"v1",kind:"Secret",metadata:{name:"cloudflare-api-token",namespace:"cert-manager"},type:"Opaque",stringData:{"api-token":env.CLOUDFLARE_API_TOKEN}}' \
  | kubectl apply -f - >/dev/null

helm upgrade --install hcloud-cloud-controller-manager hcloud/hcloud-cloud-controller-manager \
  --namespace kube-system --version '<{ hcloud-ccm-chart }>' \
  --values "$dir/ccm-values.yaml" --wait --timeout 10m
helm upgrade --install hcloud-csi hcloud/hcloud-csi \
  --namespace kube-system --version '<{ hcloud-csi-chart }>' \
  --values "$dir/csi-values.yaml" --wait --timeout 10m
helm upgrade --install cert-manager jetstack/cert-manager \
  --namespace cert-manager --version '<{ cert-manager-chart }>' \
  --values "$dir/cert-manager-values.yaml" --wait --timeout 10m
helm upgrade --install external-dns external-dns/external-dns \
  --namespace external-dns --version '<{ external-dns-chart }>' \
  --values "$dir/external-dns-values.yaml" --wait --timeout 10m

kubectl apply -f 'https://github.com/fluxcd/flux2/releases/download/<{ flux-version }>/install.yaml'
kubectl -n flux-system wait deployment --all --for=condition=Available --timeout=10m

# Only a public load-balancer address is substituted. The resulting manifest is
# streamed and discarded; the generated template remains deterministic.
sed "s/__INGRESS_IPV4__/${INGRESS_IPV4}/g" "$dir/platform.yaml" | kubectl apply -f -
kubectl apply -f "$dir/gitops.yaml"
