import { execCli, findUp, runCli } from "red/cli";
import type { Opts } from "red/workflow";
import * as operator from "./operator.ts";
import { k8sWorkflow } from "./workflow.ts";

export const lifecycleCommands = ["build", "create", "delete"];

export const usage =
  "Usage: red <build|create|delete> [-f|--file colors.yml] [--dry-run]\n" +
  "       red kubectl <kubectl arguments>\n" +
  "\n" +
  "  build     render the work directory only — contact nothing\n" +
  "  create    converge kubeadm, Flannel, Flux, and the application\n" +
  "  delete    remove the Kubernetes load balancer, then infrastructure\n" +
  "  kubectl   invoke kubectl securely through control-plane SSH";

// The nearest colors.yml at or above the working directory. Walking up means
// red can be run from any subdirectory of a project and still find the one
// desired state.
function defaultFile(): string {
  return findUp("colors.yml") ?? "colors.yml";
}

function fileArg(arg: string): boolean {
  return arg === "-f" || arg === "--file" || arg.startsWith("--file=");
}

export function defaultArgs(args: string[]): string[] {
  return args.some(fileArg) ? args : [...args, "-f", defaultFile()];
}

// REPL-friendly entry point that returns the final outcome map.
export async function run(...args: string[]): Promise<Opts> {
  const command = args[0] ?? "";
  if (["help", "--help", "-h"].includes(command)) {
    return { "red/exit": 0, "red/err": usage };
  }
  if (lifecycleCommands.includes(command)) {
    return runCli(k8sWorkflow, defaultArgs(args));
  }
  if (command === "kubectl") {
    return operator.run(defaultFile(), "kubectl", args.slice(1));
  }
  return { "red/exit": 2, "red/err": usage };
}

export async function exec(args: string[] = Bun.argv.slice(2)): Promise<never> {
  if (lifecycleCommands.includes(args[0] ?? "")) {
    return execCli(k8sWorkflow, defaultArgs(args));
  }
  const result = await run(...args);
  if (result["red/err"]) {
    ((result["red/exit"] ?? 0) === 0 ? console.log : console.error)(result["red/err"]);
  }
  return process.exit(result["red/exit"] ?? 0);
}
