# NixOS module: services.ynay
#
# Secrets are never put in the Nix store or the unit's environment: each *File option is passed
# to the service with systemd's LoadCredential and exported to the JVM by a small start script.
#
# Local database (database.createLocally = true, the default): PostgreSQL on this host with a
# `ynay` role and database. The JDBC driver cannot use unix sockets without an extra native
# library, so the app connects over TCP to localhost with password (SCRAM) authentication.
# `ynay-db-setup.service` sets that role's password, either from database.passwordFile or from a
# random password generated once into /var/lib/ynay-db/password (mode 0600, owner postgres).
{
  config,
  lib,
  pkgs,
  ...
}:

let
  inherit (lib)
    mkEnableOption
    mkIf
    mkOption
    optional
    optionalAttrs
    types
    ;

  cfg = config.services.ynay;
  pgCfg = config.services.postgresql;

  generatedPasswordFile = "/var/lib/ynay-db/password";
  dbPasswordSource =
    if cfg.database.passwordFile != null then
      cfg.database.passwordFile
    else if cfg.database.createLocally then
      generatedPasswordFile
    else
      null;

  # credential name -> (env var, source file)
  credentials = lib.filterAttrs (_: c: c.file != null) {
    db-password = {
      env = "YNAY_DB_PASSWORD";
      file = dbPasswordSource;
    };
    kanidm-client-secret = {
      env = "YNAY_KANIDM_CLIENT_SECRET";
      file = cfg.kanidm.clientSecretFile;
    };
    agent-api-key = {
      env = "YNAY_AGENT_API_KEY";
      file = cfg.agent.apiKeyFile;
    };
  };

  start = pkgs.writeShellScript "ynay-start" ''
    set -eu
    ${lib.concatStrings (
      lib.mapAttrsToList (name: c: ''
        ${c.env}="$(<"$CREDENTIALS_DIRECTORY/${name}")"
        export ${c.env}
      '') credentials
    )}
    exec ${lib.getExe cfg.package} "$@"
  '';

  environment = lib.filterAttrs (_: v: v != null) (
    {
      YNAY_PORT = toString cfg.port;
      YNAY_PUBLIC_URL = cfg.publicUrl;
      YNAY_DB_URL = cfg.database.url;
      YNAY_DB_USER = cfg.database.user;
      YNAY_KANIDM_URL = cfg.kanidm.url;
      YNAY_KANIDM_CLIENT_ID = cfg.kanidm.clientId;
      YNAY_MCP_INTROSPECTION = lib.boolToString cfg.kanidm.mcpIntrospection;
      YNAY_AGENT_PROVIDER = cfg.agent.provider;
      YNAY_AGENT_MODEL = cfg.agent.model;
      YNAY_AGENT_BASE_URL = cfg.agent.baseUrl;
      SERVER_ADDRESS = cfg.listenAddress;
      JAVA_OPTS = if cfg.javaOptions == [ ] then null else lib.concatStringsSep " " cfg.javaOptions;
      HOME = "/var/lib/ynay";
    }
    // cfg.extraEnvironment
  );
in
{
  options.services.ynay = {
    enable = mkEnableOption "YNAY, a self-hosted zero-based budgeting app";

    package = mkOption {
      type = types.package;
      description = "The YNAY package (the flake's `packages.<system>.default`).";
    };

    port = mkOption {
      type = types.port;
      default = 8080;
      description = "HTTP port.";
    };

    listenAddress = mkOption {
      type = types.nullOr types.str;
      default = null;
      example = "127.0.0.1";
      description = ''
        Address to bind. `null` listens on all interfaces. Use `127.0.0.1` when a reverse proxy on
        this host terminates TLS.
      '';
    };

    publicUrl = mkOption {
      type = types.str;
      default = "http://localhost:${toString cfg.port}";
      defaultText = lib.literalExpression ''"http://localhost:''${toString config.services.ynay.port}"'';
      example = "https://ynay.example.com";
      description = "External base URL as seen by browsers (OIDC redirect URI, MCP metadata).";
    };

    database = {
      createLocally = mkOption {
        type = types.bool;
        default = true;
        description = ''
          Run PostgreSQL on this host (`services.postgresql`) with a `ynay` role and database,
          reached over TCP on localhost with an automatically managed password.
        '';
      };

      url = mkOption {
        type = types.str;
        default = "jdbc:postgresql://localhost:${toString pgCfg.settings.port}/ynay";
        defaultText = lib.literalExpression ''"jdbc:postgresql://localhost:''${toString config.services.postgresql.settings.port}/ynay"'';
        description = "JDBC URL of the database.";
      };

      user = mkOption {
        type = types.str;
        default = "ynay";
        description = "Database role. With `createLocally` it must be `ynay` (owner of the `ynay` database).";
      };

      passwordFile = mkOption {
        type = types.nullOr types.path;
        default = null;
        example = "/run/secrets/ynay-db-password";
        description = ''
          File containing the database password. Required for a remote database. With
          `createLocally` it is optional: when unset a random password is generated.
        '';
      };
    };

    kanidm = {
      url = mkOption {
        type = types.nullOr types.str;
        default = null;
        example = "https://idm.example.com";
        description = "Kanidm origin used for OIDC login.";
      };

      clientId = mkOption {
        type = types.str;
        default = "ynay";
        description = "OAuth2 client name registered in Kanidm.";
      };

      clientSecretFile = mkOption {
        type = types.nullOr types.path;
        default = null;
        example = "/run/secrets/ynay-kanidm-secret";
        description = "File containing the OAuth2 client secret (`kanidm system oauth2 show-basic-secret ynay`).";
      };

      mcpIntrospection = mkOption {
        type = types.bool;
        default = false;
        description = "Also accept Kanidm access tokens on `/mcp` via RFC 7662 token introspection.";
      };
    };

    agent = {
      provider = mkOption {
        type = types.enum [
          "anthropic"
          "openai"
        ];
        default = "anthropic";
        description = "LLM provider for the import agent. `openai` covers any OpenAI-compatible server (Ollama, llama.cpp, vLLM).";
      };

      model = mkOption {
        type = types.nullOr types.str;
        default = null;
        description = "Model id; `null` uses the provider default.";
      };

      baseUrl = mkOption {
        type = types.nullOr types.str;
        default = null;
        example = "http://127.0.0.1:11434/v1";
        description = "Override the provider's API base URL.";
      };

      apiKeyFile = mkOption {
        type = types.nullOr types.path;
        default = null;
        description = "File containing the provider API key. Without it the agent is disabled (except `openai` with a local base URL).";
      };
    };

    environmentFile = mkOption {
      type = types.nullOr types.path;
      default = null;
      example = "/run/secrets/ynay.env";
      description = ''
        Extra `KEY=value` environment file (systemd `EnvironmentFile`), read at service start.
        Useful for secrets managed by sops-nix/agenix as a single file.
      '';
    };

    extraEnvironment = mkOption {
      type = types.attrsOf types.str;
      default = { };
      example = {
        LOGGING_LEVEL_IO_YNAY = "DEBUG";
      };
      description = "Additional (non-secret) environment variables, e.g. Spring Boot properties.";
    };

    javaOptions = mkOption {
      type = types.listOf types.str;
      default = [ ];
      example = [ "-Xmx512m" ];
      description = "Extra JVM flags (appended after the package defaults).";
    };

    openFirewall = mkOption {
      type = types.bool;
      default = false;
      description = "Open `port` in the firewall.";
    };
  };

  config = mkIf cfg.enable {
    assertions = [
      {
        assertion = cfg.database.createLocally -> cfg.database.user == "ynay";
        message = "services.ynay.database.user must be \"ynay\" when database.createLocally is enabled.";
      }
      {
        assertion = !cfg.database.createLocally -> cfg.database.passwordFile != null;
        message = "services.ynay.database.passwordFile is required when database.createLocally is disabled.";
      }
    ];

    services.postgresql = mkIf cfg.database.createLocally {
      enable = true;
      ensureDatabases = [ "ynay" ];
      ensureUsers = [
        {
          name = "ynay";
          ensureDBOwnership = true;
        }
      ];
      # Loopback TCP with SCRAM for the ynay role only; everything else keeps the module defaults.
      authentication = lib.mkBefore ''
        host ynay ynay 127.0.0.1/32 scram-sha-256
        host ynay ynay ::1/128      scram-sha-256
      '';
    };

    systemd.services.ynay-db-setup = mkIf cfg.database.createLocally {
      description = "Set the YNAY PostgreSQL role password";
      requires = [ "postgresql-setup.service" ];
      after = [ "postgresql-setup.service" ];
      path = [ pgCfg.finalPackage ];
      environment.PGPORT = toString pgCfg.settings.port;
      serviceConfig = {
        Type = "oneshot";
        RemainAfterExit = true;
        User = "postgres";
        Group = "postgres";
        StateDirectory = "ynay-db";
        StateDirectoryMode = "0700";
        UMask = "0077";
        LoadCredential = optional (
          cfg.database.passwordFile != null
        ) "db-password:${cfg.database.passwordFile}";
      };
      script = ''
        set -eu
        ${
          if cfg.database.passwordFile != null then
            ''pw_file="$CREDENTIALS_DIRECTORY/db-password"''
          else
            ''
              pw_file=${generatedPasswordFile}
              if [ ! -s "$pw_file" ]; then
                tr -dc 'A-Za-z0-9' </dev/urandom | head -c 40 >"$pw_file"
              fi
            ''
        }
        # psql reads the password itself (\set with a backtick command) and quotes it with :'pw',
        # so it never appears on a command line or needs shell/SQL escaping.
        psql -d postgres -v ON_ERROR_STOP=1 -q <<EOF
        \set pw \`cat "$pw_file"\`
        ALTER ROLE ynay WITH LOGIN PASSWORD :'pw';
        EOF
      '';
    };

    systemd.services.ynay = {
      description = "YNAY budgeting app";
      wantedBy = [ "multi-user.target" ];
      wants = [ "network-online.target" ];
      after = [
        "network-online.target"
      ]
      ++ optional cfg.database.createLocally "ynay-db-setup.service";
      requires = optional cfg.database.createLocally "ynay-db-setup.service";

      inherit environment;

      serviceConfig = {
        ExecStart = start;
        DynamicUser = true;
        User = "ynay";
        Group = "ynay";
        StateDirectory = "ynay";
        WorkingDirectory = "/var/lib/ynay";
        LoadCredential = lib.mapAttrsToList (name: c: "${name}:${c.file}") credentials;
        EnvironmentFile = optional (cfg.environmentFile != null) cfg.environmentFile;
        Restart = "on-failure";
        RestartSec = 5;
        # The JVM exits with 143 on SIGTERM after a graceful shutdown.
        SuccessExitStatus = 143;
        TimeoutStopSec = 60;

        # Hardening
        AmbientCapabilities = lib.optional (cfg.port < 1024) "CAP_NET_BIND_SERVICE";
        CapabilityBoundingSet = lib.optional (cfg.port < 1024) "CAP_NET_BIND_SERVICE";
        DevicePolicy = "closed";
        LockPersonality = true;
        NoNewPrivileges = true;
        PrivateDevices = true;
        PrivateTmp = true;
        PrivateUsers = cfg.port >= 1024;
        ProtectClock = true;
        ProtectControlGroups = true;
        ProtectHome = true;
        ProtectHostname = true;
        ProtectKernelLogs = true;
        ProtectKernelModules = true;
        ProtectKernelTunables = true;
        ProtectProc = "invisible";
        ProtectSystem = "strict";
        RemoveIPC = true;
        RestrictAddressFamilies = [
          "AF_INET"
          "AF_INET6"
          "AF_UNIX"
        ];
        RestrictNamespaces = true;
        RestrictRealtime = true;
        RestrictSUIDSGID = true;
        SystemCallArchitectures = "native";
        SystemCallFilter = [
          "@system-service"
          "~@cpu-emulation @debug @keyring @mount @obsolete @privileged @clock @setuid @chown"
        ];
        UMask = "0077";
        # MemoryDenyWriteExecute is deliberately not set: the JIT needs writable+executable pages.
      };
    };

    networking.firewall.allowedTCPPorts = optional cfg.openFirewall cfg.port;
  };
}
