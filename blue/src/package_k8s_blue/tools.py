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

from . import utils, validate

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
    dir = tool_dir(opts, infrastructure_tool)
    data = {**opts,
            "digitalocean-ssh-sources-json":
            _compact_json(opts.get("digitalocean-ssh-sources")),
            "digitalocean-api-sources-json":
            _compact_json(opts.get("digitalocean-api-sources"))}
    return [spec(template("infrastructure", "main.tf"), f"{dir}/main.tf", data)]


fallback_outputs = {
    "digitalocean_vpc_id": "00000000-0000-0000-0000-000000000000",
    "control_plane_public_ip": "192.168.0.10",
    "control_plane_private_ip": "10.20.0.10",
    "worker_public_ips": ["192.168.0.11"],
    "worker_private_ips": ["10.20.0.11"],
}


def _output_map(result: dict) -> dict | None:
    return result.get("k8s/outputs")


async def infrastructure_step(opts: dict) -> dict:
    dir = tool_dir(opts, infrastructure_tool)
    result = await tofu.tofu_with_spec(opts, infrastructure_specs(opts),
                                       dir=dir,
                                       env=credential_env(opts, "provider-compute"),
                                       output_key="k8s/outputs")
    if failed(result):
        return result
    if opts.get("blue/event") == "delete":
        return result
    if opts.get("blue/event") == "build":
        return {**result, **fallback_outputs}
    return {**result, **fallback_outputs, **(_output_map(result) or {})}


async def load_infrastructure_step(opts: dict) -> dict:
    """Load node addresses from remote state without planning or changing
    cloud resources."""
    dir = tool_dir(opts, infrastructure_tool)
    rendered = {**scaffold({**opts, "blue/event": "build"}, infrastructure_specs(opts)),
                "blue/event": opts.get("blue/event")}
    env = credential_env(opts, "provider-compute")
    init = await runtime.exec(["tofu", f"-chdir={dir}", "init",
                               "-input=false", "-no-color"], env=env)
    if init.exit != 0:
        return process_result(rendered, "infrastructure state initialization", init)
    try:
        outputs = await tofu.outputs(dir, env)
        return {**rendered, **fallback_outputs, **outputs,
                "k8s/infrastructure-present?": "control_plane_public_ip" in outputs}
    except Exception as error:  # noqa: BLE001 — outcome maps, not tracebacks
        return {**rendered, "blue/exit": 1,
                "blue/err": ("infrastructure state output failed: "
                             + (str(error) or type(error).__name__))}


def data_fn(opts: dict) -> dict:
    """Complete deterministic template data for build as well as create."""
    return {**fallback_outputs, **opts,
            "host-alias": utils.host_alias(opts),
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
    """JSON inventory separating the control plane from the workers."""
    data = data_fn(opts)
    cp_name = f"{data.get('digitalocean-name')}-control-plane-1"
    workers = {f"{data.get('digitalocean-name')}-worker-{index + 1}":
               {"ansible_host": public, "ansible_user": "root",
                "private_ip": private}
               for index, (public, private)
               in enumerate(zip(data.get("worker_public_ips") or [],
                                data.get("worker_private_ips") or []))}
    return _pretty(
        {"all": {"children": {
            "control_plane": {"hosts": {
                cp_name: {"ansible_host": data.get("control_plane_public_ip"),
                          "ansible_user": "root",
                          "private_ip": data.get("control_plane_private_ip")}}},
            "workers": {"hosts": dict(sorted(workers.items()))},
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
                    "ip": data.get("control_plane_public_ip"),
                    "block_state": "absent" if delete else "present"})


def ansible_remote_specs(opts: dict) -> list[dict]:
    dir = tool_dir(opts, ansible_remote_tool)
    data = data_fn(opts)
    return [spec(template("ansible-remote", "ansible.cfg"), f"{dir}/ansible.cfg", data),
            spec(template("ansible-remote", "create.yml"), f"{dir}/create.yml", data),
            spec(template("ansible-remote", "delete.yml"), f"{dir}/delete.yml", data),
            spec(template("ansible-remote", "gitops.yml"), f"{dir}/gitops.yml", data),
            raw_spec(f"{dir}/inventory.json", inventory(data))]


async def ansible_remote_step(opts: dict) -> dict:
    if (opts.get("blue/event") == "delete"
            and opts.get("k8s/infrastructure-present?") is False):
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
