#!/usr/bin/env bash
#
# Decide WHICH version a testnet deploy should use. Whether that version is actually
# deployable is a separate question, answered by verify-images.sh -- keeping the two
# apart means there is exactly one gate, with one error message, for both the explicit
# and the default path.
#
#   usage: resolve-image-version.sh [<version>]
#
#   <version>    -> echoed back unchanged. Any tag is allowed, not just a release
#                   version: `sha-<commit>` pins one specific build.
#   no argument  -> the highest release version published to GHCR for BOTH the
#                   tessellation and snapshot-streaming images. Both, because the deploy
#                   pins one tag for both (docker/bin/remote-deploy.sh lines 30 and 628),
#                   so a version that only published half a release is not a candidate.
#
# Ordering is by semver precedence, NOT the order the registry lists tags in -- the OCI
# spec does not fix that order, so relying on it would silently pick the wrong version
# the day GHCR changes it.
#
# Prints the resolved tag on stdout, diagnostics on stderr. GHCR is read anonymously
# (both packages are public): no token needed, so this runs locally to preview what a
# default dispatch would deploy.
#
set -euo pipefail

REQUESTED="${1:-}"
ORG="${GHCR_ORG:-constellation-labs}"
CORE_PKG="${CORE_PKG:-tessellation}"
SS_PKG="${SS_PKG:-snapshot-streaming}"

# An explicit version needs no lookup at all; verify-images.sh is what proves it exists.
if [ -n "$REQUESTED" ]; then
  echo "$REQUESTED"
  exit 0
fi

# Newline-separated tag list for one GHCR package, via the registry v2 API.
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

CORE_TAGS="$CORE_TAGS" SS_TAGS="$SS_TAGS" CORE_PKG="$CORE_PKG" SS_PKG="$SS_PKG" ORG="$ORG" \
python3 - <<'PY'
import os
import re
import sys

core = set(os.environ["CORE_TAGS"].split())
ss = set(os.environ["SS_TAGS"].split())
core_pkg, ss_pkg, org = os.environ["CORE_PKG"], os.environ["SS_PKG"], os.environ["ORG"]

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


candidates = sorted((tag for tag in core & ss if SEMVER.match(tag)), key=precedence)
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
