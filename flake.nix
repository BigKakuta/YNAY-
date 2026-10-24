{
  description = "YNAY - a self-hosted, MCP-native zero-based budgeting app";

  inputs = {
    # Lockable tarball of the stable channel. Equivalent to `github:NixOS/nixpkgs/nixos-26.05`
    # (the tarball form is used because some build environments cannot reach the GitHub API);
    # either URL works, `nix flake update` just follows the channel.
    nixpkgs.url = "https://channels.nixos.org/nixos-26.05/nixexprs.tar.xz";
  };

  outputs =
    { self, nixpkgs }:
    let
      inherit (nixpkgs) lib;
      systems = [
        "x86_64-linux"
        "aarch64-linux"
      ];
      forAllSystems = f: lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});

      # Single source of truth for the version: backend/build.gradle.kts.
      version = builtins.head (
        builtins.match ''.*[[:space:]]version = "([^"]+)".*'' (builtins.readFile ./backend/build.gradle.kts)
      );

      # The frontend lives in ./frontend; until it exists a placeholder page is bundled instead.
      frontendSrc =
        if builtins.pathExists ./frontend/package.json then
          lib.fileset.toSource {
            root = ./frontend;
            fileset = lib.fileset.difference ./frontend (
              lib.fileset.unions [
                (lib.fileset.maybeMissing ./frontend/node_modules)
                (lib.fileset.maybeMissing ./frontend/dist)
              ]
            );
          }
        else
          null;

      mkPackages = pkgs: rec {
        frontend = pkgs.callPackage ./nix/frontend.nix {
          inherit version;
          src = frontendSrc;
        };
        backend = pkgs.callPackage ./nix/backend.nix { inherit version frontend; };
        dockerImage = pkgs.callPackage ./nix/docker.nix { inherit version backend; };
        default = backend;
      };
    in
    {
      packages = forAllSystems mkPackages;

      overlays.default = final: prev: { ynay = (mkPackages final).backend; };

      # services.ynay; the package defaults to this flake's build for the host platform.
      nixosModules.default =
        { pkgs, ... }:
        {
          imports = [ ./nix/module.nix ];
          services.ynay.package = lib.mkDefault self.packages.${pkgs.stdenv.hostPlatform.system}.default;
        };
      nixosModules.ynay = self.nixosModules.default;

      apps = forAllSystems (pkgs: {
        default = {
          type = "app";
          program = lib.getExe self.packages.${pkgs.stdenv.hostPlatform.system}.backend;
          meta.description = "Run the YNAY server (configure with YNAY_* environment variables)";
        };
        update-hashes = {
          type = "app";
          program = toString (
            pkgs.writeShellScript "ynay-update-hashes" ''
              # Must run inside a checkout: the script rewrites files in the working tree.
              root="$(${lib.getExe pkgs.git} rev-parse --show-toplevel)"
              exec ${lib.getExe pkgs.bash} "$root/nix/update-hashes.sh" "$@"
            ''
          );
          meta.description = "Refresh nix/frontend-npm-deps.hash and nix/backend-deps.json";
        };
      });

      checks = forAllSystems (
        pkgs:
        let
          p = self.packages.${pkgs.stdenv.hostPlatform.system};
        in
        {
          inherit (p) frontend backend dockerImage;
        }
        // lib.optionalAttrs pkgs.stdenv.hostPlatform.isLinux {
          nixos = pkgs.testers.runNixOSTest (import ./nix/test.nix { inherit self; });
        }
      );

      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          packages = with pkgs; [
            jdk25
            (gradle_9.override { java = jdk25; })
            nodejs_22
            postgresql_17
            prefetch-npm-deps
            kanidm_1_11
            jq
          ];
          JAVA_HOME = "${pkgs.jdk25}/lib/openjdk";
        };
      });

      formatter = forAllSystems (pkgs: pkgs.nixfmt);
    };
}
