from package_k8s_blue import ssh_config

from test_validate import base


def test_the_alias_is_the_profile_and_the_identity_file_stays_unexpanded():
    assert ssh_config.host_alias(base) == "k8s-test"
    assert ssh_config.identity_file(base) == "~/.ssh/k8s-test"


def test_the_superseded_package_prefixed_block_is_still_ours_while_the_migration_is_in_flight():
    old = ["# BEGIN k8s k8s-test ANSIBLE MANAGED BLOCK",
           "Host k8s-test", "  HostName 1.2.3.4",
           "# END k8s k8s-test ANSIBLE MANAGED BLOCK"]
    assert ssh_config.foreign_stanza_line(old, "k8s-test") is None
    current = ["# BEGIN k8s-test ANSIBLE MANAGED BLOCK",
               "Host k8s-test", "  HostName 1.2.3.4",
               "# END k8s-test ANSIBLE MANAGED BLOCK"]
    assert ssh_config.foreign_stanza_line(current, "k8s-test") is None
    assert ssh_config.foreign_stanza_line(["Host k8s-test", "  HostName 9.9.9.9"], "k8s-test") == 1


def test_a_global_option_above_the_first_host_blocks_the_run():
    assert ssh_config.leading_option_line(["ServerAliveInterval 60", "Host x"]) == 1
    assert ssh_config.leading_option_line(["# a comment", "", "Host x", "  User root"]) is None


def test_the_refusal_is_reported_as_a_failed_step(monkeypatch, tmp_path):
    config = tmp_path / ".ssh" / "config"
    config.parent.mkdir(parents=True)
    config.write_text("Host k8s-test\n  HostName 9.9.9.9\n")
    monkeypatch.setenv("HOME", str(tmp_path))
    refused = ssh_config.preflight(base)
    assert refused["blue/exit"] == 1
    assert "k8s-test" in refused["blue/err"]
    config.write_text("# BEGIN k8s k8s-test ANSIBLE MANAGED BLOCK\nHost k8s-test\n  HostName 1.1.1.1\n# END k8s k8s-test ANSIBLE MANAGED BLOCK\n")
    assert ssh_config.preflight(base).get("blue/exit", 0) == 0
