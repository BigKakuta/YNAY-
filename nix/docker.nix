# OCI image for docker / podman: `nix build .#dockerImage && docker load < result`.
{
  lib,
  dockerTools,
  buildPackages,
  runCommand,
  jre25_minimal,
  jdk25_headless,
  cacert,
  tzdata,
  version,
  backend,
}:

let
  # jlink'ed runtime with only what Spring Boot + the app need (~70 MB instead of ~200 MB for the
  # full headless JDK). The list is `jdeps --print-module-deps` over the fat jar plus modules that
  # are only loaded reflectively (JNDI, JMX, zipfs, extra charsets, Kerberos for pgjdbc).
  # If a new library fails with NoClassDefFoundError for a java.*/jdk.* class, add its module here.
  jre = jre25_minimal.override {
    jdk = jdk25_headless;
    jdkOnBuild = buildPackages.jdk25_headless;
    modules = [
      "java.base"
      "java.compiler"
      "java.desktop"
      "java.instrument"
      "java.logging"
      "java.management"
      "java.naming"
      "java.net.http"
      "java.prefs"
      "java.scripting"
      "java.security.jgss"
      "java.security.sasl"
      "java.sql"
      "java.sql.rowset"
      "java.xml"
      "jdk.charsets"
      "jdk.crypto.cryptoki"
      "jdk.jfr"
      "jdk.management"
      "jdk.naming.dns"
      "jdk.net"
      "jdk.security.auth"
      "jdk.unsupported"
      "jdk.zipfs"
    ];
  };

  # Copy of the jar without the backend's bin/ wrapper, so the full JDK stays out of the image.
  app = runCommand "ynay-jar-${version}" { } ''
    install -Dm444 ${backend}/share/ynay/ynay.jar $out/share/ynay/ynay.jar
  '';

  uid = "1000";

  etc = runCommand "ynay-image-etc" { } ''
    mkdir -p $out/etc
    cat > $out/etc/passwd <<EOF
    root:x:0:0:root:/root:/sbin/nologin
    ynay:x:${uid}:${uid}:YNAY:/home/ynay:/sbin/nologin
    nobody:x:65534:65534:nobody:/var/empty:/sbin/nologin
    EOF
    cat > $out/etc/group <<EOF
    root:x:0:
    ynay:x:${uid}:
    nobody:x:65534:
    EOF
    echo 'hosts: files dns' > $out/etc/nsswitch.conf
  '';
in
dockerTools.buildLayeredImage {
  name = "ynay";
  tag = version;

  contents = [
    etc
    cacert
    tzdata
    jre
  ];

  # /tmp (Tomcat work dir, JVM temp files) and $HOME must be writable for the non-root user.
  fakeRootCommands = ''
    mkdir -p tmp home/ynay
    chmod 1777 tmp
    chown ${uid}:${uid} home/ynay
  '';

  config = {
    User = "${uid}:${uid}";
    WorkingDir = "/home/ynay";
    Cmd = [
      "${jre}/bin/java"
      "-XX:MaxRAMPercentage=75"
      "-XX:+ExitOnOutOfMemoryError"
      "-Dfile.encoding=UTF-8"
      "-Djava.awt.headless=true"
      "-jar"
      "${app}/share/ynay/ynay.jar"
    ];
    Env = [
      "HOME=/home/ynay"
      "LANG=C.UTF-8"
      "TZ=UTC"
      "TZDIR=${tzdata}/share/zoneinfo"
      "SSL_CERT_FILE=${cacert}/etc/ssl/certs/ca-bundle.crt"
      "YNAY_PORT=8080"
    ];
    ExposedPorts."8080/tcp" = { };
    Labels = {
      "org.opencontainers.image.title" = "ynay";
      "org.opencontainers.image.description" = "Self-hosted, MCP-native zero-based budgeting app";
      "org.opencontainers.image.version" = version;
      "org.opencontainers.image.source" = "https://github.com/BigKakuta/YNAY-";
      "org.opencontainers.image.licenses" = "MIT";
    };
  };

  passthru = { inherit jre app; };
}
