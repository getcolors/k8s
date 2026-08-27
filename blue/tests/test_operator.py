from package_k8s_blue import operator


def test_command_uses_ssh_and_remote_admin_kubeconfig():
    command = operator.command({"profile": "k8s-digitalocean"}, ["get", "nodes"])
    assert command[0] == "ssh"
    assert command[1] == "-F"
    assert command[2].endswith("/.ssh/config")
    assert command[3:] == [
        "--", "k8s-digitalocean",
        "'sudo' '-n' 'kubectl' '--kubeconfig=/etc/kubernetes/admin.conf' "
        "'get' 'nodes'",
    ]


def test_arguments_are_shell_quoted():
    assert "'pods; id'" in operator.command({"profile": "p"},
                                            ["get", "pods; id"])[-1]


def test_run_refuses_profile_overlay(tmp_path):
    file = tmp_path / "colors.yml"
    file.write_text("profile: demo\n")
    result = operator.run(str(file), "kubectl", [],
                          lambda _argv: {"exit": 0},
                          {"COLORS_PAR_PROFILE": "other"})
    assert result["blue/exit"] == 2
