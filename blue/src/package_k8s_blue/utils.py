"""Launcher contract and path/version helpers, the port of
io.github.getcolors.k8s.utils."""

from __future__ import annotations

from blue.cli import stage_dir

# Bump on any change a launcher pinned to an older commit could not survive.
CONTRACT = 2


def tool_dir(opts: dict, tool: str) -> str:
    """Resolve a stage beside colors.yml, never relative to the caller."""
    return stage_dir(opts, tool, default_profile="k8s")


def unprefix_v(version) -> str:
    text = str(version)
    return text[1:] if text.startswith("v") else text


def kubernetes_minor(version) -> str:
    major, minor = unprefix_v(version).split(".")[:2]
    return f"v{major}.{minor}"


def kubernetes_package_version(version) -> str:
    return f"{unprefix_v(version)}-1.1"


def host_alias(opts: dict) -> str:
    """The managed SSH alias, derived from the project profile."""
    profile = str(opts.get("profile") or "")
    return profile if profile else "k8s"
