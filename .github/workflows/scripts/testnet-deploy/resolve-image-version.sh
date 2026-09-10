#!/usr/bin/env bash
#
# Resolve the GHCR image tag a testnet deploy should use, and prove it is pullable
# BEFORE anything touches the cluster.
#
#   usage: resolve-image-version.sh [<version>]
#
# The deploy pins ONE tag for BOTH images: docker/bin/remote-deploy.sh builds
# "$CL_DOCKER_CORE_IMAGE:$TESSELLATION_DOCKER_VERSION" (line 30) and
# "$CL_DOCKER_SS_IMAGE:$TESSELLATION_DOCKER_VERSION" (line 628) from the same
# variable. So a tag is only usable when it is published for tessellation AND for
# snapshot-streaming, and both are checked here -- the deploy's first act is a
# chain-preserving `just down`, so learning about a missing image afterwards leaves
# the cluster stopped with nothing to bring it back up.
#
#   no argument  -> the highest semver release version published for both packages.
#   <version>    -> that exact tag, verified present in both. Any tag works, not
#                   just a release version: `sha-<commit>` pins a specific build.
#
# Prints the resolved tag on stdout; diagnostics go to stderr. Both packages are
# public, so GHCR is read anonymously with no token -- run it locally to see what a
# default dispatch would deploy.
#
set -euo pipefail

REQUESTED="${1:-}"
ORG="${GHCR_ORG:-constellation-labs}"
CORE_PKG="${CORE_PKG:-tessellation}"
SS_PKG="${SS_PKG:-snapshot-streaming}"

# Newline-separated tag list for one GHCR package, via the registry v2 API. We do not
# trust the ORDER it comes back in (the OCI spec does not fix it) -- ordering is done
# by semver below.
tags_for() {
  local repo="$ORG/$1"
  local token
  token="$(
    curl -fsS --max-time 30 "https://ghcr.io/token?service=ghcr.io&scope=repository:$repo:pull" \
      | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])'
  )" || { echo "error: could not obtain a pull token for ghcr.io/$repo" >&2; return 1; }

  curl -fsS --max-time 30 -H "Authorization: Bearer $token" "https://ghcr.io/v2/$repo/tags/list" \
    | python3 -c 'import json,sys; print("\n".join(json.load(sys.stdin).get("tags") or []))' \
    || { echo "error: could not list tags for ghcr.io/$repo" >&2; return 1; }
}

CORE_TAGS="$(tags_for "$CORE_PKG")"
SS_TAGS="$(tags_for "$SS_PKG")"

REQUESTED="$REQUESTED" CORE_TAGS="$CORE_TAGS" SS_TAGS="$SS_TAGS" \
CORE_PKG="$CORE_PKG" SS_PKG="$SS_PKG" ORG="$ORG" python3 - <<'PY'
import os
import re
import sys

requested = os.environ["REQUESTED"].strip()
core_pkg, ss_pkg, org = os.environ["CORE_PKG"], os.environ["SS_PKG"], os.environ["ORG"]
packages = ((core_pkg, set(os.environ["CORE_TAGS"].split())), (ss_pkg, set(os.environ["SS_TAGS"].split())))

if requested:
    absent = [pkg for pkg, tags in packages if requested not in tags]
    if absent:
        sys.exit(
            f"error: tag '{requested}' is not published for: "
            + ", ".join(f"ghcr.io/{org}/{p}" for p in absent)
            + ".\n       The deploy pins one tag for both images, so the pull would fail after the "
            "cluster is already stopped.\n       Pick a version present in both, or leave the input "
            "blank to take the latest."
        )
    print(requested)
    raise SystemExit(0)

# X.Y.Z with an optional prerelease -- excludes `testnet`, `latest` and `sha-<commit>`,
# none of which name a release.
SEMVER = re.compile(r"^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$")


def precedence(tag):
    """Semver precedence key: 4.1.0-alpha.9 < 4.1.0-alpha.180 < 4.1.0 < 4.2.0-alpha.1."""
    major, minor, patch, prerelease = SEMVER.match(tag).groups()
    if prerelease is None:
        return (int(major), int(minor), int(patch), 1, ())
    # A numeric prerelease identifier compares numerically and ranks below an
    # alphanumeric one; a shorter identifier list ranks below a longer equal prefix.
    parts = tuple(
        (0, int(ident), "") if ident.isdigit() else (1, 0, ident) for ident in prerelease.split(".")
    )
    return (int(major), int(minor), int(patch), 0, parts)


shared = packages[0][1] & packages[1][1]
candidates = sorted((tag for tag in shared if SEMVER.match(tag)), key=precedence)
if not candidates:
    sys.exit(
        f"error: no release version is published for both ghcr.io/{org}/{core_pkg} and "
        f"ghcr.io/{org}/{ss_pkg}.\n       Run a testnet release first, or dispatch with an "
        "explicit version."
    )

latest = candidates[-1]
print(f"latest release version published for both images: {latest}", file=sys.stderr)
runners_up = candidates[-4:-1]
if runners_up:
    print(f"  (preceded by: {', '.join(reversed(runners_up))})", file=sys.stderr)
print(latest)
PY
