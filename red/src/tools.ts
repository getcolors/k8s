// DigitalOcean infrastructure, kubeadm Ansible, and acceptance stages, the
// port of io.github.getcolors.k8s.tools.

import * as ansible from "red/ansible";
import { toolEnv } from "red/providers";
import { PRESERVE_JINJA_DELIMITERS, contentSpec, scaffold, type Spec, type Template } from "red/scaffold";
import * as tofu from "red/tofu";
import { runtime, type ExecResult } from "red/runtime";
import type { Opts } from "red/workflow";
import { StepError, failed } from "red/workflow";
import * as utils from "./utils.ts";
import * as validate from "./validate.ts";

import acceptanceSh from "../resources/tools/acceptance/acceptance.sh" with { type: "text" };
import ansibleLocalCfg from "../resources/tools/ansible-local/ansible.cfg" with { type: "text" };
import ansibleLocalInventory from "../resources/tools/ansible-local/inventory.ini" with { type: "text" };
import ansibleLocalMain from "../resources/tools/ansible-local/main.yml" with { type: "text" };
import ansibleRemoteCfg from "../resources/tools/ansible-remote/ansible.cfg" with { type: "text" };
import ansibleRemoteCreate from "../resources/tools/ansible-remote/create.yml" with { type: "text" };
import ansibleRemoteDelete from "../resources/tools/ansible-remote/delete.yml" with { type: "text" };
import ansibleRemoteGitops from "../resources/tools/ansible-remote/gitops.yml" with { type: "text" };
import infrastructureMain from "../resources/tools/infrastructure/main.tf" with { type: "text" };

export const infrastructureTool = "k8s-infrastructure";
export const ansibleLocalTool = "k8s-ansible-local";
export const ansibleRemoteTool = "k8s-ansible-remote";
export const acceptanceTool = "k8s-acceptance";
export const tofuTools = [infrastructureTool];

export const templateOpts = PRESERVE_JINJA_DELIMITERS;

export const toolDir = utils.toolDir;

// The template tree this colour carries, keyed the way green names its
// classpath resources: "<path>/<file>" with dots as directories.
const templates: Record<string, string> = {
  "acceptance/acceptance.sh": acceptanceSh,
  "ansible-local/ansible.cfg": ansibleLocalCfg,
  "ansible-local/inventory.ini": ansibleLocalInventory,
  "ansible-local/main.yml": ansibleLocalMain,
  "ansible-remote/ansible.cfg": ansibleRemoteCfg,
  "ansible-remote/create.yml": ansibleRemoteCreate,
  "ansible-remote/delete.yml": ansibleRemoteDelete,
  "ansible-remote/gitops.yml": ansibleRemoteGitops,
  "infrastructure/main.tf": infrastructureMain,
};

export function template(path: string, file: string): Template {
  const name = `${path.replaceAll(".", "/")}/${file}`;
  const content = templates[name];
  if (content === undefined) throw new StepError(`template not found: ${name}`);
  return { name, content };
}

function spec(source: Template, target: string, data: Opts): Spec {
  return { template: source, target, data, opts: templateOpts };
}

const rawSpec = (target: string, content: string): Spec => contentSpec(target, content);

// Provider and backend environment additions, omitting absent credentials.
export function credentialEnv(opts: Opts, ...slots: string[]): Record<string, string> | undefined {
  return toolEnv(validate.providers, opts, [...slots, "provider-backend"]);
}

export function infrastructureSpecs(opts: Opts): Spec[] {
  const dir = toolDir(opts, infrastructureTool);
  const data: Opts = {
    ...opts,
    "digitalocean-ssh-sources-json": JSON.stringify(opts["digitalocean-ssh-sources"]),
    "digitalocean-api-sources-json": JSON.stringify(opts["digitalocean-api-sources"]),
  };
  return [spec(template("infrastructure", "main.tf"), `${dir}/main.tf`, data)];
}

export const fallbackOutputs: Opts = {
  digitalocean_vpc_id: "00000000-0000-0000-0000-000000000000",
  control_plane_public_ip: "192.168.0.10",
  control_plane_private_ip: "10.20.0.10",
  worker_public_ips: ["192.168.0.11"],
  worker_private_ips: ["10.20.0.11"],
};

function outputMap(result: Opts): Opts | undefined {
  return result["k8s/outputs"] as Opts | undefined;
}

export async function infrastructureStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, infrastructureTool);
  const result = await tofu.tofuWithSpec(opts, infrastructureSpecs(opts), {
    dir,
    env: credentialEnv(opts, "provider-compute"),
    outputKey: "k8s/outputs",
  });
  if (failed(result)) return result;
  if (opts["red/event"] === "delete") return result;
  if (opts["red/event"] === "build") return { ...result, ...fallbackOutputs };
  return { ...result, ...fallbackOutputs, ...(outputMap(result) ?? {}) };
}

// Load node addresses from remote state without planning or changing cloud
// resources.
export async function loadInfrastructureStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, infrastructureTool);
  const rendered: Opts = {
    ...scaffold({ ...opts, "red/event": "build" }, infrastructureSpecs(opts)),
    "red/event": opts["red/event"],
  };
  const env = credentialEnv(opts, "provider-compute");
  const init = await runtime.exec(
    ["tofu", `-chdir=${dir}`, "init", "-input=false", "-no-color"], { env });
  if (init.exit !== 0) {
    return processResult(rendered, "infrastructure state initialization", init);
  }
  try {
    const outputs = await tofu.outputs(dir, env);
    return {
      ...rendered, ...fallbackOutputs, ...outputs,
      "k8s/infrastructure-present?": "control_plane_public_ip" in outputs,
    };
  } catch (t) {
    return {
      ...rendered, "red/exit": 1,
      "red/err": "infrastructure state output failed: " +
        (t instanceof Error ? t.message || t.constructor.name : String(t)),
    };
  }
}

// Complete deterministic template data for build as well as create.
export function dataFn(opts: Opts): Opts {
  return {
    ...fallbackOutputs,
    ...opts,
    "host-alias": utils.hostAlias(opts),
    "kubernetes-minor": utils.kubernetesMinor(opts["kubernetes-version"]),
    "kubernetes-package-version":
      utils.kubernetesPackageVersion(opts["kubernetes-version"]),
  };
}

// Java's Double.toString, which is what Cheshire renders floats through and
// therefore what green's committed inventory bytes would carry. Integral
// numbers print as longs. JS's shortest-round-trip digits are the same digits
// Java chooses; only the layout differs.
function javaNumber(value: number): string {
  if (Number.isInteger(value)) return String(value);
  const negative = value < 0;
  const [mantissa, exponentPart] = Math.abs(value).toExponential().split("e");
  const exponent = Number(exponentPart);
  const digits = mantissa!.replace(".", "");
  let body: string;
  if (exponent >= -3 && exponent < 7) {
    if (exponent >= 0) {
      const intPart = digits.padEnd(exponent + 1, "0").slice(0, exponent + 1);
      const fracPart = digits.slice(exponent + 1);
      body = `${intPart}.${fracPart.length > 0 ? fracPart : "0"}`;
    } else {
      body = `0.${"0".repeat(-exponent - 1)}${digits}`;
    }
  } else {
    const rest = digits.slice(1);
    body = `${digits[0]}.${rest.length > 0 ? rest : "0"}E${exponent}`;
  }
  return negative ? `-${body}` : body;
}

// Cheshire's pretty printer, byte for byte: spaces around colons, arrays
// inline, nested objects newline-indented, floats in Java notation.
function pretty(value: unknown, indent = 0): string {
  if (Array.isArray(value)) {
    if (value.length === 0) return "[ ]";
    return `[ ${value.map((item) => pretty(item, indent)).join(", ")} ]`;
  }
  if (value !== null && typeof value === "object") {
    const entries = Object.entries(value);
    if (entries.length === 0) return "{ }";
    const pad = " ".repeat(indent + 2);
    return `{\n${entries
      .map(([key, nested]) => `${pad}${JSON.stringify(key)} : ${pretty(nested, indent + 2)}`)
      .join(",\n")}\n${" ".repeat(indent)}}`;
  }
  if (typeof value === "number") return javaNumber(value);
  return JSON.stringify(value ?? null);
}

// JSON inventory separating the control plane from the workers.
export function inventory(opts: Opts): string {
  const data = dataFn(opts);
  const cpName = `${data["digitalocean-name"]}-control-plane-1`;
  const publicIps = (data.worker_public_ips ?? []) as unknown[];
  const privateIps = (data.worker_private_ips ?? []) as unknown[];
  const workers = publicIps
    .slice(0, Math.min(publicIps.length, privateIps.length))
    .map((publicIp, index) => [
      `${data["digitalocean-name"]}-worker-${index + 1}`,
      { ansible_host: publicIp, ansible_user: "root", private_ip: privateIps[index] },
    ] as const);
  const sorted = [...workers].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
  return pretty({
    all: {
      children: {
        control_plane: {
          hosts: {
            [cpName]: {
              ansible_host: data.control_plane_public_ip,
              ansible_user: "root",
              private_ip: data.control_plane_private_ip,
            },
          },
        },
        workers: { hosts: Object.fromEntries(sorted) },
        k8s_cluster: { children: { control_plane: {}, workers: {} } },
      },
    },
  });
}

export function ansibleLocalSpecs(opts: Opts): Spec[] {
  const dir = toolDir(opts, ansibleLocalTool);
  const data = dataFn(opts);
  return [
    spec(template("ansible-local", "ansible.cfg"), `${dir}/ansible.cfg`, data),
    spec(template("ansible-local", "inventory.ini"), `${dir}/inventory.ini`, data),
    spec(template("ansible-local", "main.yml"), `${dir}/main.yml`, data),
  ];
}

export async function ansibleLocalStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, ansibleLocalTool);
  const data = dataFn(opts);
  const isDelete = opts["red/event"] === "delete";
  return ansible.ansibleWithSpec(opts, {
    dir,
    inventory: "inventory.ini",
    playbooks: { create: "main.yml", delete: "main.yml" },
    extraVars: {
      host_alias: data["host-alias"],
      ip: data.control_plane_public_ip,
      block_state: isDelete ? "absent" : "present",
    },
  }, ansibleLocalSpecs(opts));
}

export function ansibleRemoteSpecs(opts: Opts): Spec[] {
  const dir = toolDir(opts, ansibleRemoteTool);
  const data = dataFn(opts);
  return [
    spec(template("ansible-remote", "ansible.cfg"), `${dir}/ansible.cfg`, data),
    spec(template("ansible-remote", "create.yml"), `${dir}/create.yml`, data),
    spec(template("ansible-remote", "delete.yml"), `${dir}/delete.yml`, data),
    spec(template("ansible-remote", "gitops.yml"), `${dir}/gitops.yml`, data),
    rawSpec(`${dir}/inventory.json`, inventory(data)),
  ];
}

export async function ansibleRemoteStep(opts: Opts): Promise<Opts> {
  if (opts["red/event"] === "delete" && opts["k8s/infrastructure-present?"] === false) {
    return opts;
  }
  const dir = toolDir(opts, ansibleRemoteTool);
  return ansible.ansibleWithSpec(opts, {
    dir,
    inventory: "inventory.json",
    playbooks: { create: "create.yml", delete: "delete.yml" },
    hostKeyChecking: false,
  }, ansibleRemoteSpecs(opts));
}

export function acceptanceSpecs(opts: Opts): Spec[] {
  const dir = toolDir(opts, acceptanceTool);
  return [spec(template("acceptance", "acceptance.sh"),
               `${dir}/acceptance.sh`, dataFn(opts))];
}

export function processResult(opts: Opts, label: string, { exit, out, err }: ExecResult): Opts {
  if (exit === 0) return { ...opts, "red/exit": 0 };
  return {
    ...opts,
    "red/exit": Math.max(1, exit),
    "red/err": `${label} failed: ${err || out || "(no output)"}`,
  };
}

export async function acceptanceStep(opts: Opts): Promise<Opts> {
  const rendered = scaffold(opts, acceptanceSpecs(opts));
  if (opts["red/event"] === "build" || opts["red/event"] === "delete") return rendered;
  return processResult(
    rendered, "acceptance",
    await runtime.exec(["bash", `${toolDir(opts, acceptanceTool)}/acceptance.sh`],
                       { timeoutMs: 25 * 60 * 1000 }));
}

export async function generatedCleanupStep(opts: Opts): Promise<Opts> {
  let result = scaffold(opts, ansibleLocalSpecs(opts));
  result = scaffold(result, ansibleRemoteSpecs(opts));
  return scaffold(result, acceptanceSpecs(opts));
}
