# Spring Boot backend (with the SPA bundled under classpath:/static).
#
# Gradle dependencies come from nix/backend-deps.json through nixpkgs' mitm-cache support.
# When a dependency changes, regenerate the lockfile with `nix/update-hashes.sh backend`.
{
  lib,
  stdenv,
  gradle_9,
  jdk25,
  jdk25_headless,
  makeWrapper,
  # Store path holding the built SPA (contents of `frontend/dist`), or null to build without it.
  frontend ? null,
  version,
}:

let
  # Run Gradle itself on JDK 25 so the build's `languageVersion = 25` toolchain resolves to the
  # running JVM instead of trying to download one.
  gradle = gradle_9.override { java = jdk25; };
in
stdenv.mkDerivation (finalAttrs: {
  pname = "ynay";
  inherit version;

  src = lib.fileset.toSource {
    root = ../backend;
    # Only what affects the jar; test sources are not compiled in the Nix build (tests need Docker).
    fileset = lib.fileset.unions [
      ../backend/build.gradle.kts
      ../backend/settings.gradle.kts
      ../backend/gradle.properties
      ../backend/src/main
    ];
  };

  nativeBuildInputs = [
    gradle
    makeWrapper
  ];

  mitmCache = gradle.fetchDeps {
    pkg = finalAttrs.finalPackage;
    data = ./backend-deps.json;
  };

  __darwinAllowLocalNetworking = true;

  gradleBuildTask = "bootJar";
  gradleFlags = [
    "-x"
    "test"
    "-Dfile.encoding=utf-8"
    "-Dorg.gradle.java.installations.auto-download=false"
    "-Dorg.gradle.java.installations.auto-detect=false"
    "-Dorg.gradle.java.installations.paths=${jdk25}/lib/openjdk"
  ];

  # Gradle's Copy preserves the read-only modes of store paths, which breaks processResources
  # for nested directories, so hand it a writable copy of the SPA.
  preBuild = lib.optionalString (frontend != null) ''
    frontendDist="$(mktemp -d)/frontend-dist"
    cp -r --no-preserve=mode ${frontend} "$frontendDist"
    gradleFlagsArray+=("-PfrontendDist=$frontendDist")
  '';

  # Tests run outside Nix (`./gradlew test`); they need Docker for Testcontainers.
  doCheck = false;

  installPhase = ''
    runHook preInstall

    install -Dm644 build/libs/ynay.jar $out/share/ynay/ynay.jar

    # JAVA_OPTS is appended last so operators can override any default.
    makeWrapper ${lib.getExe' jdk25_headless "java"} $out/bin/ynay \
      --add-flags "-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
      --add-flags "-Dfile.encoding=UTF-8 -Djava.awt.headless=true" \
      --add-flags "\''${JAVA_OPTS:-}" \
      --add-flags "-jar $out/share/ynay/ynay.jar"

    runHook postInstall
  '';

  passthru = {
    inherit frontend;
    jar = "${finalAttrs.finalPackage}/share/ynay/ynay.jar";
  };

  meta = {
    description = "Self-hosted, MCP-native zero-based budgeting app";
    homepage = "https://github.com/BigKakuta/YNAY-";
    license = lib.licenses.mit;
    mainProgram = "ynay";
    platforms = lib.platforms.linux ++ lib.platforms.darwin;
    sourceProvenance = with lib.sourceTypes; [
      fromSource
      binaryBytecode # Maven dependencies from the mitm cache
    ];
  };
})
