// Two-node kubeadm lifecycle DAG and package-specific remote-state advice, the
// port of io.github.getcolors.k8s.workflow.

import { readPars, parName } from "red/cli";
import * as dryRun from "red/dry-run";
import { preflight, type PreflightContext } from "red/lifecycle";
import * as progress from "red/progress";
import * as tofu from "red/tofu";
import { adviceAdd, failed, workflow, type Opts, type WireDecl } from "red/workflow";
import { compute, computeCluster } from "package-once-red";
import * as ssh from "./ssh.ts";
import * as sshConfig from "./ssh-config.ts";
import * as tools from "./tools.ts";
import * as validate from "./validate.ts";

export const defaults: Opts = {
  "compute-prevent-destroy": true,
  "provider-compute": "digitalocean",
  "provider-dns": "no-infra",
  "provider-backend": "local",
  "kubernetes-distribution": "kubeadm",
  "kubernetes-cni": "flannel",
  "control-plane-count": 1,
  "worker-count": 1,
  "digitalocean-cloud-controller": true,
  "repository-branch": "main",
  "repository-path": "./clusters/k8s-digitalocean",
  "cert-manager-acme-environment": "production",
  workdir: ".colors",
};

const lifecycleEvents = ["create", "delete"];

// A real create or delete: the two events that touch the provider.
const lifecycleEvent = ({ event, real }: PreflightContext): boolean =>
  Boolean(real && lifecycleEvents.includes(String(event)));

// Overlay credentials, validate, and guard real destruction.
//
// The compute state is read up front, on the same defaulted and overlaid opts
// the validators see — the overlay is what carries the backend credentials —
// and only for the two events that touch the provider, so the Compute Provider
// Standard's §4 check runs before the credentials: a recorded provider that
// differs from the selected one reports the actionable error, not a missing
// token. On a create an unreadable backend counts as no state (a fresh clone
// has none); a delete adopts the cluster in its own first step and fails
// closed there. The reader is injectable so tests never shell out to tofu.
export async function startStep(
  opts: Opts,
  env: Record<string, string | undefined> = process.env,
  reader: compute.StateReader = tools.stateOutput,
): Promise<Opts> {
  const overlaid = readPars({ ...defaults, ...opts }, env);
  const context: PreflightContext = {
    event: typeof overlaid["red/event"] === "string" ? overlaid["red/event"] as string : undefined,
    real: !overlaid["red/dry-run"],
  };
  const state = lifecycleEvent(context) ? await computeCluster.readState(overlaid, reader) : {};
  return preflight(opts, {
    defaults,
    overlay: readPars,
    validators: [
      (_opts, environment) => validate.envErrors(environment),
      (current) => validate.stateErrors(current),
      (current, _environment, ctx) =>
        lifecycleEvent(ctx)
          ? computeCluster.providerValidator(validate.spec, current, state.params,
                                             () => validate.secretErrors(current))
          : [],
      (current, _environment, { event, real }) =>
        real && event === "delete" && current["compute-prevent-destroy"]
          ? ["compute destruction is protected; set " +
             `${parName("compute-prevent-destroy")}=false for this delete`]
          : [],
    ],
    // The machine key's create matrix and the DigitalOcean preflight run before
    // any template is rendered: an unowned key on disk or at the provider stops
    // the run while stopping is still free. Every other event fills the same
    // template values — a destroy renders before it destroys — but checks no
    // key, because the delete's key cleanup runs after the compute destroy.
    afterValidate: async (current, _environment, ctx) => {
      if (ctx.real && ctx.event === "create") {
        let next = await ssh.ensureKey(current, async () => state.params);
        if (failed(next)) return next;
        next = await ssh.preflight(ssh.withMachineKey(next));
        if (!failed(next)) next = sshConfig.preflight(next);
        return failed(next) ? next : { ...next, "red/exit": 0 };
      }
      return { ...ssh.withMachineKey(current), "red/exit": 0 };
    },
  }, env);
}

export function wireFn(step: string, runOpts: Opts): WireDecl | undefined {
  if (runOpts["red/event"] === "delete") {
    const graph: Record<string, WireDecl> = {
      "k8s/start": [startStep, "k8s/load-infrastructure"],
      "k8s/load-infrastructure": [tools.loadInfrastructureStep, "k8s/ansible-remote"],
      "k8s/ansible-remote": [tools.ansibleRemoteStep, "k8s/ansible-local"],
      "k8s/ansible-local": [tools.ansibleLocalStep, "k8s/infrastructure"],
      // The keypair goes after the compute destroy (ssh-keypair.md §3.3): a
      // key that predeceases its hosts locks the operator out of nodes that
      // still exist.
      "k8s/infrastructure": [tools.infrastructureStep, "k8s/ssh-cleanup"],
      "k8s/ssh-cleanup": [ssh.cleanupStep, "k8s/generated-cleanup"],
      "k8s/generated-cleanup": [tools.generatedCleanupStep],
    };
    return graph[step];
  }
  const graph: Record<string, WireDecl> = {
    "k8s/start": [startStep, "k8s/infrastructure"],
    "k8s/infrastructure": [tools.infrastructureStep, "k8s/ansible-local"],
    "k8s/ansible-local": [tools.ansibleLocalStep, "k8s/ansible-remote"],
    "k8s/ansible-remote": [tools.ansibleRemoteStep, "k8s/acceptance"],
    "k8s/acceptance": [tools.acceptanceStep],
  };
  return graph[step];
}

// Write the selected backend with a package-specific remote state key.
export function backendAdvice(tool: string) {
  return tofu.conventionalBackendAdvice({
    dir: (opts) => tools.toolDir(opts, tool),
    key: (opts) => `${opts.profile ?? ""}/${tool}.tfstate`,
  });
}

export const sideEffectingSteps = [
  "k8s/load-infrastructure", "k8s/infrastructure", "k8s/ansible-local",
  "k8s/ansible-remote", "k8s/acceptance", "k8s/ssh-cleanup", "k8s/generated-cleanup",
];

function create() {
  let wf = workflow({ start: "k8s/start", wireFn });
  wf = adviceAdd(wf, "k8s/load-infrastructure", "before",
    "io.github.getcolors.k8s.workflow/backend", backendAdvice(tools.infrastructureTool));
  wf = adviceAdd(wf, "k8s/infrastructure", "before",
    "io.github.getcolors.k8s.workflow/backend", backendAdvice(tools.infrastructureTool));
  wf = progress.advise(wf);
  wf = dryRun.advise(wf, sideEffectingSteps);
  return wf;
}

export const k8sWorkflow = create();
