# CLAUDE.md

## What this is

`k8s` is a tri-colour Package Skill (green, red, blue) for a two-node kubeadm
Kubernetes cluster on DigitalOcean: one control plane, one worker, a
deployment-owned VPC and firewalls, Flannel, DigitalOcean CCM, Flux, and a
public GitOps repository. It ships three skills — `package-k8s-green`,
`package-k8s-red`, and `package-k8s-blue` — each with one launcher under
`skills/`. The consumer is `../k8s-digitalocean`.

## Layout and commands

The three implementations live in the tri-colour layout, matching `netbird`,
`k3s`, and `clickhouse`: canonical Clojure in `green/` (`green/bb.edn`,
`green/deps.edn`, `green/src/`, `green/tasks/`, tests under `green/test/clj`),
TypeScript/Bun in `red/`, and Python/uv in `blue/`. Green is canonical: a
behavioural change lands in all three colours in the same commit and passes
`scripts/parity.sh`. The fixture and the goldens are shared across colours at
the repository root — `test/fixtures/` and `test/resources/golden/` — with
`green/test/fixtures` and `green/test/resources` symlinks pointing at them.
Each colour dir holds a launcher symlink to its skill payload (`green/green`,
`red/red`, `blue/blue`).

```sh
cd green && bb test
cd green && bb golden
cd green && bb golden:accept   # regenerate after an intended change — read the diff first
cd red && bun test && bun run typecheck
cd blue && uv run pytest
./scripts/parity.sh            # three colours, both state backends, byte for byte
./scripts/launcher.sh          # from the repository root
cd green && ./green build
cd green && ./green create --dry-run
```

Never run a real create/delete without explicit authorization. Never edit or
read `.colors/`, and never read `.envrc.private`.

## The two-backend golden and parity axis

The goldens have a second axis beside the fixture: the one
`test/fixtures/colors.yml` is rendered under the **local** state backend and
again under **r2**, produced by overlaying `COLORS_PAR_PROVIDER_BACKEND` on the
same file. The committed trees live at
`test/resources/golden/{local,r2}/k8s-fixture/` and differ only in
`k8s-infrastructure/backend.tf.json`. `scripts/golden.sh` checks green against
both; `scripts/parity.sh` renders both variants through every colour and diffs
the trees — and the colour template trees (`red/resources`, blue's embedded
`resources/`) — byte for byte.

## Architecture and safety

Create is `start -> infrastructure -> ansible-local -> ansible-remote ->
acceptance`. Delete loads node addresses from remote state without changing
infrastructure, removes the Kubernetes-managed DigitalOcean Load Balancer,
then removes local SSH configuration and destroys infrastructure.
Stage names are remote-state keys and remain package-specific.

Build and dry-run are credential-free. Credentials use only `COLORS_PAR_*` and
must never render. `COLORS_PAR_PROFILE` is always refused. Keep
`compute-prevent-destroy: true`; lift it for one authorized delete with
`COLORS_PAR_COMPUTE_PREVENT_DESTROY=false`.

The public Kubernetes API and SSH admit only configured operator CIDRs. Cluster
traffic uses the deployment-owned VPC. `./green kubectl` (and its red and blue
counterparts) invokes the root-owned admin kubeconfig through SSH; no
kubeconfig is copied locally. DigitalOcean and Cloudflare tokens are streamed
from process environment into Kubernetes Secrets with Ansible `no_log` and
never enter generated files or GitOps.

The package depends only on the SDK — in every colour. Its own multi-node
DigitalOcean template is preferable to coupling to ONCE's single-server
templates, so unlike `k3s` there is no ONCE pin anywhere. Golden output guards
firewall, state-key, pin, and no-rendered-secret invariants.

## Coupling

The package pins the Green SDK in `green/deps.edn`, the Red SDK in
`red/package.json`, and the Blue SDK in `blue/pyproject.toml`. The launchers
pin only k8s: green resolves `green` transitively through `green/deps.edn`
(`:deps/root "green"`), while the red and blue payloads carry their SDK pins
beside the package pin.

Use `K8S_LIB_ROOT` (the repository root, for every colour; red also accepts
the `red/` dir directly) and `GREEN_LIB_ROOT` for working-tree development.
Final launchers use a pushed SHA managed by `bb pin` (in `green/`), which
stamps all three payloads from their unpinned birth forms; deployment
launchers are copies, not symlinks.

## Documentation

`index.html` is this repository's landing page and carries two analytics tags:
GA4 measurement ID `G-4VKP1WY4QJ`, whose explicit `page_title` must exactly
equal the decoded HTML `<title>` and stay distinct and stable so one Analytics
property can separate repositories, and the self-hosted Rybbit snippet
`<script src="https://rybbit.getcolors.ai/api/script.js" data-site-id="9fb9c41a6d49" defer></script>`,
which shares one site ID across every page because `getcolors.github.io/<repo>/`
paths already encode the repository. Never add one tag without the other.

## Git

Do not invent or hand-edit any pin. After committing and pushing package code,
run `bb pin` (in `green/`), commit the launcher stamps, and push again.
Consumers hold a copy of the payload and must re-copy after every update. Do
not commit or push unless explicitly asked.
