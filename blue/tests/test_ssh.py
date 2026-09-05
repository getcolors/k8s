import os

from package_k8s_blue import ssh, validate

from test_validate import base, optout


def test_keygen_mode_is_the_absence_of_a_supplied_key():
    assert validate.keygen(base)
    assert not validate.keygen(optout)


def test_a_build_never_names_the_operators_home():
    # Committed goldens must mean the same thing on every workstation, so a
    # build renders a fixed placeholder rather than reading ~/.ssh.
    opts = ssh.with_machine_key({**base, "blue/event": "build"})
    assert opts["ssh-private-key-path"] == "/home/build-placeholder/.ssh/k8s-test"
    assert opts["ssh-public-key-path"] == "/home/build-placeholder/.ssh/k8s-test.pub"
    # The placeholder lands on the provider's own machine-key key.
    assert opts["digitalocean-ssh-keys"] == "/home/build-placeholder/.ssh/k8s-test.pub"
    assert "build-placeholder" not in str(os.environ.get("HOME"))


def test_a_dry_run_is_held_to_the_same_rule_as_a_build():
    assert ssh.rendered_only({"blue/event": "build"})
    assert ssh.rendered_only({"blue/event": "create", "blue/dry-run": True})
    assert not ssh.rendered_only({"blue/event": "create"})


def test_real_events_render_the_real_path():
    opts = ssh.with_machine_key({**base, "blue/event": "create"})
    assert "build-placeholder" not in opts["ssh-private-key-path"]
    assert opts["ssh-private-key-path"].endswith("/.ssh/k8s-test")


def test_opt_out_opts_pass_through_untouched():
    opts = {**optout, "blue/event": "build"}
    assert ssh.with_machine_key(opts) == opts
