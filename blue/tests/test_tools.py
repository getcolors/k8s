import json
from pathlib import Path

import pytest
from blue.runtime import ExecResult
from blue.scaffold import scaffold
from blue.workflow import StepError
from package_k8s_blue import tools

from test_validate import base, optout

# A recorded `params`, as the compute stage outputs it after adoption.
cluster = {
    "provider": "digitalocean",
    "vpc_id": "9c0a1b2c-3d4e-4f60-8a7b-1c2d3e4f5a6b",
    "nodes": [
        {"index": 0, "role": "control-plane", "name": "k8s-test-control-plane-1",
         "ip": "203.0.113.1", "vpc_ip": "10.20.0.2", "user": "root", "sudoer": "root"},
        {"index": 0, "role": "worker", "name": "k8s-test-worker-1",
         "ip": "203.0.113.2", "vpc_ip": "10.20.0.3", "user": "root", "sudoer": "root"},
    ],
}

# The pre-adoption state, in the recorded scalar-plus-list shape.
legacy_outputs = json.loads(
    (Path(__file__).resolve().parents[2] / "test" / "fixtures" / "legacy-outputs.json").read_text())


def without(d: dict, key: str) -> dict:
    return {k: v for k, v in d.items() if k != key}


def only_nodes(params: dict, keep: list[int]) -> dict:
    return {**params, "nodes": [n for i, n in enumerate(params["nodes"]) if i in keep]}


def tofu_stub(monkeypatch, outputs: dict | None, init_exit: int = 0):
    """tofu as `runtime.exec` sees it: init (and apply) succeed, and the
    output read returns `outputs` in `tofu output -json` form."""
    async def fake_exec(cmd, **_kwargs):
        if "output" in cmd:
            body = json.dumps({k: {"value": v} for k, v in (outputs or {}).items()})
            return ExecResult(exit=0, out=body, err="")
        return ExecResult(exit=init_exit, out="", err="no backend" if init_exit else "")
    monkeypatch.setattr(tools.runtime, "exec", fake_exec)


def test_stage_names_are_package_specific():
    assert tools.infrastructure_tool == "k8s-infrastructure"
    assert tools.ansible_remote_tool == "k8s-ansible-remote"


def test_inventory_separates_control_plane_and_worker():
    parsed = json.loads(tools.inventory({**base, "once/cluster": cluster}))
    assert (parsed["all"]["children"]["control_plane"]["hosts"]
            ["k8s-test-control-plane-1"]["private_ip"] == "10.20.0.2")
    assert (parsed["all"]["children"]["workers"]["hosts"]
            ["k8s-test-worker-1"]["ansible_host"] == "203.0.113.2")


def test_the_inventory_names_the_generated_key_in_keygen_mode_only():
    # On a build the placeholder; opt-out keeps the operator's own arrangements.
    built = json.loads(tools.inventory({**base, "once/cluster": cluster, "blue/event": "build"}))
    assert (built["all"]["children"]["workers"]["hosts"]["k8s-test-worker-1"]
            ["ansible_ssh_private_key_file"] == "/home/build-placeholder/.ssh/k8s-test")
    opted_out = json.loads(tools.inventory({**optout, "once/cluster": cluster}))
    assert "ansible_ssh_private_key_file" not in opted_out["all"]["children"]["workers"]["hosts"]["k8s-test-worker-1"]
    # The local play is told the identity file the same way.
    assert tools.ansible_local_specs({**base, "blue/event": "build"})[0]["data"]["ssh-config-identity-file"] == "~/.ssh/k8s-test"
    assert tools.ansible_local_specs(optout)[0]["data"]["ssh-keygen"] is False


def test_build_renders_fallback_nodes_under_the_packages_own_names():
    # No adopted cluster: ONCE's fallbacks on TEST-NET-1 and the owned VPC's
    # CIDR, named the way the template names the droplets.
    assert tools.nodes(base) == [
        {"role": "control-plane", "index": 0, "name": "k8s-test-control-plane-1",
         "ip": "192.0.2.10", "vpc_ip": "10.20.0.10", "user": "root", "sudoer": "root"},
        {"role": "worker", "index": 0, "name": "k8s-test-worker-1",
         "ip": "192.0.2.11", "vpc_ip": "10.20.0.11", "user": "root", "sudoer": "root"},
    ]
    assert tools.entry_ip(base) == "192.0.2.10"
    assert tools.entry_ip({**base, "once/cluster": cluster}) == "203.0.113.1"
    assert tools.data_fn(base)["digitalocean_vpc_id"] == "00000000-0000-0000-0000-000000000000"
    assert tools.data_fn({**base, "once/cluster": cluster})["digitalocean_vpc_id"] == cluster["vpc_id"]


def test_params_errors_require_the_vpc_id():
    assert tools.params_errors(without(cluster, "vpc_id")) == ["compute state carries no vpc_id"]
    assert tools.params_errors({**cluster, "vpc_id": " "}) == ["compute state carries no vpc_id"]
    assert tools.params_errors(cluster) == []


async def test_the_reader_returns_params_nothing_or_the_legacy_translation(tmp_path, monkeypatch):
    opts = {**base, "workdir": str(tmp_path)}
    tofu_stub(monkeypatch, {"params": cluster})
    assert await tools.state_output(opts) == cluster
    tofu_stub(monkeypatch, {})
    assert await tools.state_output(opts) is None
    tofu_stub(monkeypatch, legacy_outputs)
    assert await tools.state_output(opts) == cluster
    # The worker lists must agree.
    tofu_stub(monkeypatch, {**legacy_outputs, "worker_private_ips": []})
    with pytest.raises(StepError, match="legacy state lists 1 worker public addresses and "
                                        "0 private addresses; refusing to guess the cluster"):
        await tools.state_output(opts)
    # The VPC id must be recorded.
    for outputs in [without(legacy_outputs, "digitalocean_vpc_id"),
                    {**legacy_outputs, "digitalocean_vpc_id": ""}]:
        tofu_stub(monkeypatch, outputs)
        with pytest.raises(StepError, match="legacy state carries no digitalocean_vpc_id"):
            await tools.state_output(opts)
    # A failed init is the SDK's step error.
    tofu_stub(monkeypatch, {}, init_exit=1)
    with pytest.raises(StepError, match="no backend"):
        await tools.state_output(opts)


def test_infrastructure_renders_owned_vpc_nodes_and_firewalls(tmp_path):
    opts = {**base, "workdir": str(tmp_path), "profile": "render",
            "blue/event": "build"}
    scaffold(opts, tools.infrastructure_specs(opts))
    hcl = open(f"{tools.tool_dir(opts, tools.infrastructure_tool)}/main.tf").read()
    assert 'resource "digitalocean_vpc" "cluster"' in hcl
    assert 'resource "digitalocean_droplet" "control_plane"' in hcl
    assert "203.0.113.10/32" in hcl
    assert "prevent_destroy = true" in hcl
    assert "DIGITALOCEAN_TOKEN" not in hcl


async def test_remote_render_pins_components_and_keeps_secret_lookups(tmp_path):
    opts = {**base, "workdir": str(tmp_path), "profile": "render",
            "blue/event": "build"}
    result = await tools.ansible_remote_step(opts)
    root = tools.tool_dir(result, tools.ansible_remote_tool)
    play = open(f"{root}/create.yml").read()
    assert "v1.36.3" in play
    assert "v0.28.8" in play
    assert "v0.1.68" in play
    assert "COLORS_PAR_DO_TOKEN" in play
    assert "COLORS_PAR_CLOUDFLARE_API_TOKEN" in play
    assert "fixture-secret" not in play


async def load_with(tmp_path, monkeypatch, state):
    """`load_infrastructure_step` on a delete over a stubbed `state_output`:
    `state` is what the reader returns, or an exception to raise."""
    async def stub(_opts):
        if isinstance(state, BaseException):
            raise state
        return state
    monkeypatch.setattr(tools, "state_output", stub)
    monkeypatch.setenv("HOME", str(tmp_path))
    return await tools.load_infrastructure_step(
        {**base, "workdir": str(tmp_path), "blue/event": "delete"})


async def test_a_real_delete_adopts_the_recorded_cluster(tmp_path, monkeypatch):
    adopted = await load_with(tmp_path, monkeypatch, cluster)
    assert adopted["blue/exit"] == 0
    assert adopted["blue/event"] == "delete"
    assert adopted["once/cluster"] == cluster
    assert tools.entry_ip(adopted) == "203.0.113.1"
    # A readable state holding no compute leaves the cluster unadopted; the
    # remote cleanup then skips itself.
    empty = await load_with(tmp_path, monkeypatch, None)
    assert empty["blue/exit"] == 0
    assert "once/cluster" not in empty


async def test_a_real_delete_refuses_a_partial_cluster(tmp_path, monkeypatch):
    partial = await load_with(tmp_path, monkeypatch, only_nodes(cluster, [0]))
    assert partial["blue/exit"] == 1
    assert partial["blue/err"] == \
        "the compute stage did not report nodes this package declares: worker-0"
    no_vpc = await load_with(tmp_path, monkeypatch, without(cluster, "vpc_id"))
    assert no_vpc["blue/exit"] == 1
    assert no_vpc["blue/err"] == "compute state carries no vpc_id"


async def test_an_unreadable_backend_fails_a_real_delete_closed(tmp_path, monkeypatch):
    # Swallowing it is how a teardown ends up converging against 192.0.2.10.
    real_reader = tools.state_output
    result = await load_with(tmp_path, monkeypatch, StepError("tofu output failed: no backend"))
    assert result["blue/exit"] == 1
    assert "could not read the infrastructure state for the delete cleanup" in result["blue/err"]
    assert "no backend" in result["blue/err"]
    # A legacy state the reader refuses is the same fail-closed path.
    monkeypatch.setattr(tools, "state_output", real_reader)
    tofu_stub(monkeypatch, without(legacy_outputs, "digitalocean_vpc_id"))
    legacy = await tools.load_infrastructure_step(
        {**base, "workdir": str(tmp_path), "blue/event": "delete"})
    assert legacy["blue/exit"] == 1
    assert "legacy state carries no digitalocean_vpc_id" in legacy["blue/err"]


async def test_a_real_create_refuses_nil_and_partial_compute_outputs(tmp_path, monkeypatch):
    # The real `tofu_with_spec` over a stubbed tofu: init and apply succeed,
    # and the output read returns a `params` output, or nothing.
    async def create(outputs):
        tofu_stub(monkeypatch, {"params": outputs} if outputs is not None else {})
        return await tools.infrastructure_step(
            {**base, "workdir": str(tmp_path), "blue/event": "create"})

    none = await create(None)
    assert none["blue/exit"] == 1
    assert none["blue/err"] == ("compute produced no params output; refusing to converge "
                                "against the documentation addresses")
    partial = await create(only_nodes(cluster, [1]))
    assert partial["blue/exit"] == 1
    assert partial["blue/err"] == \
        "the compute stage did not report nodes this package declares: control-plane-0"
    no_vpc = await create(without(cluster, "vpc_id"))
    assert no_vpc["blue/exit"] == 1
    assert no_vpc["blue/err"] == "compute state carries no vpc_id"
    whole = await create(cluster)
    assert whole["blue/exit"] == 0
    assert whole["once/cluster"] == cluster


async def test_repeated_delete_skips_remote_cleanup_after_nodes_are_gone():
    opts = {**base, "blue/event": "delete"}
    assert await tools.ansible_remote_step(opts) is opts


def test_workdir_resolves_beside_state():
    assert tools.tool_dir({"workdir": ".colors", "profile": "p",
                           "blue/state-file": "/srv/project/colors.yml"},
                          tools.infrastructure_tool) \
        == "/srv/project/.colors/p/k8s-infrastructure"
