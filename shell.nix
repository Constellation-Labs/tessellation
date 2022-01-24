{ jdk ? "jdk11" }:

let
  pkgs = import nix/pkgs.nix { inherit jdk; };
in
  pkgs.mkShell {
    name = "tessellation-nix";

    buildInputs = [
      pkgs.coursier
      pkgs.${jdk}
      pkgs.sbt
    ];

    shellHook = ''
      set -o allexport; source .env; set +o allexport;
    '';
  }
