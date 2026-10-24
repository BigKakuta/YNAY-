# React SPA, built with Vite. The output is the contents of `frontend/dist`.
#
# npm dependencies are pinned by a single fixed-output hash kept in nix/frontend-npm-deps.hash.
# After package-lock.json changes, refresh it with `nix/update-hashes.sh frontend`.
{
  lib,
  buildNpmPackage,
  nodejs_22,
  runCommand,
  # Path to the frontend source tree (null when the repository has no frontend yet).
  src,
  version,
  # Overridable for testing; normally read from the file maintained by nix/update-hashes.sh.
  npmDepsHash ? lib.fileContents ./frontend-npm-deps.hash,
}:

if src == null then
  # Placeholder so the backend/image/module can be built and tested before the SPA exists.
  runCommand "ynay-frontend-placeholder-${version}" { } ''
    mkdir -p $out
    cat > $out/index.html <<'EOF'
    <!doctype html>
    <html lang="en"><head><meta charset="utf-8"><title>YNAY</title></head>
    <body><p>YNAY backend is running, but this build contains no frontend.</p></body></html>
    EOF
  ''
else
  buildNpmPackage {
    pname = "ynay-frontend";
    inherit version src;

    nodejs = nodejs_22;
    inherit npmDepsHash;

    # `npm run build` (tsc + vite build) writes ./dist; that directory is the package.
    npmBuildScript = "build";

    installPhase = ''
      runHook preInstall
      cp -r dist $out
      runHook postInstall
    '';

    meta = {
      description = "YNAY web UI (static SPA)";
      license = lib.licenses.mit;
    };
  }
