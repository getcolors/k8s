"""Two-node kubeadm lifecycle DAG and package-specific remote-state advice,
the port of io.github.getcolors.k8s.workflow."""

from __future__ import annotations

import os

from blue import dry_run, progress, tofu
from blue.cli import par_name, read_pars
from blue.lifecycle import preflight
from blue.workflow import advice_add, failed, workflow
from package_once_blue import compute_cluster as cluster

from . import ssh, ssh_config, tools, validate

LIFECYCLE_EVENTS = ("create", "delete")


def _lifecycle_event(context: dict) -> bool:
    """A real create or delete: the two events that touch the provider."""
    return bool(context.get("real") and context.get("event") in LIFECYCLE_EVENTS)

DEFAULTS = {"compute-prevent-destroy": True,
            "provider-compute": "digitalocean",
            "provider-dns": "no-infra",
            "provider-backend": "local",
            "kubernetes-distribution": "kubeadm",
            "kubernetes-cni": "flannel",
            "control-plane-count": 1,
            "worker-count": 1,
            "digitalocean-cloud-controller": True,
            "repository-branch": "main",
            "repository-path": "./clusters/k8s-digitalocean",
            "cert-manager-acme-environment": "production",
            "workdir": ".colors"}


async def start_step(opts: dict, env: dict | None = None) -> dict:
    """Overlay credentials, validate, and guard real destruction.

    The compute state is read up front, on the same defaulted and overlaid
    opts the validators see — the overlay is what carries the backend
    credentials — and only for the two events that touch the provider, so
    the Compute Provider Standard's §4 check runs before the credentials: a
    recorded provider that differs from the selected one reports the
    actionable error, not a missing token. On a create an unreadable
    backend counts as no state (a fresh clone has none); a delete adopts
    the cluster in its own first step and fails closed there.
    """
    environment = dict(os.environ if env is None else env)
    overlaid = read_pars({**DEFAULTS, **opts}, environment)
    context = {"event": overlaid.get("blue/event"), "real": not overlaid.get("blue/dry-run")}
    state = (await cluster.read_state(overlaid, tools.state_output)
             if _lifecycle_event(context) else {})

    # The machine key's create matrix and the DigitalOcean preflight run
    # before any template is rendered: an unowned key on disk or at the
    # provider stops the run while stopping is still free. Every other event
    # fills the same template values — a destroy renders before it destroys —
    # but checks no key, because the delete's key cleanup runs after the
    # compute destroy.
    async def after(o, _env, ctx):
        if ctx["real"] and ctx["event"] == "create":
            async def recorded(_opts):
                return state.get("params")
            o = await ssh.ensure_key(o, recorded)
            if failed(o):
                return o
            o = ssh.preflight(ssh.with_machine_key(o))
            if failed(o):
                return o
            o = ssh_config.preflight(o)
            if failed(o):
                return o
            return {**o, "blue/exit": 0}
        return {**ssh.with_machine_key(o), "blue/exit": 0}

    return await preflight(
        opts, defaults=DEFAULTS, overlay=read_pars, env=env,
        after_validate=after,
        validators=[
            lambda _o, e, _c: validate.env_errors(e),
            lambda o, _e, _c: validate.state_errors(o),
            lambda o, _e, c: (cluster.provider_validator(
                validate.spec, o, state.get("params"), lambda: validate.secret_errors(o))
                if _lifecycle_event(c) else []),
            lambda o, _e, c: ([f"compute destruction is protected; set "
                               f"{par_name('compute-prevent-destroy')}=false for this delete"]
                              if c["real"] and c["event"] == "delete"
                              and o.get("compute-prevent-destroy") else []),
        ])


def wire_fn(step: str, run_opts: dict):
    if run_opts.get("blue/event") == "delete":
        return {
            "k8s/start": (start_step, "k8s/load-infrastructure"),
            "k8s/load-infrastructure": (tools.load_infrastructure_step, "k8s/ansible-remote"),
            "k8s/ansible-remote": (tools.ansible_remote_step, "k8s/ansible-local"),
            "k8s/ansible-local": (tools.ansible_local_step, "k8s/infrastructure"),
            # The keypair goes after the compute destroy (ssh-keypair.md §3.3):
            # a key that predeceases its hosts locks the operator out of nodes
            # that still exist.
            "k8s/infrastructure": (tools.infrastructure_step, "k8s/ssh-cleanup"),
            "k8s/ssh-cleanup": (ssh.cleanup_step, "k8s/generated-cleanup"),
            "k8s/generated-cleanup": (tools.generated_cleanup_step,),
        }.get(step)
    return {
        "k8s/start": (start_step, "k8s/infrastructure"),
        "k8s/infrastructure": (tools.infrastructure_step, "k8s/ansible-local"),
        "k8s/ansible-local": (tools.ansible_local_step, "k8s/ansible-remote"),
        "k8s/ansible-remote": (tools.ansible_remote_step, "k8s/acceptance"),
        "k8s/acceptance": (tools.acceptance_step,),
    }.get(step)


def backend_advice(tool: str):
    """Write the selected backend with a package-specific remote state key."""
    return tofu.conventional_backend_advice(
        dir=lambda o, tool=tool: tools.tool_dir(o, tool),
        key=lambda o, tool=tool: f"{'' if o.get('profile') is None else o.get('profile')}/{tool}.tfstate")


side_effecting_steps = ["k8s/load-infrastructure", "k8s/infrastructure",
                        "k8s/ansible-local", "k8s/ansible-remote",
                        "k8s/acceptance", "k8s/ssh-cleanup", "k8s/generated-cleanup"]


def create_workflow():
    wf = workflow(start="k8s/start", wire_fn=wire_fn)
    wf = advice_add(wf, "k8s/load-infrastructure", "before",
                    "io.github.getcolors.k8s.workflow/backend",
                    backend_advice(tools.infrastructure_tool))
    wf = advice_add(wf, "k8s/infrastructure", "before",
                    "io.github.getcolors.k8s.workflow/backend",
                    backend_advice(tools.infrastructure_tool))
    wf = progress.advise(wf)
    wf = dry_run.advise(wf, side_effecting_steps)
    return wf


k8s_workflow = create_workflow()
