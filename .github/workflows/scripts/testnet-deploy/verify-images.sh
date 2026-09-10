#!/usr/bin/env bash
#
# Verify that BOTH images a testnet deploy needs are actually published and runnable
# at the given tag, before the deploy stops anything.
#
#   usage: verify-images.sh <tag>
#
# Why both, and why before: docker/bin/remote-deploy.sh derives
# "$CL_DOCKER_CORE_IMAGE:$TESSELLATION_DOCKER_VERSION" (line 30) and
# "$CL_DOCKER_SS_IMAGE:$TESSELLATION_DOCKER_VERSION" (line 628) from ONE variable, and
# the two images are published by two independent CI jobs (build-push-image and
# build-push-ss). So a release whose node image published and whose snapshot-streaming
# image failed leaves a tag that looks fine and is only half deployable. The core image
# is pulled partway through the deploy (the PRESET_KEYS peer-id derivation) and the SS
# image later still, implicitly by `compose up` -- both AFTER the chain-preserving
# `just down`. Discovering a missing image there leaves the cluster stopped.
#
# Checks per image, against the registry rather than a tag listing (a tag can be listed
# and still not resolve):
#   1. the tag resolves to a manifest -- HTTP 200, not 404/401
#   2. that manifest actually carries a linux/amd64 image. `docker buildx` attaches
#      provenance as an extra `unknown/unknown` child in the index, which is metadata
#      and not something you can run, so presence of children is not enough.
#   3. the resolved digest is printed, so the run records exactly what it deployed
#      rather than just a tag that may later move.
#
# Both images are checked even if the first fails, so one run tells you the whole story.
# GHCR is read anonymously (both packages are public): no token, runnable locally.
#
set -euo pipefail

TAG="${1:?usage: verify-images.sh <tag>}"

TAG="$TAG" \
GHCR_ORG="${GHCR_ORG:-constellation-labs}" \
IMAGES="${IMAGES:-tessellation snapshot-streaming}" \
REQUIRED_PLATFORM="${REQUIRED_PLATFORM:-linux/amd64}" \
python3 - <<'PY'
import json
import os
import sys
import urllib.error
import urllib.request

TAG = os.environ["TAG"]
ORG = os.environ["GHCR_ORG"]
IMAGES = os.environ["IMAGES"].split()
PLATFORM = os.environ["REQUIRED_PLATFORM"]

# Ask for every manifest type buildx might have pushed. Omit these and the registry can
# answer with something unhelpful (or 404) for a perfectly good multi-arch tag.
ACCEPT = ", ".join(
    (
        "application/vnd.oci.image.index.v1+json",
        "application/vnd.docker.distribution.manifest.list.v2+json",
        "application/vnd.oci.image.manifest.v1+json",
        "application/vnd.docker.distribution.manifest.v2+json",
    )
)


def fetch(url, token=None, accept=None):
    request = urllib.request.Request(url)
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    if accept:
        request.add_header("Accept", accept)
    with urllib.request.urlopen(request, timeout=30) as response:
        # Return the header object, not dict(...): HTTP/2 sends header names lowercased,
        # and only the email.Message wrapper looks them up case-insensitively.
        return response.headers, response.read()


class Unavailable(Exception):
    """The image cannot be deployed, with an operator-facing reason."""


def verify(image):
    """Return (digest, description) for ORG/image:TAG, or raise Unavailable."""
    repo = f"{ORG}/{image}"
    try:
        _, body = fetch(f"https://ghcr.io/token?service=ghcr.io&scope=repository:{repo}:pull")
        token = json.loads(body)["token"]
    except (urllib.error.URLError, KeyError, ValueError) as error:
        raise Unavailable(f"could not obtain a pull token from ghcr.io ({error})")

    try:
        headers, body = fetch(f"https://ghcr.io/v2/{repo}/manifests/{TAG}", token, ACCEPT)
    except urllib.error.HTTPError as error:
        if error.code in (401, 403, 404):
            raise Unavailable(f"tag is NOT PUBLISHED (registry answered HTTP {error.code})")
        raise Unavailable(f"manifest request failed with HTTP {error.code}")
    except urllib.error.URLError as error:
        raise Unavailable(f"could not reach ghcr.io ({error})")

    digest = headers.get("Docker-Content-Digest", "(no digest header)")
    manifest = json.loads(body)
    children = manifest.get("manifests")

    if children is None:
        # A single-platform manifest keeps its platform in the config blob.
        config = (manifest.get("config") or {}).get("digest")
        if not config:
            raise Unavailable("manifest has neither child manifests nor a config blob")
        try:
            _, body = fetch(f"https://ghcr.io/v2/{repo}/blobs/{config}", token)
            config_json = json.loads(body)
        except (urllib.error.URLError, ValueError) as error:
            raise Unavailable(f"could not read the image config to check its platform ({error})")
        os_name, architecture = config_json.get("os"), config_json.get("architecture")
        if not os_name or not architecture:
            # e.g. a buildx attestation manifest, which declares no runnable platform.
            raise Unavailable(f"manifest declares no platform, so it is not a {PLATFORM} image")
        platform = f"{os_name}/{architecture}"
        if platform != PLATFORM:
            raise Unavailable(f"image is {platform}, but the cluster needs {PLATFORM}")
        return digest, f"single manifest, {platform}"

    # An index: buildx adds provenance/SBOM as an `unknown/unknown` child. That is
    # metadata, not a runnable image, so it must not satisfy the platform requirement.
    platforms = []
    for child in children:
        spec = child.get("platform") or {}
        name = f"{spec.get('os')}/{spec.get('architecture')}"
        if name != "unknown/unknown":
            platforms.append(name)
    if PLATFORM not in platforms:
        raise Unavailable(
            f"index carries no {PLATFORM} image (runnable platforms: {', '.join(platforms) or 'none'})"
        )
    return digest, f"index, {PLATFORM}"


failures = []
lines = []
for image in IMAGES:
    reference = f"ghcr.io/{ORG}/{image}:{TAG}"
    try:
        digest, description = verify(image)
    except Unavailable as error:
        failures.append(f"  {reference}\n      {error}")
        print(f"MISSING  {reference} — {error}", file=sys.stderr)
        continue
    lines.append(f"- `{reference}` — {description}, `{digest}`")
    print(f"ok       {reference} — {description}, {digest}", file=sys.stderr)

if failures:
    print(
        "\nerror: the deploy needs both images at the same tag, and these are not usable:\n"
        + "\n".join(failures)
        + "\n\nNothing has been stopped. Either wait for the release that publishes both, or\n"
        "dispatch with a version that is fully published (blank picks the latest such version).",
        file=sys.stderr,
    )
    raise SystemExit(1)

print("\n".join(lines))
PY
