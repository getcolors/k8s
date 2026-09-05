"""DigitalOcean infrastructure, kubeadm Ansible, and acceptance stages, the
port of io.github.getcolors.k8s.tools."""

from __future__ import annotations

import json
import math
from decimal import Decimal
from pathlib import Path

from blue import tofu
from blue.ansible import ansible_with_spec
from blue.providers import tool_env
from blue.runtime import runtime
from blue.scaffold import PRESERVE_JINJA_DELIMITERS, content_spec, scaffold
from blue.workflow import StepError, failed
from package_once_blue import compute_cluster as cluster

from . import ssh, ssh_config, utils, validate

infrastructure_tool = "k8s-infrastructure"
ansible_local_tool = "k8s-ansible-local"
ansible_remote_tool = "k8s-ansible-remote"
acceptance_tool = "k8s-acceptance"
tofu_tools = [infrastructure_tool]

ROOT = Path(__file__).parent / "resources"
template_opts = PRESERVE_JINJA_DELIMITERS

tool_dir = utils.tool_dir


def template(path: str, file: str) -> dict:
    name = f"tools/{path.replace('.', '/')}/{file}"
    source = ROOT / name
    if not source.is_file():
        raise StepError(f"template not found: {name}")
    return {"name": name, "content": source.read_text()}


def spec(source: dict, target: str, data: dict) -> dict:
    return {"template": source, "target": target, "data": data, "opts": template_opts}


def raw_spec(target: str, content: str) -> dict:
    return content_spec(target, content)


def credential_env(opts: dict, *slots: str) -> dict[str, str] | None:
    """Provider and backend environment additions, omitting absent credentials."""
    return tool_env(validate.providers, opts, [*slots, "provider-backend"])


def _compact_json(value) -> str:
    return json.dumps(value, separators=(",", ":"))


def infrastructure_specs(opts: dict) -> list[dict]:
    # The machine-key paths are filled here as well as in preflight, so the
    # template renders the same bytes whichever step scaffolds it.
    opts = ssh.with_machine_key(opts)
    dir = tool_dir(opts, infrastructure_tool)
    data = {**opts,
            "digitalocean-ssh-sources-json":
            _compact_json(opts.get("digitalocean-ssh-sources")),
            "digitalocean-api-sources-json":
            _compact_json(opts.get("digitalocean-api-sources"))}
    return [spec(template("infrastructure", "main.tf"), f"{dir}/main.tf", data)]


# What `build` and `--dry-run` render as the VPC id: the compute stage owns
# the real one, recorded as `params.vpc_id`.
fallback_vpc_id = "00000000-0000-0000-0000-000000000000"


def node_name(opts: dict, role: str, index: int) -> str:
    """What this package calls a node — `<name>-<role>-<ordinal>`, 1-based,
    the rule the template gives the droplets. This is the package's own
    naming, kept over ONCE's fallback rule (Compute Cluster Standard §5,
    adoption renames nothing), and the name the legacy translation gives a
    node a pre-adoption state recorded without one."""
    return f"{opts.get('digitalocean-name')}-{role}-{index + 1}"


def nodes(opts: dict) -> list[dict]:
    """The cluster's nodes in declared order — ONCE's `nodes` over the
    adopted `once/cluster`: every field from state on a real run, the
    fallbacks on a build, with their names overridden to this package's own."""
    adopted = opts.get("once/cluster")
    result = cluster.nodes(validate.spec, opts, adopted)
    if adopted is None:
        return [{**n, "name": node_name(opts, str(n["role"]), n["index"])} for n in result]
    return result


def entry_ip(opts: dict):
    """The address the bare `<profile>` alias points to: the control
    plane's, as ONCE's `ssh_config_hosts` resolves the spec's `entry`."""
    return cluster.ssh_config_hosts(validate.spec, opts, nodes(opts))[0]["ip"]


def _non_blank(x) -> bool:
    return isinstance(x, str) and bool(x.strip())


def params_errors(params: dict | None) -> list[str]:
    """The extension key this package puts inside `params` beside ONCE's:
    `vpc_id`, the deployment-owned VPC the cloud controller is told about. A
    real run is refused without it."""
    return [] if _non_blank((params or {}).get("vpc_id")) else ["compute state carries no vpc_id"]


def _with_params_check(opts: dict) -> dict:
    """After `resolved_cluster` or `adopt_state`: this package's
    `params_errors` over the adopted cluster, when there is one."""
    adopted = opts.get("once/cluster")
    if failed(opts) or adopted is None:
        return opts
    errors = params_errors(adopted)
    return {**opts, "blue/exit": 1, "blue/err": "\n".join(errors)} if errors else opts


def legacy_params(opts: dict, outputs: dict) -> dict:
    """The `params` a pre-adoption state describes. Before this package
    recorded `params`, its template output a scalar control plane
    (`control_plane_public_ip`, `control_plane_private_ip`) and two parallel
    worker lists; this builds control-plane node 0 from the scalars and
    worker i from the lists, names them by this package's own rule, and
    carries `vpc_id` from `digitalocean_vpc_id`. Refused — as the SDK's
    `StepError`, so `read_state` reports it and a delete fails closed — when
    the two lists disagree or the VPC id is absent or blank. Nothing else
    reads a legacy output after adoption."""
    publics = outputs.get("worker_public_ips")
    privates = outputs.get("worker_private_ips")
    publics = list(publics) if isinstance(publics, (list, tuple)) else []
    privates = list(privates) if isinstance(privates, (list, tuple)) else []
    vpc_id = outputs.get("digitalocean_vpc_id")
    if len(publics) != len(privates):
        raise StepError(f"legacy state lists {len(publics)} worker public addresses and "
                        f"{len(privates)} private addresses; refusing to guess the cluster")
    if not _non_blank(vpc_id):
        raise StepError("legacy state carries no digitalocean_vpc_id")

    def node(index: int, role: str, ip, vpc_ip) -> dict:
        return {"index": index, "role": role, "name": node_name(opts, role, index),
                "ip": ip, "vpc_ip": vpc_ip, "user": "root", "sudoer": "root"}

    return {"provider": "digitalocean",
            "vpc_id": vpc_id,
            "nodes": [node(0, "control-plane", outputs.get("control_plane_public_ip"),
                           outputs.get("control_plane_private_ip")),
                      *(node(i, "worker", ip, privates[i]) for i, ip in enumerate(publics))]}


async def state_output(opts: dict) -> dict | None:
    """The reader ONCE's `read_state` takes: the compute `params` recorded in
    the infrastructure state, None when the state holds no outputs at all,
    and the legacy translation above when it holds the pre-adoption outputs.
    The stage is initialised first so remote state is reachable without
    planning or changing cloud resources. An unreadable backend — a failed
    init, or whatever `blue.tofu` raises — is the SDK's `StepError`, which
    `read_state` turns into `{"error": message}`; create and delete treat
    that differently. Looked up on this module at call time, so tests can
    replace it."""
    dir = tool_dir(opts, infrastructure_tool)
    env = credential_env(opts, "provider-compute")
    init = await runtime.exec(["tofu", f"-chdir={dir}", "init",
                               "-input=false", "-no-color"], env=env)
    if init.exit != 0:
        raise StepError(f"tofu init failed: {init.err or init.out or '(no output)'}")
    outputs = await tofu.outputs(dir, env)
    if "params" in outputs:
        params = outputs.get("params")
        return params if isinstance(params, dict) else None
    if not outputs:
        return None
    return legacy_params(opts, outputs)


async def infrastructure_step(opts: dict) -> dict:
    dir = tool_dir(opts, infrastructure_tool)
    result = await tofu.tofu_with_spec(opts, infrastructure_specs(opts),
                                       dir=dir,
                                       env=credential_env(opts, "provider-compute"))
    if failed(result):
        return result
    if opts.get("blue/event") in ("delete", "build"):
        return result
    # A real converge never falls back: None outputs and a partial cluster
    # are refused by ONCE, then the VPC id by this package.
    return _with_params_check(cluster.resolved_cluster(
        validate.spec, opts, result, {}, cluster.output_params(result)))


async def load_infrastructure_step(opts: dict) -> dict:
    """Adopt the cluster from remote state without planning or changing
    cloud resources: ONCE's `read_state` over `state_output`, then
    `adopt_state`, which fails closed on an unreadable backend and refuses a
    partial cluster, then this package's `params_errors`. A readable state
    holding no compute leaves `once/cluster` absent, and the remote cleanup
    skips itself."""
    rendered = {**scaffold({**opts, "blue/event": "build"}, infrastructure_specs(opts)),
                "blue/event": opts.get("blue/event")}
    state = await cluster.read_state(rendered, state_output)
    return _with_params_check(
        cluster.adopt_state(validate.spec, rendered, str(opts.get("blue/event")), state))


def data_fn(opts: dict) -> dict:
    """Complete deterministic template data for build as well as create."""
    opts = ssh.with_machine_key(opts)
    adopted = opts.get("once/cluster") or {}
    return {**opts,
            "digitalocean_vpc_id": adopted.get("vpc_id") or fallback_vpc_id,
            "host-alias": utils.host_alias(opts),
            # Only what a `build` genuinely knows: whether the package owns the
            # key, and where the local play should point the identity file.
            "ssh-keygen": validate.keygen(opts),
            "ssh-config-identity-file": ssh_config.identity_file(opts),
            "kubernetes-minor": utils.kubernetes_minor(opts.get("kubernetes-version")),
            "kubernetes-package-version":
            utils.kubernetes_package_version(opts.get("kubernetes-version"))}


def _java_double(x: float) -> str:
    """Java's Double.toString, which is what Green's cheshire JSON emits for
    floats: decimal between 1e-3 and 1e7, `d.dddE±e` scientific outside it.
    Python's own repr disagrees exactly where scientific notation starts
    (0.0001 -> "1.0E-4"), and the goldens carry the Java form."""
    if math.isnan(x):
        return "NaN"
    if math.isinf(x):
        return "Infinity" if x > 0 else "-Infinity"
    negative = math.copysign(1.0, x) < 0
    magnitude = abs(x)
    if magnitude == 0.0:
        return "-0.0" if negative else "0.0"
    _sign, digits, exponent = Decimal(repr(magnitude)).as_tuple()
    digit_str = "".join(map(str, digits)).rstrip("0") or "0"
    dec_exp = exponent + len(digits) - 1
    if -3 <= dec_exp < 7:
        if dec_exp >= 0:
            whole = digit_str[:dec_exp + 1].ljust(dec_exp + 1, "0")
            frac = digit_str[dec_exp + 1:] or "0"
        else:
            whole = "0"
            frac = "0" * (-dec_exp - 1) + digit_str
        rendered = f"{whole}.{frac}"
    else:
        mantissa = digit_str[0] + "." + (digit_str[1:] or "0")
        rendered = f"{mantissa}E{dec_exp}"
    return ("-" if negative else "") + rendered


def _pretty(value, indent=0):
    """Cheshire's pretty JSON, byte for byte — Green's artifact contract."""
    if isinstance(value, (list, tuple)):
        if not value:
            return "[ ]"
        return "[ " + ", ".join(_pretty(item, indent) for item in value) + " ]"
    if isinstance(value, dict):
        if not value:
            return "{ }"
        pad = " " * (indent + 2)
        body = ",\n".join(f"{pad}{json.dumps(str(k))} : {_pretty(v, indent + 2)}"
                          for k, v in value.items())
        return "{\n" + body + "\n" + " " * indent + "}"
    if isinstance(value, float) and not isinstance(value, bool):
        return _java_double(value)
    return json.dumps(value)


def inventory(opts: dict) -> str:
    """The remote play's inventory: the control plane and the workers, each
    node under its own name, from `nodes`."""
    opts = ssh.with_machine_key(opts)
    all_nodes = nodes(opts)

    def host(n: dict) -> dict:
        # In keygen mode nothing guarantees an agent holds the generated key,
        # so the play is told which one to use; opt-out keeps the operator's
        # own arrangements, as it always did.
        entry = {"ansible_host": n.get("ip"), "ansible_user": n.get("user"),
                 "private_ip": n.get("vpc_ip")}
        if validate.keygen(opts):
            entry["ansible_ssh_private_key_file"] = opts.get("ssh-private-key-path")
        return entry

    def hosts(role: str) -> dict:
        return dict(sorted((n["name"], host(n)) for n in all_nodes if n.get("role") == role))

    return _pretty(
        {"all": {"children": {
            "control_plane": {"hosts": hosts("control-plane")},
            "workers": {"hosts": hosts("worker")},
            "k8s_cluster": {"children": {"control_plane": {}, "workers": {}}}}}})


def ansible_local_specs(opts: dict) -> list[dict]:
    dir = tool_dir(opts, ansible_local_tool)
    data = data_fn(opts)
    return [spec(template("ansible-local", "ansible.cfg"), f"{dir}/ansible.cfg", data),
            spec(template("ansible-local", "inventory.ini"), f"{dir}/inventory.ini", data),
            spec(template("ansible-local", "main.yml"), f"{dir}/main.yml", data)]


async def ansible_local_step(opts: dict) -> dict:
    dir = tool_dir(opts, ansible_local_tool)
    data = data_fn(opts)
    delete = opts.get("blue/event") == "delete"
    return await ansible_with_spec(
        opts, ansible_local_specs(opts),
        dir=dir,
        inventory="inventory.ini",
        playbooks={"create": "main.yml", "delete": "main.yml"},
        extra_vars={"host_alias": data["host-alias"],
                    "ip": entry_ip(opts),
                    "user": "root",
                    "block_state": "absent" if delete else "present"})


def ansible_remote_specs(opts: dict) -> list[dict]:
    dir = tool_dir(opts, ansible_remote_tool)
    data = data_fn(opts)
    return [spec(template("ansible-remote", "ansible.cfg"), f"{dir}/ansible.cfg", data),
            spec(template("ansible-remote", "create.yml"), f"{dir}/create.yml", data),
            spec(template("ansible-remote", "delete.yml"), f"{dir}/delete.yml", data),
            spec(template("ansible-remote", "gitops.yml"), f"{dir}/gitops.yml", data),
            raw_spec(f"{dir}/inventory.json", inventory(opts))]


async def ansible_remote_step(opts: dict) -> dict:
    """The remote play. On a delete it addresses the adopted cluster; a
    state that recorded no compute — the nodes are already gone — has
    nothing to clean up, and the step skips itself rather than render the
    fallbacks."""
    if opts.get("blue/event") == "delete" and opts.get("once/cluster") is None:
        return opts
    dir = tool_dir(opts, ansible_remote_tool)
    return await ansible_with_spec(
        opts, ansible_remote_specs(opts),
        dir=dir,
        inventory="inventory.json",
        playbooks={"create": "create.yml", "delete": "delete.yml"},
        host_key_checking=False)


def acceptance_specs(opts: dict) -> list[dict]:
    dir = tool_dir(opts, acceptance_tool)
    return [spec(template("acceptance", "acceptance.sh"),
                 f"{dir}/acceptance.sh", data_fn(opts))]


def process_result(opts: dict, label: str, result) -> dict:
    if result.exit == 0:
        return {**opts, "blue/exit": 0}
    return {**opts, "blue/exit": max(1, result.exit),
            "blue/err": (f"{label} failed: "
                         f"{result.err or result.out or '(no output)'}")}


async def acceptance_step(opts: dict) -> dict:
    rendered = scaffold(opts, acceptance_specs(opts))
    if opts.get("blue/event") in ("build", "delete"):
        return rendered
    return process_result(
        rendered, "acceptance",
        await runtime.exec(["bash", f"{tool_dir(opts, acceptance_tool)}/acceptance.sh"],
                           timeout_ms=25 * 60 * 1000))


async def generated_cleanup_step(opts: dict) -> dict:
    result = scaffold(opts, ansible_local_specs(opts))
    result = scaffold(result, ansible_remote_specs(opts))
    return scaffold(result, acceptance_specs(opts))
