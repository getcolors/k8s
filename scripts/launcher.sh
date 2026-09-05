#!/usr/bin/env bash
set -euo pipefail

# The launchers are the files here that are copied out and run somewhere else,
# so their interesting behaviour happens in environments this checkout does not
# contain: no bb.edn beside them, no k8s on the classpath, an unstamped pin.
# `bb test` cannot reach any of that — it runs inside the checkout, where
# green/bb.edn local-roots k8s to the working tree, which is the one path on
# which none of the resolution logic runs. Every failure this catches is
# silent: the launcher still starts and still renders, it just resolves the
# wrong thing.

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
launcher="$root/skills/package-k8s-green/green"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
checks=0
fail(){ echo "launcher: FAIL — $*" >&2; exit 1; }
ok(){ checks=$((checks+1)); echo "  ok — $*"; }

[ -f "$launcher" ] || fail 'payload launcher is missing'
grep -q 'io.github.getcolors.k8s.workflow/workflow' "$launcher" || fail 'workflow dispatch is missing'
for bad in 'defn.*-step' 'tofu/' 'helm '; do
  ! grep -qE "$bad" "$launcher" || fail "launcher contains package logic: $bad"
done
ok 'dispatches to the library and contains no lifecycle logic'

grep -qE '\(def \^:private k8s-sha (nil|"[0-9a-f]{40}")\)' "$launcher" || fail 'invalid pin site'
ok 'has one managed immutable pin site'

mkdir "$tmp/bare"
cp "$launcher" "$tmp/bare/green"; chmod +x "$tmp/bare/green"
if grep -q '(def \^:private k8s-sha nil)' "$launcher"; then
  out=$(cd "$tmp/bare" && ./green build 2>&1 || true)
  grep -q K8S_LIB_ROOT <<<"$out" || fail 'an unpinned launcher did not explain K8S_LIB_ROOT'
  ok 'unstamped payload fails with an actionable working-tree override'
else
  ok 'payload carries a real package commit pin'
fi

mkdir "$tmp/project"
cp "$launcher" "$tmp/project/green"; chmod +x "$tmp/project/green"
cp "$root/test/fixtures/colors.yml" "$tmp/project/colors.yml"
(cd "$tmp/project" && K8S_LIB_ROOT="$root" ./green build >/dev/null) || fail 'K8S_LIB_ROOT build failed'
[ -f "$tmp/project/.colors/k8s-fixture/k8s-infrastructure/main.tf" ] || fail 'copied payload rendered nothing'
ok 'working-tree override renders from a copied payload'
mkdir -p "$tmp/project/deep/path"
(cd "$tmp/project/deep/path" && K8S_LIB_ROOT="$root" ../../green build >/dev/null) || fail 'upward desired-state search failed'
ok 'finds colors.yml by walking upward'

out=$(cd "$tmp/project" && K8S_LIB_ROOT="$root" ./green nonsense 2>&1 || true)
grep -q Usage <<<"$out" || fail 'unknown command has no usage'
for verb in build create delete kubectl; do
  grep -q "\"$verb\"" "$launcher" || fail "missing command $verb"
done
! grep -q '"talosctl" command' "$launcher" || fail 'Talos command remains dispatchable'
grep -q 'io.github.getcolors.k8s.operator/run' "$launcher" || fail 'operator commands bypass tested code'
ok 'lifecycle and SSH-backed kubectl commands are dispatchable'

[ -L "$root/green/green" ] && [ "$(readlink "$root/green/green")" = ../skills/package-k8s-green/green ] || fail 'green/green is not the payload symlink'
[ -L "$root/red/red" ] && [ "$(readlink "$root/red/red")" = ../skills/package-k8s-red/red ] || fail 'red/red is not the payload symlink'
[ -L "$root/blue/blue" ] && [ "$(readlink "$root/blue/blue")" = ../skills/package-k8s-blue/blue ] || fail 'blue/blue is not the payload symlink'
ok 'each colour dir symlinks its skill payload'

# The red and blue payloads refuse an unpinned standalone copy the same way.
for colour in red blue; do
  payload="$root/skills/package-k8s-$colour/$colour"
  if grep -qE '"package-k8s-red": null,|^# dependencies = \[\]$' "$payload"; then
    cp "$payload" "$tmp/$colour"
    chmod +x "$tmp/$colour"
    out=$( (cd "$tmp" && "./$colour" build 2>&1) || true )
    grep -q 'K8S_LIB_ROOT' <<<"$out" ||
      fail "an unpinned $colour payload must name K8S_LIB_ROOT; got: $out"
    ok "an unpinned $colour payload explains itself"
  else
    ok "$colour payload is pinned to a real commit"
  fi
done

# The ONCE pin is one fact in four places: the three manifests and the red
# payload's PINS, which installs ONCE itself (blue resolves it transitively
# through the package). A manifest bump the red payload did not follow
# installs a package whose `computeCluster` import fails at first use in a
# deployment, not here.
once_sha=$(awk '/once\.git/ {found=1} found && match($0, /:git\/sha "[0-9a-f]{40}"/) {print substr($0, RSTART+10, 40); exit}' "$root/green/deps.edn")
[ -n "$once_sha" ] || fail 'green/deps.edn carries no ONCE pin'
grep -q "getcolors/once#$once_sha" "$root/red/package.json" || fail 'red/package.json ONCE pin differs from green'
grep -q "rev = \"$once_sha\"" "$root/blue/pyproject.toml" || fail 'blue/pyproject.toml ONCE pin differs from green'
grep -q "getcolors/once#$once_sha" "$root/skills/package-k8s-red/red" || fail 'red payload PINS ONCE pin differs from green'
ok 'the ONCE pin agrees in green, red, blue, and the red payload'

echo "launcher: $checks checks passed"
