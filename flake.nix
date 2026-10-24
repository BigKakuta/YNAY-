{
  description = "YNAY - a self-hosted, MCP-native zero-based budgeting app";

  inputs = {
    # Lockable tarball of the stable channel (equivalent to github:NixOS/nixpkgs/nixos-26.05).
    nixpkgs.url = "https://channels.nixos.org/nixos-26.05/nixexprs.tar.xz";
  };

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          packages = with pkgs; [ jdk25 gradle_9 nodejs_22 postgresql_17 ];
          JAVA_HOME = "${pkgs.jdk25}/lib/openjdk";
        };
      });
    };
}
