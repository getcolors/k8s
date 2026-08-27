import os

from blue.workflow import run as run_workflow
from package_k8s_blue import tools, workflow

from test_validate import base


def next_steps(event, step):
    return (workflow.wire_fn(step, {"blue/event": event}) or ())[1:]


def test_create_orders_infrastructure_before_kubeadm_and_acceptance():
    assert next_steps("create", "k8s/start") == ("k8s/infrastructure",)
    assert next_steps("create", "k8s/infrastructure") == ("k8s/ansible-local",)
    assert next_steps("create", "k8s/ansible-local") == ("k8s/ansible-remote",)
    assert next_steps("create", "k8s/ansible-remote") == ("k8s/acceptance",)


def test_delete_loads_state_and_removes_load_balancer_before_infrastructure():
    assert next_steps("delete", "k8s/start") == ("k8s/load-infrastructure",)
    assert next_steps("delete", "k8s/load-infrastructure") == ("k8s/ansible-remote",)
    assert next_steps("delete", "k8s/ansible-remote") == ("k8s/ansible-local",)
    assert next_steps("delete", "k8s/ansible-local") == ("k8s/infrastructure",)


async def start(opts, env=None):
    return await workflow.start_step(opts, env if env is not None else {})


async def test_build_and_dry_run_need_no_credentials():
    assert (await start({**base, "blue/event": "build"}))["blue/exit"] == 0
    assert (await start({**base, "blue/event": "create",
                         "blue/dry-run": True}))["blue/exit"] == 0


async def test_real_lifecycle_needs_secrets_and_delete_override():
    assert (await start({**base, "blue/event": "create"}))["blue/exit"] == 2
    env = {"COLORS_PAR_DO_TOKEN": "x", "COLORS_PAR_CLOUDFLARE_API_TOKEN": "y"}
    assert (await start({**base, "blue/event": "create"}, env))["blue/exit"] == 0
    assert (await start({**base, "blue/event": "delete"}, env))["blue/exit"] == 2
    assert (await start({**base, "blue/event": "delete"},
                        {**env, "COLORS_PAR_COMPUTE_PREVENT_DESTROY": "false"})
            )["blue/exit"] == 0


async def test_backend_key_is_package_specific(tmp_path):
    opts = {**base, "profile": "p", "workdir": str(tmp_path),
            "provider-backend": "r2", "r2-bucket": "b", "r2-endpoint": "https://r2"}
    workflow.backend_advice(tools.infrastructure_tool)(opts)
    backend = open(f"{tools.tool_dir(opts, tools.infrastructure_tool)}"
                   "/backend.tf.json").read()
    assert "p/k8s-infrastructure.tfstate" in backend


async def test_whole_build_renders_all_stages(tmp_path):
    result = await run_workflow(workflow.k8s_workflow,
                                {**base, "blue/event": "build",
                                 "workdir": str(tmp_path), "profile": "built"})
    assert result["blue/exit"] == 0
    for file in ["k8s-infrastructure/main.tf",
                 "k8s-infrastructure/backend.tf.json",
                 "k8s-ansible-local/main.yml",
                 "k8s-ansible-remote/create.yml",
                 "k8s-ansible-remote/delete.yml",
                 "k8s-ansible-remote/inventory.json",
                 "k8s-acceptance/acceptance.sh"]:
        assert os.path.isfile(tmp_path / "built" / file), f"{file} should exist"


async def test_dry_run_touches_nothing(tmp_path):
    result = await run_workflow(workflow.k8s_workflow,
                                {**base, "blue/event": "create",
                                 "blue/dry-run": True,
                                 "workdir": str(tmp_path), "profile": "dry"})
    assert result["blue/exit"] == 0
    assert os.listdir(tmp_path) == []
