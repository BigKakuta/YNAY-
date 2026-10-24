# NixOS VM test: services.ynay with a local PostgreSQL.
#   nix build .#checks.x86_64-linux.nixos      (needs KVM for reasonable speed)
#   nix run .#checks.x86_64-linux.nixos.driverInteractive   (interactive debugging)
{ self }:
{
  name = "ynay";

  nodes.machine =
    { pkgs, ... }:
    {
      imports = [ self.nixosModules.default ];

      virtualisation.memorySize = 2048;
      virtualisation.cores = 2;
      environment.systemPackages = [ pkgs.curl ];

      services.ynay = {
        enable = true;
        publicUrl = "http://machine:8080";
        openFirewall = true;
        kanidm = {
          url = "https://idm.example.invalid";
          clientSecretFile = pkgs.writeText "kanidm-secret" "test-secret";
        };
        javaOptions = [ "-Xmx512m" ];
      };
    };

  testScript = ''
    import json

    machine.wait_for_unit("postgresql.service")
    machine.wait_for_unit("ynay-db-setup.service")
    machine.wait_for_unit("ynay.service")
    machine.wait_for_open_port(8080, timeout=600)

    with subtest("health endpoint is UP"):
        health = json.loads(machine.succeed("curl -sf http://localhost:8080/actuator/health"))
        assert health["status"] == "UP", health

    with subtest("API requires authentication"):
        code = machine.succeed(
            "curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/api/me"
        ).strip()
        assert code == "401", f"/api/me returned {code}"

    with subtest("secrets are passed as credentials, not in the unit environment"):
        machine.fail("systemctl show ynay.service -p Environment | grep -q test-secret")
        machine.succeed("test -s /var/lib/ynay-db/password")

    with subtest("service is sandboxed"):
        user = machine.succeed("ps -o user= -C java").strip()
        assert user != "root", f"java runs as {user}"

    with subtest("generated DB password survives a restart"):
        machine.systemctl("restart ynay-db-setup.service ynay.service")
        machine.wait_for_unit("ynay.service")
        machine.wait_until_succeeds("curl -sf http://localhost:8080/actuator/health", timeout=300)
  '';
}
