#!/usr/bin/env bash
set -euo pipefail

alias_name='k8s-fixture'
host='hello.fixture.example'
kubectl=(ssh -- "$alias_name" "sudo -n kubectl --kubeconfig=/etc/kubernetes/admin.conf")

"${kubectl[@]}" wait node --all --for=condition=Ready --timeout=10m
[ "$("${kubectl[@]}" get nodes -o name | wc -l)" -eq 2 ] || {
  echo 'acceptance: expected exactly two Kubernetes nodes' >&2
  exit 1
}
"${kubectl[@]}" -n kube-flannel rollout status daemonset/kube-flannel-ds --timeout=10m
"${kubectl[@]}" -n kube-system rollout status deployment/digitalocean-cloud-controller-manager --timeout=10m
"${kubectl[@]}" -n flux-system wait gitrepository/colors-app --for=condition=Ready --timeout=10m
"${kubectl[@]}" -n flux-system wait kustomization/colors-app --for=condition=Ready --timeout=15m
"${kubectl[@]}" -n flux-system wait kustomization/controllers --for=condition=Ready --timeout=15m
"${kubectl[@]}" -n flux-system wait kustomization/config --for=condition=Ready --timeout=15m
"${kubectl[@]}" -n flux-system wait kustomization/apps --for=condition=Ready --timeout=15m
"${kubectl[@]}" -n ingress-nginx wait pod --all --for=condition=Ready --timeout=10m
"${kubectl[@]}" -n external-dns wait pod --all --for=condition=Ready --timeout=10m
"${kubectl[@]}" -n cert-manager wait pod --all --for=condition=Ready --timeout=10m
"${kubectl[@]}" -n hello-world rollout status deployment/hello-world --timeout=10m
"${kubectl[@]}" -n hello-world wait certificate/hello-world-tls --for=condition=Ready --timeout=15m

for _ in $(seq 1 90); do
  if response=$(curl --fail --silent --show-error --max-time 15 "https://${host}/"); then
    grep -qx 'Hello from kubeadm on DigitalOcean' <<<"$response" || {
      echo "acceptance: unexpected response: $response" >&2
      exit 1
    }
    curl --fail --silent --show-error --max-time 15 "https://${host}/healthz" | grep -qx ok
    echo 'acceptance: two nodes, Flannel, DigitalOcean CCM/LB, Flux, DNS, TLS, and hello-world are healthy'
    exit 0
  fi
  sleep 10
done

echo 'acceptance: HTTPS application did not converge' >&2
exit 1
