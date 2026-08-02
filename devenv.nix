{ pkgs, ... }:
{
  languages.clojure.enable = true;
  languages.opentofu.enable = true;
  packages = with pkgs; [
    babashka curl jq xz openssh hcloud
    kubectl kubernetes-helm talosctl fluxcd
  ];
}
