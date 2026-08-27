"""Two-node kubeadm lifecycle DAG and package-specific remote-state advice,
the port of io.github.getcolors.k8s.workflow."""

from __future__ import annotations

from blue import dry_run, progress, tofu
from blue.cli import par_name, read_pars
from blue.lifecycle import preflight
from blue.workflow import advice_add, workflow

from . import tools, validate

LIFECYCLE_EVENTS = ("create", "delete")

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
    """Overlay credentials, validate, and guard real destruction."""
    return await preflight(
        opts, defaults=DEFAULTS, overlay=read_pars, env=env,
        validators=[
            lambda _o, e, _c: validate.env_errors(e),
            lambda o, _e, _c: validate.state_errors(o),
            lambda o, _e, c: (validate.secret_errors(o)
                              if c["real"] and c["event"] in LIFECYCLE_EVENTS else []),
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
            "k8s/infrastructure": (tools.infrastructure_step, "k8s/generated-cleanup"),
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
                        "k8s/acceptance", "k8s/generated-cleanup"]


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
