import { describe, expect, test } from "bun:test";
import { existsSync, mkdtempSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { scaffold } from "red/scaffold";
import { runtime } from "red/runtime";
import { StepError, run as runWorkflow, type Opts } from "red/workflow";
import { computeCluster } from "package-once-red";
import * as operator from "../src/operator.ts";
import * as ssh from "../src/ssh.ts";
import * as sshConfig from "../src/ssh-config.ts";
import * as tools from "../src/tools.ts";
import * as validate from "../src/validate.ts";
import * as workflow from "../src/workflow.ts";

function tempDir(): string {
  return mkdtempSync(join(tmpdir(), "k8s-test-"));
}

const base: Opts = {
  profile: "k8s-test",
  workdir: ".colors",
  "provider-compute": "digitalocean",
  "provider-dns": "cloudflare",
  "provider-backend": "local",
  "compute-prevent-destroy": true,
  "kubernetes-distribution": "kubeadm",
  "kubernetes-version": "v1.36.3",
  "kubernetes-cni": "flannel",
  "flannel-version": "v0.28.8",
  "kubernetes-pod-cidr": "10.244.0.0/16",
  "kubernetes-service-cidr": "10.96.0.0/12",
  "control-plane-count": 1,
  "worker-count": 1,
  "flux-version": "v2.9.3",
  "digitalocean-cloud-controller-version": "v0.1.68",
  "digitalocean-cloud-controller": true,
  repository: "https://github.com/getcolors/k8s-helloworld.git",
  "repository-branch": "main",
  "repository-path": "./clusters/k8s-digitalocean",
  "digitalocean-name": "k8s-test",
  "digitalocean-region": "ams3",
  "digitalocean-control-plane-size": "s-2vcpu-4gb",
  "digitalocean-worker-size": "s-2vcpu-4gb",
  "digitalocean-image": "ubuntu-24-04-x64",
  "digitalocean-vpc-cidr": "10.20.0.0/20",
  "digitalocean-ssh-sources": ["203.0.113.10/32"],
  "digitalocean-api-sources": ["203.0.113.10/32"],
  "application-host": "hello.example.com",
  "cloudflare-zone": "example.com",
  "external-dns-owner-id": "k8s-test",
  "cert-manager-acme-environment": "production",
};

// The opt-out twin: an operator-registered key, by id or fingerprint.
const optout: Opts = { ...base, "digitalocean-ssh-keys": "fingerprint" };

const matching = (opts: Opts, re: RegExp): string[] =>
  validate.stateErrors(opts).filter((e) => re.test(e));

// A recorded `params`, as the compute stage outputs it after adoption.
const cluster: computeCluster.ClusterParams = {
  provider: "digitalocean",
  vpc_id: "9c0a1b2c-3d4e-4f60-8a7b-1c2d3e4f5a6b",
  nodes: [
    { index: 0, role: "control-plane", name: "k8s-test-control-plane-1",
      ip: "203.0.113.1", vpc_ip: "10.20.0.2", user: "root", sudoer: "root" },
    { index: 0, role: "worker", name: "k8s-test-worker-1",
      ip: "203.0.113.2", vpc_ip: "10.20.0.3", user: "root", sudoer: "root" },
  ],
};

// The pre-adoption state, in the recorded scalar-plus-list shape.
const legacyOutputs: Record<string, unknown> = JSON.parse(
  readFileSync(join(import.meta.dir, "../../test/fixtures/legacy-outputs.json"), "utf8"));

const withoutNodes = (params: computeCluster.ClusterParams, keep: number[]) =>
  ({ ...params, nodes: params.nodes!.filter((_n, i) => keep.includes(i)) });

// The shape `red/tofu` throws: the SDK's StepError. Only that is an
// unreadable backend; anything else propagates as a defect.
const unreadable = async () => { throw new StepError("tofu output failed: no backend"); };

// --- validate ----------------------------------------------------------------

describe("validate", () => {
  test("complete state is valid", () => {
    expect(validate.stateErrors(base)).toEqual([]);
  });

  test("both keypair modes are renderable and the old key name is refused", () => {
    // The SSH Keypair Standard has two modes and conformance means both hold.
    expect(validate.stateErrors(optout)).toEqual([]);
    expect(validate.keygen(base)).toBe(true);
    expect(validate.keygen(optout)).toBe(false);
    // The machine key is never required: its absence is keygen mode.
    expect(validate.stateErrors(base).some((e) => e.includes("digitalocean-ssh-keys"))).toBe(false);
    // The one desired-state migration: the key moved to the standard's name.
    expect(validate.stateErrors({ ...base, "digitalocean-ssh-key-fingerprint": "fingerprint" }))
      .toContain(":digitalocean-ssh-key-fingerprint is now :digitalocean-ssh-keys; rename it in colors.yml, or leave it out so the deployment owns its keypair");
  });

  test("reports all missing and invalid values", () => {
    const { repository, "digitalocean-region": _region, ...rest } = base;
    const errors = validate.stateErrors({
      ...rest,
      "kubernetes-version": "latest",
      "worker-count": 3,
      "digitalocean-ssh-sources": ["world"],
    });
    expect(errors.length).toBeGreaterThanOrEqual(5);
    expect(errors.some((e) => e.includes(":repository"))).toBe(true);
    expect(errors.some((e) => e.includes(":digitalocean-region"))).toBe(true);
  });

  test("package is kubeadm, flannel, digitalocean", () => {
    expect(matching({ ...base, "provider-compute": "hcloud" }, /digitalocean/).length)
      .toBeGreaterThan(0);
    expect(matching({ ...base, "kubernetes-distribution": "talos" }, /kubeadm/).length)
      .toBeGreaterThan(0);
    expect(matching({ ...base, "kubernetes-cni": "cilium" }, /flannel/).length)
      .toBeGreaterThan(0);
  });

  test("topology and cidrs are restricted", () => {
    expect(matching({ ...base, "control-plane-count": 3 }, /control-plane-count/).length)
      .toBeGreaterThan(0);
    expect(matching({ ...base, "digitalocean-api-sources": ["0.0.0.0/99"] }, /api-sources/).length)
      .toBeGreaterThan(0);
  });

  test("compute checks are the standard's, in ONCE's words", () => {
    // The source lists, the owned VPC's CIDR and the selection are ONCE's
    // checks over `spec`; the package no longer words them itself.
    expect(matching({ ...base, "digitalocean-api-sources": ["world"] }, /api-sources/))
      .toEqual([':digitalocean-api-sources entry "world" is not an IPv4 or IPv6 CIDR']);
    expect(matching({ ...base, "digitalocean-ssh-sources": [] }, /ssh-sources/))
      .toEqual([":digitalocean-ssh-sources must list at least one CIDR"]);
    expect(matching({ ...base, "digitalocean-vpc-cidr": "10.20.0.1/20" }, /vpc-cidr/))
      .toEqual([":digitalocean-vpc-cidr must be a canonical IPv4 network such as 10.40.0.0/24"]);
    const { "digitalocean-vpc-cidr": _cidr, ...withoutCidr } = base;
    expect(matching(withoutCidr, /vpc-cidr/)).toEqual([":digitalocean-vpc-cidr is required"]);
    expect(matching({ ...base, "provider-compute": "hcloud" }, /provider-compute/))
      .toEqual([":provider-compute must be one of digitalocean"]);
    // A created network is this package's to own: compute's DigitalOcean
    // "must not create a VPC" refusal is filtered, never reported.
    expect(matching(base, /must be absent/)).toEqual([]);
  });

  test("spec content is the two-role topology", () => {
    expect(computeCluster.specErrors(validate.spec)).toEqual([]);
    expect(validate.spec.roles.map((r) => r.role)).toEqual(["control-plane", "worker"]);
    expect(validate.spec.roles.map((r) => r.count)).toEqual([1, 1]);
    expect(validate.spec.roles.map((r) => r.countKey)).toEqual(["control-plane-count", "worker-count"]);
    expect(validate.spec.entry).toEqual({ role: "control-plane", index: 0 });
    expect(validate.spec.registry.digitalocean!.network)
      .toEqual({ mode: "created", key: "digitalocean-vpc-cidr" });
    expect(validate.spec.default).toBe("digitalocean");
    expect(validate.spec.sources).toEqual({ nonEmpty: ["ssh-sources", "api-sources"], mayBeEmpty: [] });
    expect("fallbackSubnet" in validate.spec).toBe(false);
    expect(computeCluster.topologyErrors(validate.spec, base)).toEqual([]);
    expect(computeCluster.aliases(validate.spec, base))
      .toEqual(["k8s-test", "k8s-test-control-plane", "k8s-test-worker"]);
  });

  test("secret errors use COLORS variables", () => {
    const text = validate.secretErrors(base).join("\n");
    expect(text).toContain("COLORS_PAR_DO_TOKEN");
    expect(text).toContain("COLORS_PAR_CLOUDFLARE_API_TOKEN");
    expect(validate.secretErrors({
      ...base, "do-token": "x", "cloudflare-api-token": "y",
    })).toEqual([]);
  });

  test("profile overlay is always refused", () => {
    expect(validate.envErrors({ COLORS_PAR_PROFILE: "other" })[0])
      .toContain("COLORS_PAR_PROFILE");
  });
});

// --- tools -------------------------------------------------------------------

describe("tools", () => {
  test("stage names are package specific", () => {
    expect(tools.infrastructureTool).toBe("k8s-infrastructure");
    expect(tools.ansibleRemoteTool).toBe("k8s-ansible-remote");
  });

  test("inventory separates control plane and worker", () => {
    const parsed = JSON.parse(tools.inventory({ ...base, "once/cluster": cluster }));
    expect(parsed.all.children.control_plane.hosts["k8s-test-control-plane-1"].private_ip)
      .toBe("10.20.0.2");
    expect(parsed.all.children.workers.hosts["k8s-test-worker-1"].ansible_host)
      .toBe("203.0.113.2");
  });

  test("the inventory names the generated key in keygen mode only", () => {
    // On a build the placeholder; opt-out keeps the operator's own arrangements.
    const built = JSON.parse(tools.inventory({ ...base, "once/cluster": cluster, "red/event": "build" }));
    expect(built.all.children.workers.hosts["k8s-test-worker-1"].ansible_ssh_private_key_file)
      .toBe("/home/build-placeholder/.ssh/k8s-test");
    const optedOut = JSON.parse(tools.inventory({ ...optout, "once/cluster": cluster }));
    expect("ansible_ssh_private_key_file" in optedOut.all.children.workers.hosts["k8s-test-worker-1"]).toBe(false);
    // The local play is told the identity file the same way.
    expect((tools.ansibleLocalSpecs({ ...base, "red/event": "build" })[0]!.data as Opts)["ssh-config-identity-file"])
      .toBe("~/.ssh/k8s-test");
    expect((tools.ansibleLocalSpecs(optout)[0]!.data as Opts)["ssh-keygen"]).toBe(false);
  });

  test("build renders fallback nodes under the package's own names", () => {
    // No adopted cluster: ONCE's fallbacks on TEST-NET-1 and the owned VPC's
    // CIDR, named the way the template names the droplets.
    expect(tools.nodes(base)).toEqual([
      { role: "control-plane", index: 0, name: "k8s-test-control-plane-1",
        ip: "192.0.2.10", vpc_ip: "10.20.0.10", user: "root", sudoer: "root" },
      { role: "worker", index: 0, name: "k8s-test-worker-1",
        ip: "192.0.2.11", vpc_ip: "10.20.0.11", user: "root", sudoer: "root" },
    ]);
    expect(tools.entryIp(base)).toBe("192.0.2.10");
    expect(tools.entryIp({ ...base, "once/cluster": cluster })).toBe("203.0.113.1");
    expect(tools.dataFn(base).digitalocean_vpc_id).toBe("00000000-0000-0000-0000-000000000000");
    expect(tools.dataFn({ ...base, "once/cluster": cluster }).digitalocean_vpc_id).toBe(cluster.vpc_id);
  });

  test("params errors require the vpc id", () => {
    const { vpc_id: _id, ...withoutVpc } = cluster;
    expect(tools.paramsErrors(withoutVpc)).toEqual(["compute state carries no vpc_id"]);
    expect(tools.paramsErrors({ ...cluster, vpc_id: " " })).toEqual(["compute state carries no vpc_id"]);
    expect(tools.paramsErrors(cluster)).toEqual([]);
  });

  // `stateOutput` over stubbed tofu: an init that succeeds and an output read
  // returning `outputs`, in `tofu output -json` form.
  async function readState(outputs: Record<string, unknown>) {
    const previous = runtime.exec;
    runtime.exec = async (cmd) => cmd.includes("output")
      ? { exit: 0, out: JSON.stringify(Object.fromEntries(
          Object.entries(outputs).map(([k, v]) => [k, { value: v }]))), err: "" }
      : { exit: 0, out: "", err: "" };
    try {
      return await tools.stateOutput({ ...base, workdir: tempDir() });
    } finally {
      runtime.exec = previous;
    }
  }

  test("the reader returns params, nothing, or the legacy translation", async () => {
    expect(await readState({ params: cluster })).toEqual(cluster);
    expect(await readState({})).toBeUndefined();
    expect(await readState(legacyOutputs)).toEqual(cluster);
    // The worker lists must agree.
    await expect(readState({ ...legacyOutputs, worker_private_ips: [] })).rejects.toThrow(
      "legacy state lists 1 worker public addresses and 0 private addresses; refusing to guess the cluster");
    await expect(readState({ ...legacyOutputs, worker_private_ips: [] })).rejects.toBeInstanceOf(StepError);
    // The VPC id must be recorded.
    const { digitalocean_vpc_id: _id, ...withoutVpc } = legacyOutputs;
    for (const outputs of [withoutVpc, { ...legacyOutputs, digitalocean_vpc_id: "" }]) {
      await expect(readState(outputs)).rejects.toThrow("legacy state carries no digitalocean_vpc_id");
      await expect(readState(outputs)).rejects.toBeInstanceOf(StepError);
    }
    // A failed init is the SDK's step error.
    const previous = runtime.exec;
    runtime.exec = async () => ({ exit: 1, out: "", err: "no backend" });
    try {
      await expect(tools.stateOutput({ ...base, workdir: tempDir() })).rejects.toThrow("no backend");
      await expect(tools.stateOutput({ ...base, workdir: tempDir() })).rejects.toBeInstanceOf(StepError);
    } finally {
      runtime.exec = previous;
    }
  });

  test("infrastructure renders owned VPC, nodes, and firewalls", () => {
    const opts: Opts = { ...base, workdir: tempDir(), profile: "render", "red/event": "build" };
    scaffold(opts, tools.infrastructureSpecs(opts));
    const hcl = readFileSync(
      join(tools.toolDir(opts, tools.infrastructureTool), "main.tf"), "utf8");
    expect(hcl).toContain('resource "digitalocean_vpc" "cluster"');
    expect(hcl).toContain('resource "digitalocean_droplet" "control_plane"');
    expect(hcl).toContain("203.0.113.10/32");
    expect(hcl).toContain("prevent_destroy = true");
    expect(hcl).not.toContain("DIGITALOCEAN_TOKEN");
  });

  test("remote render pins components and keeps secret lookups", async () => {
    const opts: Opts = { ...base, workdir: tempDir(), profile: "render", "red/event": "build" };
    const result = await tools.ansibleRemoteStep(opts);
    const root = tools.toolDir(result, tools.ansibleRemoteTool);
    const play = readFileSync(join(root, "create.yml"), "utf8");
    expect(play).toContain("v1.36.3");
    expect(play).toContain("v0.28.8");
    expect(play).toContain("v0.1.68");
    expect(play).toContain("COLORS_PAR_DO_TOKEN");
    expect(play).toContain("COLORS_PAR_CLOUDFLARE_API_TOKEN");
    expect(play).not.toContain("fixture-secret");
  });

  // `loadInfrastructureStep` on a delete over an injected reader.
  const loadWith = (reader: () => Promise<computeCluster.ClusterParams | undefined>) =>
    tools.loadInfrastructureStep({ ...base, workdir: tempDir(), "red/event": "delete" }, reader);

  test("a real delete adopts the recorded cluster", async () => {
    const adopted = await loadWith(async () => cluster);
    expect(adopted["red/exit"]).toBe(0);
    expect(adopted["red/event"]).toBe("delete");
    expect(adopted["once/cluster"]).toEqual(cluster);
    expect(tools.entryIp(adopted)).toBe("203.0.113.1");
    // A readable state holding no compute leaves the cluster unadopted; the
    // remote cleanup then skips itself.
    const empty = await loadWith(async () => undefined);
    expect(empty["red/exit"]).toBe(0);
    expect("once/cluster" in empty).toBe(false);
  });

  test("a real delete refuses a partial cluster", async () => {
    const partial = await loadWith(async () => withoutNodes(cluster, [0]));
    expect(partial["red/exit"]).toBe(1);
    expect(partial["red/err"]).toBe("the compute stage did not report nodes this package declares: worker-0");
    const { vpc_id: _id, ...withoutVpc } = cluster;
    const noVpc = await loadWith(async () => withoutVpc);
    expect(noVpc["red/exit"]).toBe(1);
    expect(noVpc["red/err"]).toBe("compute state carries no vpc_id");
  });

  test("an unreadable backend fails a real delete closed", async () => {
    // Swallowing it is how a teardown ends up converging against 192.0.2.10.
    const result = await loadWith(unreadable);
    expect(result["red/exit"]).toBe(1);
    expect(String(result["red/err"])).toContain("could not read the infrastructure state for the delete cleanup");
    expect(String(result["red/err"])).toContain("no backend");
    // A legacy state the reader refuses is the same fail-closed path.
    const { digitalocean_vpc_id: _id, ...withoutVpc } = legacyOutputs;
    const previous = runtime.exec;
    runtime.exec = async (cmd) => cmd.includes("output")
      ? { exit: 0, out: JSON.stringify(Object.fromEntries(
          Object.entries(withoutVpc).map(([k, v]) => [k, { value: v }]))), err: "" }
      : { exit: 0, out: "", err: "" };
    try {
      const legacy = await loadWith(() => tools.stateOutput({ ...base, workdir: tempDir() }));
      expect(legacy["red/exit"]).toBe(1);
      expect(String(legacy["red/err"])).toContain("legacy state carries no digitalocean_vpc_id");
    } finally {
      runtime.exec = previous;
    }
  });

  test("a real create refuses nil and partial compute outputs", async () => {
    // The real `tofuWithSpec` over a stubbed tofu: init and apply succeed, and
    // the output read returns `outputs` — a `params` output, or nothing.
    const create = async (outputs: computeCluster.ClusterParams | undefined) => {
      const previous = runtime.exec;
      runtime.exec = async (cmd) => cmd.includes("output")
        ? { exit: 0, out: outputs ? JSON.stringify({ params: { value: outputs } }) : "{}", err: "" }
        : { exit: 0, out: "", err: "" };
      try {
        return await tools.infrastructureStep({ ...base, workdir: tempDir(), "red/event": "create" });
      } finally {
        runtime.exec = previous;
      }
    };
    const none = await create(undefined);
    expect(none["red/exit"]).toBe(1);
    expect(none["red/err"]).toBe("compute produced no params output; refusing to converge against the documentation addresses");
    const partial = await create(withoutNodes(cluster, [1]));
    expect(partial["red/exit"]).toBe(1);
    expect(partial["red/err"]).toBe("the compute stage did not report nodes this package declares: control-plane-0");
    const { vpc_id: _id, ...withoutVpc } = cluster;
    const noVpc = await create(withoutVpc);
    expect(noVpc["red/exit"]).toBe(1);
    expect(noVpc["red/err"]).toBe("compute state carries no vpc_id");
    const whole = await create(cluster);
    expect(whole["red/exit"]).toBe(0);
    expect(whole["once/cluster"]).toEqual(cluster);
  });

  test("repeated delete skips remote cleanup after nodes are gone", async () => {
    const opts: Opts = { ...base, "red/event": "delete" };
    expect(await tools.ansibleRemoteStep(opts)).toBe(opts);
  });

  test("workdir resolves beside state", () => {
    expect(tools.toolDir({
      workdir: ".colors", profile: "p",
      "red/state-file": "/srv/project/colors.yml",
    }, tools.infrastructureTool)).toBe("/srv/project/.colors/p/k8s-infrastructure");
  });
});

// --- workflow ----------------------------------------------------------------

const nextSteps = (event: string, step: string): string[] =>
  (workflow.wireFn(step, { "red/event": event }) ?? []).slice(1).map(String);

// The compute state is read once per run, through the injectable reader, on a
// real create or delete. Every lifecycle test stubs it: undefined is a
// readable state holding no compute, a value is a recorded `params`, and a
// throw is a backend that cannot be read.
const start = (opts: Opts, state?: computeCluster.ClusterParams, env: Record<string, string> = {}) =>
  workflow.startStep(opts, env, async () => state);
const startUnreadable = (opts: Opts, env: Record<string, string> = {}) =>
  workflow.startStep(opts, env, unreadable);
const credentials = { COLORS_PAR_DO_TOKEN: "x", COLORS_PAR_CLOUDFLARE_API_TOKEN: "y" };

describe("workflow", () => {
  test("create orders infrastructure before kubeadm and acceptance", () => {
    expect(nextSteps("create", "k8s/start")).toEqual(["k8s/infrastructure"]);
    expect(nextSteps("create", "k8s/infrastructure")).toEqual(["k8s/ansible-local"]);
    expect(nextSteps("create", "k8s/ansible-local")).toEqual(["k8s/ansible-remote"]);
    expect(nextSteps("create", "k8s/ansible-remote")).toEqual(["k8s/acceptance"]);
  });

  test("delete loads state and removes load balancer before infrastructure", () => {
    expect(nextSteps("delete", "k8s/start")).toEqual(["k8s/load-infrastructure"]);
    expect(nextSteps("delete", "k8s/load-infrastructure")).toEqual(["k8s/ansible-remote"]);
    expect(nextSteps("delete", "k8s/ansible-remote")).toEqual(["k8s/ansible-local"]);
    expect(nextSteps("delete", "k8s/ansible-local")).toEqual(["k8s/infrastructure"]);
    // The keypair goes after the compute destroy (ssh-keypair.md §3.3).
    expect(nextSteps("delete", "k8s/infrastructure")).toEqual(["k8s/ssh-cleanup"]);
    expect(workflow.wireFn("k8s/ssh-cleanup", { "red/event": "delete" }))
      .toEqual([ssh.cleanupStep, "k8s/generated-cleanup"]);
  });

  test("a build fills the placeholder key paths", async () => {
    const r = await workflow.startStep({ ...base, "red/event": "build" }, {});
    expect(r["red/exit"]).toBe(0);
    expect(r["ssh-private-key-path"]).toBe("/home/build-placeholder/.ssh/k8s-test");
    expect(r["ssh-keygen"]).toBe(true);
    const o = await workflow.startStep({ ...optout, "red/event": "build" }, {});
    expect(o["red/exit"]).toBe(0);
    expect(o["ssh-private-key-path"]).toBeUndefined();
  });

  test("build and dry-run need no credentials and never read the state", async () => {
    // A throwing reader proves nothing on these paths reaches the backend.
    for (const opts of [
      { ...base, "red/event": "build" },
      { ...base, "red/event": "create", "red/dry-run": true },
      { ...base, "red/event": "delete", "red/dry-run": true },
    ]) {
      expect((await startUnreadable(opts))["red/exit"]).toBe(0);
    }
  });

  test("real lifecycle needs secrets and delete override", async () => {
    expect((await start({ ...base, "red/event": "create" }))["red/exit"]).toBe(2);
    // The credentialed create runs opted out: in keygen mode a real create
    // generates the machine key, and no test may write into the operator's
    // ~/.ssh.
    expect((await start({ ...optout, "red/event": "create" }, undefined, credentials))["red/exit"]).toBe(0);
    expect((await start({ ...base, "red/event": "delete" }, undefined, credentials))["red/exit"]).toBe(2);
    expect((await start({ ...base, "red/event": "delete" }, undefined,
      { ...credentials, COLORS_PAR_COMPUTE_PREVENT_DESTROY: "false" }))["red/exit"]).toBe(0);
  });

  test("a provider switch is refused before the credentials", async () => {
    // Standard §4: the recorded provider is compared with the selected one
    // before the secrets, so the actionable error is what the operator reads.
    for (const event of ["create", "delete"]) {
      const r = await start({ ...base, "red/event": event, "compute-prevent-destroy": false },
        { ...cluster, provider: "vultr" });
      expect(r["red/exit"]).toBe(2);
      expect(String(r["red/err"]))
        .toContain("state holds a vultr machine; set provider-compute back to vultr and delete first");
      expect(String(r["red/err"])).not.toContain("required credential is not set");
    }
  });

  test("legacy state is accepted on the default provider", async () => {
    // A recorded state without `provider` predates the package recording one:
    // it is a digitalocean cluster, which is what is selected.
    const { provider: _p, ...legacy } = cluster;
    for (const event of ["create", "delete"]) {
      const r = await start({ ...base, "red/event": event, "compute-prevent-destroy": false }, legacy);
      expect(r["red/exit"]).toBe(2);
      expect(String(r["red/err"])).not.toContain("state holds");
      expect(String(r["red/err"])).toContain("required credential is not set");
    }
  });

  test("a matching provider passes to the credentials", async () => {
    const r = await start({ ...base, "red/event": "create" }, cluster);
    expect(r["red/exit"]).toBe(2);
    expect(String(r["red/err"])).not.toContain("state holds");
    expect(String(r["red/err"])).toContain("COLORS_PAR_DO_TOKEN");
  });

  test("an unreadable backend counts as no state on create", async () => {
    // A fresh clone has no readable state and must still be able to create.
    const r = await startUnreadable({ ...base, "red/event": "create" });
    expect(r["red/exit"]).toBe(2);
    expect(String(r["red/err"])).not.toContain("could not read");
    expect(String(r["red/err"])).not.toContain("state holds");
    expect(String(r["red/err"])).toContain("COLORS_PAR_DO_TOKEN");
  });

  test("a real create on a fresh work directory reports the credentials, not a crash", async () => {
    // No reader stub: the real `stateOutput` runs against a work directory
    // that holds no stage yet, as a fresh clone's does. The init cannot run
    // there, which the reader reports as the SDK's StepError; ONCE's
    // `readState` counts that as an unreadable state, so the create reports
    // its credentials instead of crashing.
    const work = tempDir();
    const r = await workflow.startStep({ ...base, workdir: work, "red/event": "create" }, {});
    expect(r["red/exit"]).toBe(2);
    expect(String(r["red/err"])).toContain("COLORS_PAR_DO_TOKEN");
    expect(String(r["red/err"])).not.toContain("could not read");
    expect(readdirSync(work)).toEqual([]);
  });

  test("backend key is package specific", async () => {
    const opts: Opts = {
      ...base, profile: "p", workdir: tempDir(), "provider-backend": "r2",
      "r2-bucket": "b", "r2-endpoint": "https://r2",
    };
    await workflow.backendAdvice(tools.infrastructureTool)(opts);
    const backend = readFileSync(
      join(tools.toolDir(opts, tools.infrastructureTool), "backend.tf.json"), "utf8");
    expect(backend).toContain("p/k8s-infrastructure.tfstate");
  });

  test("whole build renders all stages", async () => {
    const dir = tempDir();
    const result = await runWorkflow(workflow.k8sWorkflow,
      { ...base, "red/event": "build", workdir: dir, profile: "built" });
    expect(result["red/exit"]).toBe(0);
    for (const file of [
      "k8s-infrastructure/main.tf",
      "k8s-infrastructure/backend.tf.json",
      "k8s-ansible-local/main.yml",
      "k8s-ansible-remote/create.yml",
      "k8s-ansible-remote/delete.yml",
      "k8s-ansible-remote/inventory.json",
      "k8s-acceptance/acceptance.sh",
    ]) {
      expect(existsSync(join(dir, "built", file))).toBe(true);
    }
  });

  test("dry-run touches nothing", async () => {
    const dir = tempDir();
    const result = await runWorkflow(workflow.k8sWorkflow,
      { ...base, "red/event": "create", "red/dry-run": true, workdir: dir, profile: "dry" });
    expect(result["red/exit"]).toBe(0);
    expect(readdirSync(dir)).toEqual([]);
  });
});

// --- operator ----------------------------------------------------------------

describe("operator", () => {
  test("command uses ssh and remote admin kubeconfig", () => {
    const command = operator.command({ profile: "k8s-digitalocean" }, ["get", "nodes"]);
    expect(command[0]).toBe("ssh");
    expect(command[1]).toBe("-F");
    expect(String(command[2])).toEndWith("/.ssh/config");
    expect(command.slice(3)).toEqual([
      "--", "k8s-digitalocean",
      "'sudo' '-n' 'kubectl' '--kubeconfig=/etc/kubernetes/admin.conf' 'get' 'nodes'",
    ]);
  });

  test("arguments are shell quoted", () => {
    expect(operator.command({ profile: "p" }, ["get", "pods; id"]).at(-1))
      .toContain("'pods; id'");
  });

  test("run refuses profile overlay", async () => {
    const file = join(tempDir(), "colors.yml");
    writeFileSync(file, "profile: demo\n");
    const result = await operator.run(file, "kubectl", [],
      () => ({ exit: 0, out: "", err: "" }),
      { COLORS_PAR_PROFILE: "other" });
    expect(result["red/exit"]).toBe(2);
  });
});

// --- the machine keypair -----------------------------------------------------

describe("ssh", () => {
  test("a build never names the operator's home", () => {
    const opts = ssh.withMachineKey({ ...base, "red/event": "build" });
    expect(opts["ssh-private-key-path"]).toBe("/home/build-placeholder/.ssh/k8s-test");
    expect(opts["ssh-public-key-path"]).toBe("/home/build-placeholder/.ssh/k8s-test.pub");
    // The placeholder lands on the provider's own machine-key key.
    expect(opts["digitalocean-ssh-keys"]).toBe("/home/build-placeholder/.ssh/k8s-test.pub");
    expect(String(process.env.HOME)).not.toContain("build-placeholder");
  });

  test("a dry-run is held to the same rule as a build", () => {
    expect(ssh.renderedOnly({ "red/event": "build" })).toBe(true);
    expect(ssh.renderedOnly({ "red/event": "create", "red/dry-run": true })).toBe(true);
    expect(ssh.renderedOnly({ "red/event": "create" })).toBe(false);
  });

  test("real events render the real path", () => {
    const opts = ssh.withMachineKey({ ...base, "red/event": "create" });
    expect(String(opts["ssh-private-key-path"])).not.toContain("build-placeholder");
    expect(String(opts["ssh-private-key-path"]).endsWith("/.ssh/k8s-test")).toBe(true);
  });

  test("opt-out opts pass through untouched", () => {
    const opts = { ...optout, "red/event": "build" };
    expect(ssh.withMachineKey(opts)).toEqual(opts);
  });
});

// --- ~/.ssh/config -----------------------------------------------------------

describe("ssh-config", () => {
  test("the alias is the profile and the identity file stays unexpanded", () => {
    expect(sshConfig.hostAlias(base)).toBe("k8s-test");
    expect(sshConfig.identityFile(base)).toBe("~/.ssh/k8s-test");
  });

  test("the superseded package-prefixed block is still ours while the migration is in flight", () => {
    const old = [
      "# BEGIN k8s k8s-test ANSIBLE MANAGED BLOCK",
      "Host k8s-test", "  HostName 1.2.3.4",
      "# END k8s k8s-test ANSIBLE MANAGED BLOCK",
    ];
    expect(sshConfig.foreignStanzaLine(old, "k8s-test")).toBeUndefined();
    const current = [
      "# BEGIN k8s-test ANSIBLE MANAGED BLOCK",
      "Host k8s-test", "  HostName 1.2.3.4",
      "# END k8s-test ANSIBLE MANAGED BLOCK",
    ];
    expect(sshConfig.foreignStanzaLine(current, "k8s-test")).toBeUndefined();
    expect(sshConfig.foreignStanzaLine(["Host k8s-test", "  HostName 9.9.9.9"], "k8s-test")).toBe(1);
  });

  test("a global option above the first Host blocks the run", () => {
    expect(sshConfig.leadingOptionLine(["ServerAliveInterval 60", "Host x"])).toBe(1);
    expect(sshConfig.leadingOptionLine(["# a comment", "", "Host x", "  User root"])).toBeUndefined();
  });

  test("the refusal is reported as a failed step", () => {
    const home = mkdtempSync(join(tmpdir(), "k8s-ssh-config-"));
    const saved = process.env.HOME;
    try {
      process.env.HOME = home;
      const { mkdirSync } = require("node:fs");
      mkdirSync(join(home, ".ssh"));
      writeFileSync(join(home, ".ssh", "config"), "Host k8s-test\n  HostName 9.9.9.9\n");
      const refused = sshConfig.preflight(base);
      expect(refused["red/exit"]).toBe(1);
      expect(String(refused["red/err"])).toContain("k8s-test");
      writeFileSync(join(home, ".ssh", "config"),
        "# BEGIN k8s k8s-test ANSIBLE MANAGED BLOCK\nHost k8s-test\n  HostName 1.1.1.1\n# END k8s k8s-test ANSIBLE MANAGED BLOCK\n");
      expect(sshConfig.preflight(base)["red/exit"] ?? 0).toBe(0);
    } finally {
      process.env.HOME = saved;
    }
  });
});
