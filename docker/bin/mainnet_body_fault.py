"""Isolated devnet response-body fault. Forward native headers; emit no rumors."""
import argparse
import http.client
import http.server
import json
from pathlib import Path
import subprocess
import threading
import time

from mainnet_staged_fault import parse_states


def serve(ip, logfile, stopfile):
    if ip not in ("172.30.194.13", "172.30.194.14"):
        raise ValueError("proxy is restricted to the two private lab addresses")
    lock = threading.Lock()

    def record(kind, **data):
        with lock, open(logfile, "a") as output:
            output.write(json.dumps(dict(time=time.time(), kind=kind, **data)) + "\n")

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass

        def do_GET(self):
            self.forward()

        def do_POST(self):
            self.forward()

        def forward(self):
            upstream = http.client.HTTPConnection("127.0.0.1", 9001, timeout=10)
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 <= length <= 2 * 1024 * 1024 or self.headers.get("Transfer-Encoding"):
                    self.send_error(400)
                    return
                body = self.rfile.read(length)
                upstream.request(self.command, self.path, body=body, headers=dict(self.headers))
                response = upstream.getresponse()
                stall = self.path == "/rumors/peer/query" and response.status == 200
                self.send_response_only(response.status)
                for key, value in response.getheaders():
                    if key.lower() not in ("content-length", "transfer-encoding", "connection"):
                        self.send_header(key, value)
                self.send_header("Transfer-Encoding", "chunked")
                self.send_header("Connection", "close")
                self.end_headers()
                if stall:
                    # Keep native identity/token headers. No signed rumor is changed,
                    # fabricated, or emitted. Whitespace prevents an idle-read timeout
                    # from disguising the missing total-response deadline.
                    upstream.close()
                    record("body_stall_started", source=self.client_address[0])
                    until = time.monotonic() + 300
                    while time.monotonic() < until:
                        self.wfile.write(b"1\r\n \r\n")
                        self.wfile.flush()
                        time.sleep(.2)
                else:
                    while chunk := response.read(16384):
                        self.wfile.write(f"{len(chunk):x}\r\n".encode() + chunk + b"\r\n")
                    self.wfile.write(b"0\r\n\r\n")
                    self.wfile.flush()
            except (OSError, http.client.HTTPException) as error:
                record("connection_ended", error=type(error).__name__)
            finally:
                self.close_connection = True
                upstream.close()

    server = http.server.ThreadingHTTPServer((ip, 19001), Handler)
    server.daemon_threads = True
    record("proxy_ready", ip=ip)
    server.timeout = .2
    try:
        while not Path(stopfile).exists():
            server.handle_request()
    finally:
        server.server_close()


class BodyFaultController:
    def __init__(self, command, names, network, peers, event, previous_ordinal, output, mode):
        self.command, self.names, self.network, self.event = command, names, network, event
        self.previous_ordinal, self.fault_ordinal = previous_ordinal, previous_ordinal + 1
        self.output, self.mode = Path(output), mode
        self.stop, self.finished = threading.Event(), threading.Event()
        self.failure, self.rules, self.proxies = None, [], []

    def records(self, node, ordinal):
        return [r for r in parse_states(self.command("docker", "logs", "--timestamps", "--tail", "3000", self.names[node]))
                if r["ordinal"] == ordinal]

    def namespace(self, node):
        info = json.loads(self.command("docker", "inspect", self.names[node]))[0]
        networks = info["NetworkSettings"]["Networks"]
        expected = f"172.30.194.{10+node}"
        if set(networks) != {self.network} or networks[self.network]["IPAddress"] != expected:
            raise RuntimeError("not the exact owned lab namespace")
        net = json.loads(self.command("docker", "network", "inspect", self.network))[0]
        if not net["Internal"] or net["IPAM"]["Config"][0]["Subnet"] != "172.30.194.0/24":
            raise RuntimeError("isolation check failed")
        pid = info["State"]["Pid"]
        if not info["State"]["Running"] or pid <= 1:
            raise RuntimeError("lab container not running")
        return ("sudo", "-n", "nsenter", "--target", str(pid), "--net")

    def delay(self, seconds):
        if self.stop.wait(seconds):
            raise RuntimeError("cancelled")

    def install(self):
        for node in (3, 4):
            log = self.output / f"body-proxy-{node}.jsonl"
            stopfile = self.output / f"body-proxy-{node}.stop"
            process = subprocess.Popen([*self.namespace(node), "python3", str(Path(__file__).resolve()),
                                        "--serve", f"172.30.194.{10+node}", "--log", str(log), "--stop-file", str(stopfile)],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            self.proxies.append((process, stopfile))
            for _ in range(30):
                if log.exists() and "proxy_ready" in log.read_text():
                    break
                if process.poll() is not None:
                    raise RuntimeError("proxy exited before readiness")
                self.delay(.1)
            else:
                raise RuntimeError("proxy not ready")
        for source in (0, 1, 2):
            base = self.namespace(source)
            for target in (3, 4):
                ip = f"172.30.194.{10+target}"
                rule = ("OUTPUT", "-p", "tcp", "-d", ip, "--dport", "9001",
                        "-m", "comment", "--comment", self.network, "-j", "DNAT", "--to-destination", ip + ":19001")
                self.command(*base, "/usr/sbin/iptables", "-w", "3", "-t", "nat", "-I", *rule)
                self.rules.append((source, rule))
                # Terminate only these existing private P2P connections so newly
                # opened connections take the isolated proxy path.
                self.command(*base, "/usr/bin/ss", "-K", "dst", ip, "dport", "=", ":9001")
        self.event("body_fault_installed", rules=len(self.rules), mode=self.mode,
                   scope="three healthy observers; two faulty responders; private bridge only")

    def restore(self):
        for source, rule in list(reversed(self.rules)):
            self.command(*self.namespace(source), "/usr/sbin/iptables", "-w", "3", "-t", "nat", "-D", *rule)
            self.rules.remove((source, rule))
        for process, stopfile in self.proxies:
            stopfile.touch()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.command("sudo", "-n", "kill", "-TERM", str(process.pid))
                process.wait(timeout=5)
        self.proxies.clear()
        self.event("body_fault_restored", ordinal=self.fault_ordinal)

    def run(self):
        try:
            until = time.monotonic() + 300
            while time.monotonic() < until:
                records = self.records(0, self.previous_ordinal)
                if any(r["phase"] == "Finished" and r["count"] == 5 and not r["removed"] for r in records):
                    break
                self.delay(.5)
            else:
                raise RuntimeError("healthy control rounds not completed")
            self.install()
            self.delay(210)
            states = [self.records(i, self.fault_ordinal) for i in (0, 1, 2)]
            finished = [any(r["phase"] == "Finished" for r in records) for records in states]
            stalls = []
            for node in (3, 4):
                rows = [json.loads(line) for line in (self.output / f"body-proxy-{node}.jsonl").read_text().splitlines()]
                stalls.extend(r for r in rows if r["kind"] == "body_stall_started")
            if set(r["source"] for r in stalls) != {f"172.30.194.{10+i}" for i in (0, 1, 2)}:
                raise RuntimeError("fault did not reach all healthy observers")
            self.event("body_fault_observed", mode=self.mode, finished=finished, records=states, stalled_queries=len(stalls))
            if self.mode == "stock" and any(finished):
                raise RuntimeError("stock did not reproduce intended whole-group stall")
            if self.mode == "fixed" and not all(finished):
                raise RuntimeError("candidate did not recover snapshot progress during fault")
        except Exception as error:
            self.failure = str(error)
            self.event("body_fault_failed", error=self.failure)
        finally:
            try:
                self.restore()
            except Exception as error:
                self.failure = f"{self.failure or ''}; restore: {error}"
            self.finished.set()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--serve", required=True)
    parser.add_argument("--log", required=True)
    parser.add_argument("--stop-file", required=True)
    args = parser.parse_args()
    serve(args.serve, args.log, args.stop_file)
