from package_k8s_blue import validate
from package_once_blue import compute_cluster as cluster

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


def test_compute_checks_are_the_standards_in_once_words():
    # The source lists, the owned VPC's CIDR and the selection are ONCE's
    # checks over `spec`; the package no longer words them itself.
    assert matching({**base, "digitalocean-api-sources": ["world"]}, "api-sources") == \
        [':digitalocean-api-sources entry "world" is not an IPv4 or IPv6 CIDR']
    assert matching({**base, "digitalocean-ssh-sources": []}, "ssh-sources") == \
        [":digitalocean-ssh-sources must list at least one CIDR"]
    assert matching({**base, "digitalocean-vpc-cidr": "10.20.0.1/20"}, "vpc-cidr") == \
        [":digitalocean-vpc-cidr must be a canonical IPv4 network such as 10.40.0.0/24"]
    without_cidr = {k: v for k, v in base.items() if k != "digitalocean-vpc-cidr"}
    assert matching(without_cidr, "vpc-cidr") == [":digitalocean-vpc-cidr is required"]
    assert matching({**base, "provider-compute": "hcloud"}, "provider-compute") == \
        [":provider-compute must be one of digitalocean"]
    # A created network is this package's to own: compute's DigitalOcean
    # "must not create a VPC" refusal is filtered, never reported.
    assert matching(base, "must be absent") == []


def test_spec_content_is_the_two_role_topology():
    assert cluster.spec_errors(validate.spec) == []
    assert [r["role"] for r in validate.spec["roles"]] == ["control-plane", "worker"]
    assert [r["count"] for r in validate.spec["roles"]] == [1, 1]
    assert [r["count_key"] for r in validate.spec["roles"]] == \
        ["control-plane-count", "worker-count"]
    assert validate.spec["entry"] == {"role": "control-plane", "index": 0}
    assert validate.spec["registry"]["digitalocean"]["network"] == \
        {"mode": "created", "key": "digitalocean-vpc-cidr"}
    assert validate.spec["default"] == "digitalocean"
    assert validate.spec["sources"] == \
        {"non_empty": ["ssh-sources", "api-sources"], "may_be_empty": []}
    assert "fallback_subnet" not in validate.spec
    assert cluster.topology_errors(validate.spec, base) == []
    assert cluster.aliases(validate.spec, base) == \
        ["k8s-test", "k8s-test-control-plane", "k8s-test-worker"]


def test_secret_errors_use_colors_variables():
    text = "\n".join(validate.secret_errors(base))
    assert "COLORS_PAR_DO_TOKEN" in text
    assert "COLORS_PAR_CLOUDFLARE_API_TOKEN" in text
    assert validate.secret_errors(
        {**base, "do-token": "x", "cloudflare-api-token": "y"}) == []


def test_profile_overlay_is_always_refused():
    assert "COLORS_PAR_PROFILE" in validate.env_errors(
        {"COLORS_PAR_PROFILE": "other"})[0]
