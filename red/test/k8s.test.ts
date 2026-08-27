import { describe, expect, test } from "bun:test";
import { existsSync, mkdtempSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { scaffold } from "red/scaffold";
import { runtime } from "red/runtime";
import { run as runWorkflow, type Opts } from "red/workflow";
import * as operator from "../src/operator.ts";
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
  "digitalocean-ssh-key-fingerprint": "fingerprint",
  "digitalocean-vpc-cidr": "10.20.0.0/20",
  "digitalocean-ssh-sources": ["203.0.113.10/32"],
  "digitalocean-api-sources": ["203.0.113.10/32"],
  "application-host": "hello.example.com",
  "cloudflare-zone": "example.com",
  "external-dns-owner-id": "k8s-test",
  "cert-manager-acme-environment": "production",
};

const matching = (opts: Opts, re: RegExp): string[] =>
  validate.stateErrors(opts).filter((e) => re.test(e));

// --- validate ----------------------------------------------------------------

describe("validate", () => {
  test("complete state is valid", () => {
    expect(validate.stateErrors(base)).toEqual([]);
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
    const parsed = JSON.parse(tools.inventory({
      ...base,
      control_plane_public_ip: "203.0.113.1",
      control_plane_private_ip: "10.20.0.2",
      worker_public_ips: ["203.0.113.2"],
      worker_private_ips: ["10.20.0.3"],
    }));
    expect(parsed.all.children.control_plane.hosts["k8s-test-control-plane-1"].private_ip)
      .toBe("10.20.0.2");
    expect(parsed.all.children.workers.hosts["k8s-test-worker-1"].ansible_host)
      .toBe("203.0.113.2");
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

  test("load-infrastructure accepts the system environment", async () => {
    const opts: Opts = { ...base, workdir: tempDir(), "red/event": "delete" };
    const previous = runtime.exec;
    runtime.exec = async (cmd) =>
      cmd.includes("output")
        ? { exit: 0, out: '{"control_plane_public_ip":{"value":"203.0.113.1"}}', err: "" }
        : { exit: 0, out: "", err: "" };
    try {
      const result = await tools.loadInfrastructureStep(opts);
      expect(result["red/event"]).toBe("delete");
      expect(result["k8s/infrastructure-present?"]).toBe(true);
      expect(result.control_plane_public_ip).toBe("203.0.113.1");
    } finally {
      runtime.exec = previous;
    }
  });

  test("repeated delete skips remote cleanup after nodes are gone", async () => {
    const opts: Opts = { ...base, "red/event": "delete", "k8s/infrastructure-present?": false };
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
  });

  test("build and dry-run need no credentials", async () => {
    expect((await workflow.startStep({ ...base, "red/event": "build" }, {}))["red/exit"])
      .toBe(0);
    expect((await workflow.startStep(
      { ...base, "red/event": "create", "red/dry-run": true }, {}))["red/exit"]).toBe(0);
  });

  test("real lifecycle needs secrets and delete override", async () => {
    expect((await workflow.startStep({ ...base, "red/event": "create" }, {}))["red/exit"])
      .toBe(2);
    const env = {
      COLORS_PAR_DO_TOKEN: "x",
      COLORS_PAR_CLOUDFLARE_API_TOKEN: "y",
    };
    expect((await workflow.startStep({ ...base, "red/event": "create" }, env))["red/exit"])
      .toBe(0);
    expect((await workflow.startStep({ ...base, "red/event": "delete" }, env))["red/exit"])
      .toBe(2);
    expect((await workflow.startStep({ ...base, "red/event": "delete" },
      { ...env, COLORS_PAR_COMPUTE_PREVENT_DESTROY: "false" }))["red/exit"]).toBe(0);
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
