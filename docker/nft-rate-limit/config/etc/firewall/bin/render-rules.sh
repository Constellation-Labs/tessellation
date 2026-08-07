#!/usr/bin/env bash
# /etc/firewall/bin/render-rules.sh <mode>
#
# Reads /etc/firewall/modes/<mode>.nft and substitutes:
#   @ALLOW@               -> CSV from /etc/firewall/allowlist.conf
#   @PUBLIC_PORTS@        -> from /etc/firewall/ports.env
#   @P2P_PORTS@           -> from /etc/firewall/ports.env
#   @PUBLIC_BYTE_RATE@    -> from /etc/firewall/ports.env
# Writes the rendered nftables ruleset to stdout.

set -euo pipefail

FW_DIR="${FW_DIR:-/etc/firewall}"

mode="${1:-}"
[ -n "$mode" ] || { echo "usage: $0 <mode>" >&2; exit 2; }

template="$FW_DIR/modes/${mode}.nft"
[ -f "$template" ] || { echo "missing template: $template" >&2; exit 1; }

# shellcheck disable=SC1091
. "$FW_DIR/ports.env"
[ -n "${PUBLIC_PORTS:-}"     ] || { echo "ports.env missing PUBLIC_PORTS"     >&2; exit 1; }
[ -n "${P2P_PORTS:-}"        ] || { echo "ports.env missing P2P_PORTS"        >&2; exit 1; }
[ -n "${PUBLIC_BYTE_RATE:-}" ] || { echo "ports.env missing PUBLIC_BYTE_RATE" >&2; exit 1; }

# Build CSV of CIDRs from allowlist.conf, stripping comments + blank lines.
ALLOW="$(
  grep -hvE '^[[:space:]]*(#|$)' "$FW_DIR/allowlist.conf" \
    | awk '{print $1}' \
    | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+(/[0-9]+)?$' \
    | sort -u \
    | paste -sd, -
)"

# Refuse to render with an empty allowlist - would render an empty set
# which nft accepts but is never the intent. Better to fail loudly.
[ -n "$ALLOW" ] || { echo "allowlist.conf produced no entries" >&2; exit 1; }

sed \
  -e "s|@ALLOW@|${ALLOW}|g" \
  -e "s|@PUBLIC_PORTS@|${PUBLIC_PORTS}|g" \
  -e "s|@P2P_PORTS@|${P2P_PORTS}|g" \
  -e "s|@PUBLIC_BYTE_RATE@|${PUBLIC_BYTE_RATE}|g" \
  "$template"
