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
          jre = p.jdk11;
        };

        bloopOverlay = f: p: {
          bloop =
            if system == "aarch64-darwin"
            then
              let x86Packages = import nixpkgs { system = "x86_64-darwin"; }; in
              x86Packages.bloop.override { inherit (f) jre; }
            else p.bloop;
        };

        pkgs = import nixpkgs {
          inherit system;
          overlays = [ jreOverlay bloopOverlay ];
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
              nodejs
            ];

            shellHook = ''
              JAVA_HOME="${pkgs.jre}"
            '';
          };
        };
      }
    );
}

