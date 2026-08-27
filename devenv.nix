{ pkgs, ... }:
{
  languages.clojure.enable = true;
  languages.opentofu.enable = true;
  packages = with pkgs; [
    ansible babashka bun curl doctl jq openssh uv
    kubectl kubernetes-helm fluxcd
  ];
}
