#!/usr/bin/env bash
set -euo pipefail

: "${KUBECONFIG:?KUBECONFIG is required}"
: "${TALOSCONFIG:?TALOSCONFIG is required}"
: "${EXPECTED_INGRESS_IPV4:?EXPECTED_INGRESS_IPV4 is required}"
: "${TALOS_ENDPOINT:?TALOS_ENDPOINT is required}"
: "${CONTROL_PLANE_NODES:?CONTROL_PLANE_NODES is required}"
: "${WORKER_NODES:?WORKER_NODES is required}"

kubectl wait node --all --for=condition=Ready --timeout=10m
[ "$(kubectl get nodes -o name | wc -l)" -eq 6 ] || {
  echo 'acceptance: expected exactly six Kubernetes nodes' >&2
  exit 1
}

talosctl health --endpoints "$TALOS_ENDPOINT" --nodes "$TALOS_ENDPOINT" \
  --init-node "$TALOS_ENDPOINT" \
  --control-plane-nodes "$CONTROL_PLANE_NODES" \
  --worker-nodes "$WORKER_NODES" --wait-timeout=10m
kubectl -n kube-system rollout status daemonset/cilium --timeout=10m
kubectl -n kube-system rollout status deployment/cilium-operator --timeout=10m
kubectl -n kube-system rollout status deployment/hcloud-cloud-controller-manager --timeout=10m
kubectl -n kube-system rollout status deployment/hcloud-csi-controller --timeout=10m
kubectl -n kube-system rollout status daemonset/hcloud-csi-node --timeout=10m
kubectl -n external-dns rollout status deployment/external-dns --timeout=10m
kubectl -n cert-manager rollout status deployment/cert-manager --timeout=10m
kubectl -n cert-manager rollout status deployment/cert-manager-webhook --timeout=10m
kubectl -n flux-system wait deployment --all --for=condition=Available --timeout=10m

kubectl -n kube-system exec daemonset/cilium -- cilium-dbg status --verbose \
  | grep -Eiq 'Encryption:[[:space:]]+Wireguard|WireGuard' || {
    echo 'acceptance: Cilium does not report WireGuard encryption' >&2
    exit 1
  }

kubectl -n flux-system wait gitrepository/colors --for=condition=Ready --timeout=10m
kubectl -n flux-system wait kustomization/colors --for=condition=Ready --timeout=10m

<% if persistent-volume-test-enabled %>
kubectl -n volume-test wait pod/acceptance --for=condition=Ready --timeout=10m
[ "$(kubectl -n volume-test get pvc acceptance -o jsonpath='{.status.phase}')" = Bound ]
kubectl -n volume-test exec acceptance -- grep -qx persistent /data/result
<% endif %>

<% if hello-world-enabled %>
kubectl -n hello-world rollout status deployment/hello-world --timeout=10m
kubectl -n hello-world wait certificate/hello-world-tls --for=condition=Ready --timeout=15m
resolved=''
for _ in $(seq 1 90); do
  resolved=$(getent ahostsv4 '<{ ingress-test-hostname }>' | awk 'NR == 1 {print $1}')
  [ -n "$resolved" ] && break
  sleep 10
done
[ -n "$resolved" ] || { echo 'acceptance: ingress DNS did not resolve' >&2; exit 1; }
<% if not external-dns-cloudflare-proxied %>
[ "$resolved" = "$EXPECTED_INGRESS_IPV4" ] || {
  echo "acceptance: ingress DNS resolved to $resolved, expected $EXPECTED_INGRESS_IPV4" >&2
  exit 1
}
<% endif %>
for _ in $(seq 1 60); do
  if curl --fail --silent --show-error --max-time 10 \
      'https://<{ ingress-test-hostname }>/' >/dev/null; then
    echo 'acceptance: Talos, six nodes, Cilium WireGuard/ingress, CCM/CSI, Flux, DNS, TLS, and volume are healthy'
    exit 0
  fi
  sleep 10
done
echo 'acceptance: HTTPS ingress did not converge' >&2
exit 1
<% else %>
echo 'acceptance: Talos, six nodes, Cilium WireGuard, CCM/CSI, Flux, and volume are healthy'
<% endif %>
