// Launcher contract and path/version helpers, the port of
// io.github.getcolors.k8s.utils.

import { stageDir } from "red/cli";
import type { Opts } from "red/workflow";

// Bump on any change a launcher pinned to an older commit could not survive.
export const contract = 2;

// Resolve a stage beside colors.yml, never relative to the caller.
export function toolDir(opts: Opts, tool: string): string {
  return stageDir(opts, tool, { defaultProfile: "k8s" });
}

export function unprefixV(version: unknown): string {
  return String(version).replace(/^v/, "");
}

export function kubernetesMinor(version: unknown): string {
  const [major, minor] = unprefixV(version).split(".");
  return `v${major}.${minor}`;
}

export function kubernetesPackageVersion(version: unknown): string {
  return `${unprefixV(version)}-1.1`;
}

// The managed SSH alias, derived from the project profile.
export function hostAlias(opts: Opts): string {
  const profile = String(opts.profile ?? "");
  return profile.length > 0 ? profile : "k8s";
}
