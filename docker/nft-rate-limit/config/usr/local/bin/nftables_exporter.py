#!/usr/bin/env python3
"""
nftables_exporter - lightweight Prometheus exporter for nftables counters,
meters, dynamic counter sets, and the firewall-mode active-mode marker.

Listens on :9437/metrics. Reads `nft -j list ruleset` once per scrape and
extracts counter, meter, and per-element set state.

Runs as root via systemd unit because `nft list` requires CAP_NET_ADMIN.
"""

import json
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn

LISTEN_HOST = "0.0.0.0"
LISTEN_PORT = 9437
ACTIVE_MODE_FILE = "/etc/firewall/active-mode"
KNOWN_MODES = ("steady", "emergency", "open", "unknown")


def nft_json_list(what):
    try:
        r = subprocess.run(
            ["nft", "-j", "list", what],
            capture_output=True, text=True, timeout=5
        )
        if r.returncode != 0:
            return None
        return json.loads(r.stdout)
    except (subprocess.TimeoutExpired, json.JSONDecodeError, FileNotFoundError):
        return None


def collect_counters():
    data = nft_json_list("counters")
    if not data:
        return []
    out = []
    for entry in data.get("nftables", []):
        c = entry.get("counter")
        if not c:
            continue
        out.append((
            c.get("table", ""),
            c.get("family", ""),
            c.get("name", ""),
            int(c.get("packets", 0)),
            int(c.get("bytes", 0)),
        ))
    return out


def collect_meters():
    data = nft_json_list("meters")
    if not data:
        return []
    out = []
    for entry in data.get("nftables", []):
        m = entry.get("meter")
        if not m:
            continue
        elem = m.get("elem", [])
        out.append((
            m.get("table", ""),
            m.get("family", ""),
            m.get("name", ""),
            len(elem),
        ))
    return out


def collect_set_counters():
    """Yield (table, family, set_name, key_value, packets, bytes) for every
    element in every dynamic counter set. Used for per-destination-IP
    breakdowns of egress (pub_out_dst / p2p_out_dst)."""
    data = nft_json_list("sets")
    if not data:
        return []
    sets_meta = []
    # First pass: collect set definitions to know which ones are counter sets.
    for entry in data.get("nftables", []):
        s = entry.get("set")
        if not s:
            continue
        sets_meta.append(s)

    out = []
    for s in sets_meta:
        # `nft list sets` returns set definitions WITHOUT elements. Need
        # `nft list set <family> <table> <name>` to get elements.
        family = s.get("family", "")
        table = s.get("table", "")
        name = s.get("name", "")
        if not (family and table and name):
            continue
        details = nft_json_list_set(family, table, name)
        if not details:
            continue
        for entry in details.get("nftables", []):
            sd = entry.get("set")
            if not sd or sd.get("name") != name:
                continue
            for e in sd.get("elem", []) or []:
                el = e.get("elem", e) if isinstance(e, dict) else None
                if not isinstance(el, dict):
                    continue
                val = el.get("val")
                ctr = el.get("counter")
                if val is None or not isinstance(ctr, dict):
                    continue
                out.append((
                    table, family, name, str(val),
                    int(ctr.get("packets", 0)),
                    int(ctr.get("bytes", 0)),
                ))
    return out


def nft_json_list_set(family, table, name):
    try:
        r = subprocess.run(
            ["nft", "-j", "list", "set", family, table, name],
            capture_output=True, text=True, timeout=5
        )
        if r.returncode != 0:
            return None
        return json.loads(r.stdout)
    except (subprocess.TimeoutExpired, json.JSONDecodeError, FileNotFoundError):
        return None


def read_active_mode():
    try:
        with open(ACTIVE_MODE_FILE, "r") as f:
            return f.read().strip() or "unknown"
    except FileNotFoundError:
        return "unknown"


def render_metrics():
    counters = collect_counters()
    meters = collect_meters()
    mode = read_active_mode()
    lines = []

    lines.append("# HELP nftables_counter_packets_total Packet count of nftables named counter")
    lines.append("# TYPE nftables_counter_packets_total counter")
    for t, f, n, p, _ in counters:
        lines.append(f'nftables_counter_packets_total{{table="{t}",family="{f}",name="{n}"}} {p}')

    lines.append("# HELP nftables_counter_bytes_total Byte count of nftables named counter")
    lines.append("# TYPE nftables_counter_bytes_total counter")
    for t, f, n, _, b in counters:
        lines.append(f'nftables_counter_bytes_total{{table="{t}",family="{f}",name="{n}"}} {b}')

    lines.append("# HELP nftables_meter_elements Number of elements currently tracked by an nftables meter")
    lines.append("# TYPE nftables_meter_elements gauge")
    for t, f, n, c in meters:
        lines.append(f'nftables_meter_elements{{table="{t}",family="{f}",name="{n}"}} {c}')

    set_counters = collect_set_counters()
    lines.append("# HELP nftables_set_element_packets_total Packets per dynamic counter-set element")
    lines.append("# TYPE nftables_set_element_packets_total counter")
    for t, f, n, k, p, _ in set_counters:
        lines.append(f'nftables_set_element_packets_total{{table="{t}",family="{f}",set="{n}",key="{k}"}} {p}')

    lines.append("# HELP nftables_set_element_bytes_total Bytes per dynamic counter-set element")
    lines.append("# TYPE nftables_set_element_bytes_total counter")
    for t, f, n, k, _, b in set_counters:
        lines.append(f'nftables_set_element_bytes_total{{table="{t}",family="{f}",set="{n}",key="{k}"}} {b}')

    lines.append("# HELP nftables_active_mode Active firewall-mode marker (1 indicates current mode)")
    lines.append("# TYPE nftables_active_mode gauge")
    for m in KNOWN_MODES:
        lines.append(f'nftables_active_mode{{mode="{m}"}} {1 if mode == m else 0}')

    lines.append("")
    return "\n".join(lines)


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/metrics":
            body = render_metrics().encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; version=0.0.4")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path == "/":
            body = b"<html><body><a href='/metrics'>metrics</a></body></html>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        # Quiet by default; uncomment to see scrape logs
        pass


class ThreadedServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True


if __name__ == "__main__":
    server = ThreadedServer((LISTEN_HOST, LISTEN_PORT), Handler)
    sys.stderr.write(f"nftables-exporter listening on {LISTEN_HOST}:{LISTEN_PORT}\n")
    sys.stderr.flush()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
