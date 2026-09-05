// DigitalOcean infrastructure, kubeadm Ansible, and acceptance stages, the
// port of io.github.getcolors.k8s.tools.

import * as ansible from "red/ansible";
import { toolEnv } from "red/providers";
import { PRESERVE_JINJA_DELIMITERS, contentSpec, scaffold, type Spec, type Template } from "red/scaffold";
import * as tofu from "red/tofu";
import { runtime, type ExecResult } from "red/runtime";
import type { Opts } from "red/workflow";
import { StepError, failed } from "red/workflow";
import { compute, computeCluster } from "package-once-red";
import * as ssh from "./ssh.ts";
import * as sshConfig from "./ssh-config.ts";
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
  // The machine-key paths are filled here as well as in preflight, so the
  // template renders the same bytes whichever step scaffolds it.
  opts = ssh.withMachineKey(opts);
  const dir = toolDir(opts, infrastructureTool);
  const data: Opts = {
    ...opts,
    "digitalocean-ssh-sources-json": JSON.stringify(opts["digitalocean-ssh-sources"]),
    "digitalocean-api-sources-json": JSON.stringify(opts["digitalocean-api-sources"]),
  };
  return [spec(template("infrastructure", "main.tf"), `${dir}/main.tf`, data)];
}

// What `build` and `--dry-run` render as the VPC id: the compute stage owns
// the real one, recorded as `params.vpc_id`.
export const fallbackVpcId = "00000000-0000-0000-0000-000000000000";

// What this package calls a node — `<name>-<role>-<ordinal>`, 1-based, the
// rule the template gives the droplets. This is the package's own naming, kept
// over ONCE's fallback rule (Compute Cluster Standard §5, adoption renames
// nothing), and the name the legacy translation gives a node a pre-adoption
// state recorded without one.
export function nodeName(opts: Opts, role: string, index: number): string {
  return `${opts["digitalocean-name"]}-${role}-${index + 1}`;
}

// The cluster's nodes in declared order — ONCE's `nodes` over the adopted
// `once/cluster`: every field from state on a real run, the fallbacks on a
// build, with their names overridden to this package's own.
export function nodes(opts: Opts): computeCluster.Node[] {
  const cluster = opts["once/cluster"] as computeCluster.ClusterParams | undefined;
  const result = computeCluster.nodes(validate.spec, opts, cluster);
  if (cluster === undefined || cluster === null) {
    return result.map((n) => ({ ...n, name: nodeName(opts, String(n.role), n.index) }));
  }
  return result;
}

// The address the bare `<profile>` alias points to: the control plane's, as
// ONCE's `sshConfigHosts` resolves the spec's `entry`.
export function entryIp(opts: Opts): unknown {
  return computeCluster.sshConfigHosts(validate.spec, opts, nodes(opts))[0]?.ip;
}

const nonBlank = (x: unknown): boolean => typeof x === "string" && x.trim().length > 0;

// The extension key this package puts inside `params` beside ONCE's: `vpc_id`,
// the deployment-owned VPC the cloud controller is told about. A real run is
// refused without it.
export function paramsErrors(params: Opts | undefined): string[] {
  return nonBlank(params?.vpc_id) ? [] : ["compute state carries no vpc_id"];
}

// After `resolvedCluster` or `adoptState`: this package's `paramsErrors` over
// the adopted cluster, when there is one.
function withParamsCheck(opts: Opts): Opts {
  const cluster = opts["once/cluster"] as Opts | undefined;
  if (failed(opts) || cluster === undefined || cluster === null) return opts;
  const errors = paramsErrors(cluster);
  return errors.length > 0 ? { ...opts, "red/exit": 1, "red/err": errors.join("\n") } : opts;
}

// The `params` a pre-adoption state describes. Before this package recorded
// `params`, its template output a scalar control plane
// (`control_plane_public_ip`, `control_plane_private_ip`) and two parallel
// worker lists; this builds control-plane node 0 from the scalars and worker i
// from the lists, names them by this package's own rule, and carries `vpc_id`
// from `digitalocean_vpc_id`. Refused — as the SDK's `StepError`, so
// `readState` reports it and a delete fails closed — when the two lists
// disagree or the VPC id is absent or blank. Nothing else reads a legacy
// output after adoption.
export function legacyParams(opts: Opts, outputs: Record<string, unknown>): computeCluster.ClusterParams {
  const publics = Array.isArray(outputs.worker_public_ips) ? outputs.worker_public_ips : [];
  const privates = Array.isArray(outputs.worker_private_ips) ? outputs.worker_private_ips : [];
  const vpcId = outputs.digitalocean_vpc_id;
  if (publics.length !== privates.length) {
    throw new StepError(`legacy state lists ${publics.length} worker public addresses and ` +
      `${privates.length} private addresses; refusing to guess the cluster`);
  }
  if (!nonBlank(vpcId)) throw new StepError("legacy state carries no digitalocean_vpc_id");
  const node = (index: number, role: string, ip: unknown, vpcIp: unknown): computeCluster.Node => ({
    index, role, name: nodeName(opts, role, index),
    ip: ip as string, vpc_ip: vpcIp as string, user: "root", sudoer: "root",
  });
  return {
    provider: "digitalocean",
    vpc_id: vpcId,
    nodes: [
      node(0, "control-plane", outputs.control_plane_public_ip, outputs.control_plane_private_ip),
      ...publics.map((ip, i) => node(i, "worker", ip, privates[i])),
    ],
  } as computeCluster.ClusterParams;
}

// The reader ONCE's `readState` takes: the compute `params` recorded in the
// infrastructure state, undefined when the state holds no outputs at all, and
// the legacy translation above when it holds the pre-adoption outputs. The
// stage is initialised first so remote state is reachable without planning or
// changing cloud resources. An unreadable backend — a failed init, or whatever
// `red/tofu` throws — is the SDK's `StepError`, which `readState` turns into
// `{ error }`; create and delete treat that differently. Injectable into the
// steps so tests never shell out to tofu.
export async function stateOutput(opts: Opts): Promise<computeCluster.ClusterParams | undefined> {
  const dir = toolDir(opts, infrastructureTool);
  const env = credentialEnv(opts, "provider-compute");
  const init = await runtime.exec(
    ["tofu", `-chdir=${dir}`, "init", "-input=false", "-no-color"], { env });
  if (init.exit !== 0) {
    throw new StepError(`tofu init failed: ${init.err || init.out || "(no output)"}`);
  }
  const outputs = await tofu.outputs(dir, env);
  if ("params" in outputs) {
    const params = outputs.params;
    return params && typeof params === "object" ? params as computeCluster.ClusterParams : undefined;
  }
  if (Object.keys(outputs).length === 0) return undefined;
  return legacyParams(opts, outputs);
}

// DigitalOcean's answer when a VPC is deleted while it still counts members.
export const vpcMembersError = /Can not delete VPC with members/;

// How often a destroy is retried on `vpcMembersError`, and how long it waits
// between attempts. Mutable so a test can shorten the wait.
export const destroyRetry = { attempts: 4, delayMs: 30000 };

// Run a destroy, retrying the DigitalOcean VPC race. Droplets are deleted
// asynchronously, and a destroy that reaches the deployment-owned VPC seconds
// later is refused with 409 `Can not delete VPC with members` — a race the
// next attempt wins once the members have drained (seen live on 2026-09-05).
// Only that message is retried; every other failure is reported as is, on the
// first attempt.
export async function destroyWithDrain(run: () => Promise<Opts>): Promise<Opts> {
  for (let attempt = 1; ; attempt++) {
    const result = await run();
    if (failed(result) && vpcMembersError.test(String(result["red/err"] ?? "")) &&
        attempt < destroyRetry.attempts) {
      await new Promise((resolve) => setTimeout(resolve, destroyRetry.delayMs));
      continue;
    }
    return result;
  }
}

export async function infrastructureStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, infrastructureTool);
  const run = () => tofu.tofuWithSpec(opts, infrastructureSpecs(opts), {
    dir,
    env: credentialEnv(opts, "provider-compute"),
  });
  const result = opts["red/event"] === "delete" ? await destroyWithDrain(run) : await run();
  if (failed(result)) return result;
  if (opts["red/event"] === "delete" || opts["red/event"] === "build") return result;
  // A real converge never falls back: nil outputs and a partial cluster are
  // refused by ONCE, then the VPC id by this package.
  return withParamsCheck(computeCluster.resolvedCluster(
    validate.spec, opts, result, {}, computeCluster.outputParams(result)));
}

// Adopt the cluster from remote state without planning or changing cloud
// resources: ONCE's `readState` over the reader, then `adoptState`, which
// fails closed on an unreadable backend and refuses a partial cluster, then
// this package's `paramsErrors`. A readable state holding no compute leaves
// `once/cluster` absent, and the remote cleanup skips itself.
export async function loadInfrastructureStep(
  opts: Opts,
  reader: compute.StateReader = stateOutput,
): Promise<Opts> {
  const rendered: Opts = {
    ...scaffold({ ...opts, "red/event": "build" }, infrastructureSpecs(opts)),
    "red/event": opts["red/event"],
  };
  const state = await computeCluster.readState(rendered, reader);
  return withParamsCheck(
    computeCluster.adoptState(validate.spec, rendered, String(opts["red/event"]), state));
}

// Complete deterministic template data for build as well as create.
export function dataFn(opts: Opts): Opts {
  opts = ssh.withMachineKey(opts);
  const cluster = opts["once/cluster"] as Opts | undefined;
  return {
    ...opts,
    digitalocean_vpc_id: cluster?.vpc_id ?? fallbackVpcId,
    "host-alias": utils.hostAlias(opts),
    // Only what a `build` genuinely knows: whether the package owns the key,
    // and where the local play should point the identity file.
    "ssh-keygen": validate.keygen(opts),
    "ssh-config-identity-file": sshConfig.identityFile(opts),
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

// The remote play's inventory: the control plane and the workers, each node
// under its own name, from `nodes`.
export function inventory(opts: Opts): string {
  opts = ssh.withMachineKey(opts);
  const all = nodes(opts);
  // In keygen mode nothing guarantees an agent holds the generated key, so the
  // play is told which one to use; opt-out keeps the operator's own
  // arrangements, as it always did.
  const host = (n: computeCluster.Node) =>
    ({ ansible_host: n.ip, ansible_user: n.user, private_ip: n.vpc_ip,
       ...(validate.keygen(opts) ? { ansible_ssh_private_key_file: opts["ssh-private-key-path"] } : {}) });
  const hosts = (role: string) => Object.fromEntries(
    all.filter((n) => n.role === role)
      .map((n) => [n.name, host(n)] as const)
      .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)));
  return pretty({
    all: {
      children: {
        control_plane: { hosts: hosts("control-plane") },
        workers: { hosts: hosts("worker") },
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
      ip: entryIp(opts),
      user: "root",
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
    rawSpec(`${dir}/inventory.json`, inventory(opts)),
  ];
}

// The remote play. On a delete it addresses the adopted cluster; a state that
// recorded no compute — the nodes are already gone — has nothing to clean up,
// and the step skips itself rather than render the fallbacks.
export async function ansibleRemoteStep(opts: Opts): Promise<Opts> {
  if (opts["red/event"] === "delete" && opts["once/cluster"] == null) {
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
