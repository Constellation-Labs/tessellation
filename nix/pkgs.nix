{ jdk }:

let
  pinned = import ./pinned.nix;
  config = import ./config.nix { inherit jdk; };
  pkgs   = import pinned.nixpkgs {
    inherit config;
    overlays = [ (self: super: {
      jre = super.${jdk};
    })];
  };
in
  pkgs
