// Credential-free kubeadm/DigitalOcean desired-state validation, the port of
// io.github.getcolors.k8s.validate.
//
// Green renders its keys as Clojure keywords, so every message here carries the
// same leading colon — the three colours must report identical errors for one
// colors.yml.

import { parName } from "red/cli";
import type { Registry } from "red/providers";
import type { Opts } from "red/workflow";

export const providers: Registry = {
  "provider-compute": {
    digitalocean: {
      required: ["digitalocean-name", "digitalocean-region",
                 "digitalocean-control-plane-size",
                 "digitalocean-worker-size", "digitalocean-image",
                 "digitalocean-ssh-key-fingerprint",
                 "digitalocean-vpc-cidr",
                 "digitalocean-ssh-sources",
                 "digitalocean-api-sources"],
      secrets: ["do-token"],
      tofuEnv: { "do-token": "DIGITALOCEAN_TOKEN" },
    },
  },
  "provider-dns": {
    cloudflare: {
      required: ["cloudflare-zone", "application-host"],
      secrets: ["cloudflare-api-token"],
      tofuEnv: {},
    },
    "no-infra": { required: [], secrets: [], tofuEnv: {} },
  },
  "provider-backend": {
    local: { required: [], secrets: [], tofuEnv: {} },
    s3: {
      required: ["s3-bucket", "s3-region"],
      secrets: ["s3-access-key-id", "s3-secret-access-key"],
      tofuEnv: { "s3-access-key-id": "AWS_ACCESS_KEY_ID",
                 "s3-secret-access-key": "AWS_SECRET_ACCESS_KEY" },
    },
    r2: {
      required: ["r2-bucket", "r2-endpoint"],
      secrets: ["r2-access-key-id", "r2-secret-access-key"],
      tofuEnv: { "r2-access-key-id": "AWS_ACCESS_KEY_ID",
                 "r2-secret-access-key": "AWS_SECRET_ACCESS_KEY" },
    },
  },
};

export const slots = ["provider-compute", "provider-dns", "provider-backend"];

export const profilePar = parName("profile");

interface ProviderEntry {
  required?: string[];
  secrets?: string[];
  tofuEnv?: Record<string, string>;
}

export function placeholder(value: unknown): boolean {
  return value == null ||
    (typeof value === "string" && (!value.trim() || value.toUpperCase() === "REPLACE_ME"));
}

function entry(opts: Opts, slot: string): ProviderEntry | undefined {
  return (providers as Record<string, Record<string, ProviderEntry>>)[slot]?.[String(opts[slot])];
}

// Flat credential key to the environment variable consumed by OpenTofu.
export function tofuEnv(opts: Opts, slot: string): Record<string, string> {
  return entry(opts, slot)?.tofuEnv ?? {};
}

function slotKeys(opts: Opts, field: "required" | "secrets"): string[] {
  return slots.flatMap((slot) => entry(opts, slot)?.[field] ?? []);
}

function missing(opts: Opts, keys: string[]): string[] {
  return keys.filter((key) => placeholder(opts[key]));
}

// Refuse the one environment overlay that could redirect remote state.
export function envErrors(env: Record<string, string | undefined>): string[] {
  return String(env[profilePar] ?? "").length
    ? [`${profilePar} is set. K8s takes profile from colors.yml only; ` +
       "an environment overlay could redirect remote state."]
    : [];
}

const semverRe = /^v[0-9]+\.[0-9]+\.[0-9]+$/;
const httpsGitRe = /^https:\/\/[A-Za-z0-9._~-]+(?:\/[A-Za-z0-9._~-]+)+(?:\.git)?$/;
const dnsRe = /^(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,63}$/;
const cidrRe = /^(?:[0-9]{1,3}\.){3}[0-9]{1,3}\/(?:[0-9]|[12][0-9]|3[0-2])$/;
const branchRe = /^[A-Za-z0-9._/-]+$/;
const pathRe = /^\.\/[A-Za-z0-9._/-]+$/;
const profileRe = /^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$/;

export function validCidr(value: unknown): boolean {
  return cidrRe.test(String(value)) &&
    String(value).split("/")[0]!.split(".").every((octet) => Number(octet) <= 255);
}

// pr-str, for the unsupported-provider message: green prints the offending
// value through pr-str, which quotes strings and renders nil bare.
function prStr(value: unknown): string {
  if (value == null) return "nil";
  if (typeof value === "string") return JSON.stringify(value);
  return String(value);
}

export const requiredKeys = [
  "profile", "workdir", "kubernetes-distribution", "kubernetes-version",
  "kubernetes-cni", "flannel-version", "kubernetes-pod-cidr",
  "kubernetes-service-cidr", "flux-version",
  "digitalocean-cloud-controller-version", "repository", "repository-branch",
  "repository-path", "control-plane-count", "worker-count",
  "external-dns-owner-id", "cert-manager-acme-environment",
];

// All credential-free validation errors.
export function stateErrors(opts: Opts): string[] {
  const errors: string[] = [];
  for (const key of missing(opts, [...requiredKeys, ...slotKeys(opts, "required")])) {
    errors.push(`:${key} is required`);
  }
  for (const slot of slots) {
    if (!entry(opts, slot)) {
      errors.push(`unsupported :${slot} ${prStr(opts[slot])}`);
    }
  }
  if (opts["provider-compute"] !== "digitalocean") {
    errors.push(":provider-compute must be digitalocean");
  }
  if (opts["kubernetes-distribution"] !== "kubeadm") {
    errors.push(":kubernetes-distribution must be kubeadm");
  }
  if (opts["kubernetes-cni"] !== "flannel") {
    errors.push(":kubernetes-cni must be flannel");
  }
  if (opts["control-plane-count"] !== 1) {
    errors.push(":control-plane-count must be 1");
  }
  if (opts["worker-count"] !== 1) {
    errors.push(":worker-count must be 1");
  }
  if (opts["digitalocean-cloud-controller"] !== true) {
    errors.push(":digitalocean-cloud-controller must be true");
  }
  if (typeof opts["compute-prevent-destroy"] !== "boolean") {
    errors.push(":compute-prevent-destroy must be true or false");
  }
  if (!(placeholder(opts.profile) || profileRe.test(String(opts.profile)))) {
    errors.push(":profile must be a safe 1-63 character name");
  }
  for (const key of ["kubernetes-version", "flannel-version", "flux-version",
                     "digitalocean-cloud-controller-version"]) {
    const value = opts[key];
    if (!placeholder(value) && !semverRe.test(String(value))) {
      errors.push(`:${key} must be an exact vMAJOR.MINOR.PATCH release`);
    }
  }
  if (!placeholder(opts.repository) && !httpsGitRe.test(String(opts.repository))) {
    errors.push(":repository must be a public HTTPS Git URL");
  }
  if (!(placeholder(opts["repository-branch"]) || branchRe.test(String(opts["repository-branch"])))) {
    errors.push(":repository-branch contains unsupported characters");
  }
  if (!(placeholder(opts["repository-path"]) || pathRe.test(String(opts["repository-path"])))) {
    errors.push(":repository-path must begin with ./");
  }
  for (const key of ["application-host", "cloudflare-zone"]) {
    const value = opts[key];
    if (opts["provider-dns"] === "cloudflare" && !placeholder(value) &&
        !dnsRe.test(String(value))) {
      errors.push(`:${key} must be a DNS name`);
    }
  }
  if (opts["provider-dns"] === "cloudflare" &&
      !placeholder(opts["application-host"]) &&
      !placeholder(opts["cloudflare-zone"]) &&
      !String(opts["application-host"]).endsWith(`.${opts["cloudflare-zone"]}`)) {
    errors.push(":application-host must be below :cloudflare-zone");
  }
  for (const key of ["kubernetes-pod-cidr", "kubernetes-service-cidr",
                     "digitalocean-vpc-cidr"]) {
    const value = opts[key];
    if (!placeholder(value) && !validCidr(value)) {
      errors.push(`:${key} must be a valid IPv4 CIDR`);
    }
  }
  for (const key of ["digitalocean-ssh-sources", "digitalocean-api-sources"]) {
    const values = opts[key];
    if (!placeholder(values) &&
        (!Array.isArray(values) || values.length === 0 ||
         values.some((value) => !validCidr(value)))) {
      errors.push(`:${key} must be a non-empty list of IPv4 CIDRs`);
    }
  }
  if (!["production", "staging"].includes(String(opts["cert-manager-acme-environment"]))) {
    errors.push(":cert-manager-acme-environment must be production or staging");
  }
  return errors;
}

// Credentials required by the selected providers.
export function secretErrors(opts: Opts, selected: string[] = slots): string[] {
  const keys = selected.flatMap((slot) => entry(opts, slot)?.secrets ?? []);
  return [...new Set(missing(opts, keys))]
    .map((key) => `required credential is not set: ${parName(key)}`);
}
