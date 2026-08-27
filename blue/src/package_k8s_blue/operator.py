"""kubectl dispatch through the managed control-plane SSH alias, the port of
io.github.getcolors.k8s.operator.

No kubeconfig is copied locally. The remote root-owned admin.conf supplies
kubectl's credentials, while stdin/stdout/stderr remain attached so
`apply -f -` and ordinary terminal use work naturally.
"""

from __future__ import annotations

import os

from blue.cli import load_yaml, read_pars
from blue.process import posix_quote, run_inherit

from . import utils, validate

# Quote one remote POSIX-shell argument without allowing command injection.
shell_quote = posix_quote


def command(opts: dict, args: list[str]) -> list[str]:
    """The local ssh argv for remote kubectl against the admin kubeconfig."""
    remote = " ".join(shell_quote(arg)
                      for arg in ["sudo", "-n", "kubectl",
                                  "--kubeconfig=/etc/kubernetes/admin.conf", *args])
    return ["ssh", "-F", os.path.join(os.path.expanduser("~"), ".ssh/config"),
            "--", utils.host_alias(opts), remote]


# Run argv with the caller's terminal streams attached.
inherit_run = run_inherit


def run(state_file: str, _kind, args: list[str], runner=None,
        env: dict | None = None) -> dict:
    """Read desired state and invoke kubectl through SSH. Returns an outcome
    map."""
    runner = runner or (lambda argv: inherit_run(argv))
    env = dict(os.environ) if env is None else env
    try:
        if not os.path.isfile(state_file):
            return {"blue/exit": 2,
                    "blue/err": f"desired state file not found: {state_file}"}
        with open(state_file) as handle:
            state = load_yaml(handle.read()) or {}
        opts = read_pars({**state, "blue/state-file": os.path.abspath(state_file)}, env)
        errors = validate.env_errors(env)
        if errors:
            return {"blue/exit": 2, "blue/err": "\n".join(errors)}
        result = runner(command(opts, args))
        exit_code = getattr(result, "exit", None)
        if exit_code is None:
            exit_code = result.get("exit", 0)
        err = getattr(result, "err", None)
        if err is None and isinstance(result, dict):
            err = result.get("err")
        outcome = {"blue/exit": 0 if exit_code == 0 else max(1, exit_code)}
        if exit_code != 0 and err:
            outcome["blue/err"] = err
        return outcome
    except Exception as error:  # noqa: BLE001 — outcome maps, not tracebacks
        return {"blue/exit": 2, "blue/err": str(error) or type(error).__name__}
