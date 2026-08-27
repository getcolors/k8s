"""Credential-free kubeadm/DigitalOcean desired-state validation, the port of
io.github.getcolors.k8s.validate.

Green renders its keys as Clojure keywords, so every message here carries the
same leading colon — the three colours must report identical errors for one
colors.yml.
"""

from __future__ import annotations

import re

from blue.cli import par_name

providers = {
    "provider-compute": {
        "digitalocean": {
            "required": ["digitalocean-name", "digitalocean-region",
                         "digitalocean-control-plane-size",
                         "digitalocean-worker-size", "digitalocean-image",
                         "digitalocean-ssh-key-fingerprint",
                         "digitalocean-vpc-cidr",
                         "digitalocean-ssh-sources",
                         "digitalocean-api-sources"],
            "secrets": ["do-token"],
            "tofu-env": {"do-token": "DIGITALOCEAN_TOKEN"},
        },
    },
    "provider-dns": {
        "cloudflare": {"required": ["cloudflare-zone", "application-host"],
                       "secrets": ["cloudflare-api-token"],
                       "tofu-env": {}},
        "no-infra": {"required": [], "secrets": [], "tofu-env": {}},
    },
    "provider-backend": {
        "local": {"required": [], "secrets": [], "tofu-env": {}},
        "s3": {"required": ["s3-bucket", "s3-region"],
               "secrets": ["s3-access-key-id", "s3-secret-access-key"],
               "tofu-env": {"s3-access-key-id": "AWS_ACCESS_KEY_ID",
                            "s3-secret-access-key": "AWS_SECRET_ACCESS_KEY"}},
        "r2": {"required": ["r2-bucket", "r2-endpoint"],
               "secrets": ["r2-access-key-id", "r2-secret-access-key"],
               "tofu-env": {"r2-access-key-id": "AWS_ACCESS_KEY_ID",
                            "r2-secret-access-key": "AWS_SECRET_ACCESS_KEY"}},
    },
}

slots = ["provider-compute", "provider-dns", "provider-backend"]

profile_par = par_name("profile")


def placeholder(x) -> bool:
    return x is None or (isinstance(x, str) and (not x.strip() or x.upper() == "REPLACE_ME"))


def _entry(opts: dict, slot: str) -> dict | None:
    value = opts.get(slot)
    return providers.get(slot, {}).get(value) if isinstance(value, str) else None


def tofu_env(opts: dict, slot: str) -> dict[str, str]:
    """Flat credential key to the environment variable consumed by OpenTofu."""
    return (_entry(opts, slot) or {}).get("tofu-env", {})


def _slot_keys(opts: dict, field: str) -> list[str]:
    return [key for slot in slots for key in (_entry(opts, slot) or {}).get(field, [])]


def _missing(opts: dict, keys: list[str]) -> list[str]:
    return [key for key in keys if placeholder(opts.get(key))]


def env_errors(env: dict) -> list[str]:
    """Refuse the one environment overlay that could redirect remote state."""
    if str(env.get(profile_par) or ""):
        return [f"{profile_par} is set. K8s takes profile from colors.yml only; "
                "an environment overlay could redirect remote state."]
    return []


_semver_re = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+")
_https_git_re = re.compile(r"https://[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)+(?:\.git)?")
_dns_re = re.compile(
    r"(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,63}")
_cidr_re = re.compile(r"(?:[0-9]{1,3}\.){3}[0-9]{1,3}/(?:[0-9]|[12][0-9]|3[0-2])")
_branch_re = re.compile(r"[A-Za-z0-9._/-]+")
_path_re = re.compile(r"\./[A-Za-z0-9._/-]+")
_profile_re = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,62}")


def valid_cidr(value) -> bool:
    if not _cidr_re.fullmatch(str(value)):
        return False
    octets = str(value).split("/")[0].split(".")
    return all(0 <= int(octet) <= 255 for octet in octets)


def _pr_str(value) -> str:
    """pr-str, for the unsupported-provider message: green prints the offending
    value through pr-str, which quotes strings and renders nil bare."""
    if value is None:
        return "nil"
    if isinstance(value, str):
        return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


required_keys = [
    "profile", "workdir", "kubernetes-distribution", "kubernetes-version",
    "kubernetes-cni", "flannel-version", "kubernetes-pod-cidr",
    "kubernetes-service-cidr", "flux-version",
    "digitalocean-cloud-controller-version", "repository", "repository-branch",
    "repository-path", "control-plane-count", "worker-count",
    "external-dns-owner-id", "cert-manager-acme-environment",
]


def state_errors(opts: dict) -> list[str]:
    """All credential-free validation errors."""
    errors: list[str] = []
    for key in _missing(opts, [*required_keys, *_slot_keys(opts, "required")]):
        errors.append(f":{key} is required")
    for slot in slots:
        if _entry(opts, slot) is None:
            errors.append(f"unsupported :{slot} {_pr_str(opts.get(slot))}")
    if opts.get("provider-compute") != "digitalocean":
        errors.append(":provider-compute must be digitalocean")
    if opts.get("kubernetes-distribution") != "kubeadm":
        errors.append(":kubernetes-distribution must be kubeadm")
    if opts.get("kubernetes-cni") != "flannel":
        errors.append(":kubernetes-cni must be flannel")
    if not _count_is_one(opts.get("control-plane-count")):
        errors.append(":control-plane-count must be 1")
    if not _count_is_one(opts.get("worker-count")):
        errors.append(":worker-count must be 1")
    if opts.get("digitalocean-cloud-controller") is not True:
        errors.append(":digitalocean-cloud-controller must be true")
    if not isinstance(opts.get("compute-prevent-destroy"), bool):
        errors.append(":compute-prevent-destroy must be true or false")
    if not (placeholder(opts.get("profile"))
            or _profile_re.fullmatch(str(opts.get("profile")))):
        errors.append(":profile must be a safe 1-63 character name")
    for key in ["kubernetes-version", "flannel-version", "flux-version",
                "digitalocean-cloud-controller-version"]:
        value = opts.get(key)
        if not placeholder(value) and not _semver_re.fullmatch(str(value)):
            errors.append(f":{key} must be an exact vMAJOR.MINOR.PATCH release")
    if (not placeholder(opts.get("repository"))
            and not _https_git_re.fullmatch(str(opts.get("repository")))):
        errors.append(":repository must be a public HTTPS Git URL")
    if not (placeholder(opts.get("repository-branch"))
            or _branch_re.fullmatch(str(opts.get("repository-branch")))):
        errors.append(":repository-branch contains unsupported characters")
    if not (placeholder(opts.get("repository-path"))
            or _path_re.fullmatch(str(opts.get("repository-path")))):
        errors.append(":repository-path must begin with ./")
    for key in ["application-host", "cloudflare-zone"]:
        value = opts.get(key)
        if (opts.get("provider-dns") == "cloudflare" and not placeholder(value)
                and not _dns_re.fullmatch(str(value))):
            errors.append(f":{key} must be a DNS name")
    if (opts.get("provider-dns") == "cloudflare"
            and not placeholder(opts.get("application-host"))
            and not placeholder(opts.get("cloudflare-zone"))
            and not str(opts.get("application-host")).endswith(
                f".{opts.get('cloudflare-zone')}")):
        errors.append(":application-host must be below :cloudflare-zone")
    for key in ["kubernetes-pod-cidr", "kubernetes-service-cidr",
                "digitalocean-vpc-cidr"]:
        value = opts.get(key)
        if not placeholder(value) and not valid_cidr(value):
            errors.append(f":{key} must be a valid IPv4 CIDR")
    for key in ["digitalocean-ssh-sources", "digitalocean-api-sources"]:
        values = opts.get(key)
        if not placeholder(values) and (
                not isinstance(values, (list, tuple)) or not values
                or any(not valid_cidr(value) for value in values)):
            errors.append(f":{key} must be a non-empty list of IPv4 CIDRs")
    if opts.get("cert-manager-acme-environment") not in ("production", "staging"):
        errors.append(":cert-manager-acme-environment must be production or staging")
    return errors


def _count_is_one(value) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value == 1


def secret_errors(opts: dict, selected: list[str] | None = None) -> list[str]:
    """Credentials required by the selected providers."""
    chosen = slots if selected is None else selected
    keys = [key for slot in chosen for key in (_entry(opts, slot) or {}).get("secrets", [])]
    return [f"required credential is not set: {par_name(key)}"
            for key in dict.fromkeys(_missing(opts, keys))]
