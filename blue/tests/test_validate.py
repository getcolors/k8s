from package_k8s_blue import validate

base = {
    "profile": "k8s-test", "workdir": ".colors",
    "provider-compute": "digitalocean", "provider-dns": "cloudflare",
    "provider-backend": "local", "compute-prevent-destroy": True,
    "kubernetes-distribution": "kubeadm", "kubernetes-version": "v1.36.3",
    "kubernetes-cni": "flannel", "flannel-version": "v0.28.8",
    "kubernetes-pod-cidr": "10.244.0.0/16",
    "kubernetes-service-cidr": "10.96.0.0/12",
    "control-plane-count": 1, "worker-count": 1, "flux-version": "v2.9.3",
    "digitalocean-cloud-controller-version": "v0.1.68",
    "digitalocean-cloud-controller": True,
    "repository": "https://github.com/getcolors/k8s-helloworld.git",
    "repository-branch": "main", "repository-path": "./clusters/k8s-digitalocean",
    "digitalocean-name": "k8s-test", "digitalocean-region": "ams3",
    "digitalocean-control-plane-size": "s-2vcpu-4gb",
    "digitalocean-worker-size": "s-2vcpu-4gb",
    "digitalocean-image": "ubuntu-24-04-x64",
    "digitalocean-ssh-key-fingerprint": "fingerprint",
    "digitalocean-vpc-cidr": "10.20.0.0/20",
    "digitalocean-ssh-sources": ["203.0.113.10/32"],
    "digitalocean-api-sources": ["203.0.113.10/32"],
    "application-host": "hello.example.com", "cloudflare-zone": "example.com",
    "external-dns-owner-id": "k8s-test",
    "cert-manager-acme-environment": "production",
}


def matching(opts, needle):
    return [e for e in validate.state_errors(opts) if needle in e]


def test_complete_state_is_valid():
    assert validate.state_errors(base) == []


def test_reports_all_missing_and_invalid_values():
    opts = {k: v for k, v in base.items()
            if k not in ("repository", "digitalocean-region")}
    errors = validate.state_errors({**opts,
                                    "kubernetes-version": "latest",
                                    "worker-count": 3,
                                    "digitalocean-ssh-sources": ["world"]})
    assert len(errors) >= 5
    assert any(":repository" in e for e in errors)
    assert any(":digitalocean-region" in e for e in errors)


def test_package_is_kubeadm_flannel_digitalocean():
    assert matching({**base, "provider-compute": "hcloud"}, "digitalocean")
    assert matching({**base, "kubernetes-distribution": "talos"}, "kubeadm")
    assert matching({**base, "kubernetes-cni": "cilium"}, "flannel")


def test_topology_and_cidrs_are_restricted():
    assert matching({**base, "control-plane-count": 3}, "control-plane-count")
    assert matching({**base, "digitalocean-api-sources": ["0.0.0.0/99"]},
                    "api-sources")


def test_secret_errors_use_colors_variables():
    text = "\n".join(validate.secret_errors(base))
    assert "COLORS_PAR_DO_TOKEN" in text
    assert "COLORS_PAR_CLOUDFLARE_API_TOKEN" in text
    assert validate.secret_errors(
        {**base, "do-token": "x", "cloudflare-api-token": "y"}) == []


def test_profile_overlay_is_always_refused():
    assert "COLORS_PAR_PROFILE" in validate.env_errors(
        {"COLORS_PAR_PROFILE": "other"})[0]
