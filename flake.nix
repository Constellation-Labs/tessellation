{
  description = "Scala development shell";

  inputs = {
    nixpkgs.url = github:nixos/nixpkgs/nixpkgs-unstable;
    flake-utils.url = github:numtide/flake-utils;
  };

  outputs = { self, nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        jreOverlay = f: p: {
          jre = p.jdk11_headless;
        };

        pkgs = import nixpkgs {
          inherit system;
          overlays = [ jreOverlay ];
        };
      in
      {
        devShells = rec {
          default = scala;

          scala = pkgs.mkShell {
            name = "scala-dev-shell";

            buildInputs = with pkgs; [
              coursier
              jre
              sbt
              bloop
            ];

            shellHook = ''
              JAVA_HOME="${pkgs.jre}"
            '';
          };
        };
      }
    );
}

