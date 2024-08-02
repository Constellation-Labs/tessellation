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
          jre = p.graalvm11-ce;
        };

        bloopOverlay = f: p: {
          bloop =
            if system == "aarch64-darwin"
            then
              let x86Packages = import nixpkgs { system = "x86_64-darwin"; }; in
              x86Packages.bloop.override { inherit (f) jre; }
            else p.bloop;
        };

        nativeOverlay = f: p: {
          scala-cli-native = p.symlinkJoin
            {
              name = "scala-cli-native";
              paths = [ p.scala-cli ];
              buildInputs = [ p.makeWrapper ];
              postBuild = ''
                wrapProgram $out/bin/scala-cli \
                  --prefix LLVM_BIN : "${p.llvmPackages.clang}/bin"
              '';
            };
        };

        pkgs = import nixpkgs {
          inherit system;
          overlays = [ jreOverlay bloopOverlay nativeOverlay ];
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
              scala-cli-native
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

