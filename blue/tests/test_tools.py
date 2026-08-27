import json

from blue.runtime import ExecResult
from blue.scaffold import scaffold
from package_k8s_blue import tools

from test_validate import base


def test_stage_names_are_package_specific():
    assert tools.infrastructure_tool == "k8s-infrastructure"
    assert tools.ansible_remote_tool == "k8s-ansible-remote"


def test_inventory_separates_control_plane_and_worker():
    parsed = json.loads(tools.inventory(
        {**base,
         "control_plane_public_ip": "203.0.113.1",
         "control_plane_private_ip": "10.20.0.2",
         "worker_public_ips": ["203.0.113.2"],
         "worker_private_ips": ["10.20.0.3"]}))
    assert (parsed["all"]["children"]["control_plane"]["hosts"]
            ["k8s-test-control-plane-1"]["private_ip"] == "10.20.0.2")
    assert (parsed["all"]["children"]["workers"]["hosts"]
            ["k8s-test-worker-1"]["ansible_host"] == "203.0.113.2")


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


async def test_load_infrastructure_accepts_the_system_environment(tmp_path, monkeypatch):
    opts = {**base, "workdir": str(tmp_path), "blue/event": "delete"}

    async def fake_exec(cmd, **_kwargs):
        if "output" in cmd:
            return ExecResult(
                exit=0,
                out='{"control_plane_public_ip":{"value":"203.0.113.1"}}',
                err="")
        return ExecResult(exit=0, out="", err="")

    monkeypatch.setattr(tools.runtime, "exec", fake_exec)
    result = await tools.load_infrastructure_step(opts)
    assert result["blue/event"] == "delete"
    assert result["k8s/infrastructure-present?"]
    assert result["control_plane_public_ip"] == "203.0.113.1"


async def test_repeated_delete_skips_remote_cleanup_after_nodes_are_gone():
    opts = {**base, "blue/event": "delete", "k8s/infrastructure-present?": False}
    assert await tools.ansible_remote_step(opts) is opts


def test_workdir_resolves_beside_state():
    assert tools.tool_dir({"workdir": ".colors", "profile": "p",
                           "blue/state-file": "/srv/project/colors.yml"},
                          tools.infrastructure_tool) \
        == "/srv/project/.colors/p/k8s-infrastructure"
