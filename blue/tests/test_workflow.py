import os

import pytest
from blue.workflow import StepError
from blue.workflow import run as run_workflow
from package_k8s_blue import ssh, tools, workflow

from test_tools import cluster, without
from test_validate import base, optout

# The compute state is read once per run, through `tools.state_output`, on a
# real create or delete. Every lifecycle test stubs it: None is a readable
# state holding no compute, a dict is a recorded `params`, and a raise is a
# backend that cannot be read.

CREDENTIALS = {"COLORS_PAR_DO_TOKEN": "x", "COLORS_PAR_CLOUDFLARE_API_TOKEN": "y"}


@pytest.fixture
def state(monkeypatch):
    def install(params):
        async def stub(_opts):
            return params
        monkeypatch.setattr(tools, "state_output", stub)
    return install


@pytest.fixture
def unreadable(monkeypatch):
    # The shape `blue.tofu` raises: the SDK's StepError. Only that is an
    # unreadable backend; anything else propagates as a defect.
    async def boom(_opts):
        raise StepError("tofu output failed: no backend")
    monkeypatch.setattr(tools, "state_output", boom)


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
    # The keypair goes after the compute destroy (ssh-keypair.md §3.3).
    assert next_steps("delete", "k8s/infrastructure") == ("k8s/ssh-cleanup",)
    assert workflow.wire_fn("k8s/ssh-cleanup", {"blue/event": "delete"}) == (ssh.cleanup_step, "k8s/generated-cleanup")


async def test_a_build_fills_the_placeholder_key_paths():
    r = await workflow.start_step({**base, "blue/event": "build"}, {})
    assert r["blue/exit"] == 0
    assert r["ssh-private-key-path"] == "/home/build-placeholder/.ssh/k8s-test"
    assert r["ssh-keygen"] is True
    o = await workflow.start_step({**optout, "blue/event": "build"}, {})
    assert o["blue/exit"] == 0
    assert "ssh-private-key-path" not in o


async def start(opts, env=None):
    return await workflow.start_step(opts, env if env is not None else {})


async def test_build_and_dry_run_need_no_credentials_and_never_read_the_state(unreadable):
    # A throwing reader proves nothing on these paths reaches the backend.
    for opts in [{**base, "blue/event": "build"},
                 {**base, "blue/event": "create", "blue/dry-run": True},
                 {**base, "blue/event": "delete", "blue/dry-run": True}]:
        assert (await start(opts))["blue/exit"] == 0


async def test_real_lifecycle_needs_secrets_and_delete_override(state):
    state(None)
    assert (await start({**base, "blue/event": "create"}))["blue/exit"] == 2
    # The credentialed create runs opted out: in keygen mode a real create
    # generates the machine key, and no test may write into the operator's
    # ~/.ssh.
    assert (await start({**optout, "blue/event": "create"}, CREDENTIALS))["blue/exit"] == 0
    assert (await start({**base, "blue/event": "delete"}, CREDENTIALS))["blue/exit"] == 2
    assert (await start({**base, "blue/event": "delete"},
                        {**CREDENTIALS, "COLORS_PAR_COMPUTE_PREVENT_DESTROY": "false"})
            )["blue/exit"] == 0


async def test_a_provider_switch_is_refused_before_the_credentials(state):
    # Standard §4: the recorded provider is compared with the selected one
    # before the secrets, so the actionable error is what the operator reads.
    state({**cluster, "provider": "vultr"})
    for event in ["create", "delete"]:
        r = await start({**base, "blue/event": event, "compute-prevent-destroy": False})
        assert r["blue/exit"] == 2, event
        assert ("state holds a vultr machine; set provider-compute back to vultr "
                "and delete first") in r["blue/err"]
        assert "required credential is not set" not in r["blue/err"]


async def test_legacy_state_is_accepted_on_the_default_provider(state):
    # A recorded state without `provider` predates the package recording one:
    # it is a digitalocean cluster, which is what is selected.
    state(without(cluster, "provider"))
    for event in ["create", "delete"]:
        r = await start({**base, "blue/event": event, "compute-prevent-destroy": False})
        assert r["blue/exit"] == 2, event
        assert "state holds" not in r["blue/err"]
        assert "required credential is not set" in r["blue/err"]


async def test_a_matching_provider_passes_to_the_credentials(state):
    state(cluster)
    r = await start({**base, "blue/event": "create"})
    assert r["blue/exit"] == 2
    assert "state holds" not in r["blue/err"]
    assert "COLORS_PAR_DO_TOKEN" in r["blue/err"]


async def test_an_unreadable_backend_counts_as_no_state_on_create(unreadable):
    # A fresh clone has no readable state and must still be able to create.
    r = await start({**base, "blue/event": "create"})
    assert r["blue/exit"] == 2
    assert "could not read" not in r["blue/err"]
    assert "state holds" not in r["blue/err"]
    assert "COLORS_PAR_DO_TOKEN" in r["blue/err"]


async def test_a_real_create_on_a_fresh_work_directory_reports_the_credentials_not_a_crash(tmp_path):
    # No state stub: the real `state_output` runs against a work directory
    # that holds no stage yet, as a fresh clone's does. The init cannot run
    # there, which the reader reports as the SDK's StepError; ONCE's
    # `read_state` counts that as an unreadable state, so the create reports
    # its credentials instead of crashing.
    r = await start({**base, "workdir": str(tmp_path), "blue/event": "create"})
    assert r["blue/exit"] == 2
    assert "COLORS_PAR_DO_TOKEN" in r["blue/err"]
    assert "could not read" not in r["blue/err"]
    assert os.listdir(tmp_path) == []


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
