"""Lab-only fault injection. Never changes protocol messages, membership or quorum."""
import datetime
import json
import re
import threading
import time

PHASES = ("CollectingFacilities", "CollectingProposals", "CollectingSignatures", "Finished")


def parse_states(log):
    records, pending = [], None
    for line in re.sub(r"\x1b\[[0-9;]*m", "", log).splitlines():
        prefix = re.match(r"^(\d{4}-\d\d-\d\dT\S+) (.*)$", line)
        if not prefix:
            continue
        stamp, message = prefix.groups()
        if re.search(r"State (created|updated) ConsensusState\{", message):
            pending = datetime.datetime.fromisoformat(stamp.replace("Z", "+00:00")).timestamp()
        if pending is None:
            continue
        state = re.search(r"key=SnapshotOrdinal\{value=(\d+)\}.*?lockStatus=(\w+).*?facilitatorCount=(\d+).*?removedFacilitators=Set\(([^)]*)\).*?withdrawnFacilitators=Set\(([^)]*)\).*?status=(\w+)", message)
        if state:
            ordinal, lock, count, removed, withdrawn, phase = state.groups()
            records.append(dict(time=pending, ordinal=int(ordinal), lock=lock, count=int(count),
                                removed=sorted(re.findall(r"\b[0-9a-f]{8}\b", removed)),
                                withdrawn=sorted(re.findall(r"\b[0-9a-f]{8}\b", withdrawn)), phase=phase))
            pending = None
    return records


def validate_round(records, peer_ids):
    """Fail closed unless all three recovery stages and expected cumulative removals exist."""
    phases = []
    for phase in PHASES:
        matches = [r for r in records if r["phase"] == phase and r["lock"] == "Open"]
        if not matches:
            raise ValueError("missing open phase " + phase)
        phases.append(matches[0])
    for i, record in enumerate(phases):
        expected = sorted(peer_ids[j][:8] for j in (4, 3, 2)[:i])
        if record["count"] != 5-i or record["removed"] != expected or record["withdrawn"]:
            raise ValueError("unexpected membership or withdrawal at " + record["phase"])
    durations = []
    for begin, end in zip(phases, phases[1:]):
        closes = [r for r in records if r["phase"] == begin["phase"] and r["lock"] == "Closed"]
        if not closes or not 49 <= closes[0]["time"] - begin["time"] <= 55:
            raise ValueError("missing expected 50-second phase lock")
        duration = end["time"] - begin["time"]
        if duration < 59:
            raise ValueError("phase advanced before intended recovery interval")
        durations.append(duration)
    return dict(phase_seconds=durations, total_seconds=phases[-1]["time"]-phases[0]["time"], phases=phases)


class StagedFaultController:
    def __init__(self, command, names, network, peers, event, previous_ordinal):
        self.command, self.names, self.network, self.event = command, names, network, event
        self.ids = [p["id"] for p in peers]
        if len(set(i[:8] for i in self.ids)) != 5:
            raise ValueError("lab peer prefixes must be unique for log validation")
        self.previous_ordinal, self.fault_ordinal = previous_ordinal, previous_ordinal + 1
        self.stop, self.finished = threading.Event(), threading.Event()
        self.failure = None
        self.paused, self.rules = set(), []
        self.pid = None

    def records(self, node, ordinal=None):
        records = parse_states(self.command("docker", "logs", "--timestamps", "--tail", "2000", self.names[node]))
        return [r for r in records if r["ordinal"] == (self.fault_ordinal if ordinal is None else ordinal)]

    def delay(self, seconds):
        if self.stop.wait(max(0, seconds)):
            raise RuntimeError("controller cancelled")

    def wait_phase(self, node, phase, ordinal=None, seconds=240):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            found = [r for r in self.records(node, ordinal) if r["phase"] == phase and r["lock"] == "Open"]
            if found:
                return found[0]
            self.delay(.3)
        raise RuntimeError(f"node {node} did not reach {phase}")

    def verify_namespace(self):
        info = json.loads(self.command("docker", "inspect", self.names[2]))[0]
        networks = info["NetworkSettings"]["Networks"]
        if set(networks) != {self.network} or networks[self.network]["IPAddress"] != "172.30.194.12":
            raise RuntimeError("refusing firewall access outside exact owned lab network")
        net = json.loads(self.command("docker", "network", "inspect", self.network))[0]
        if not net["Internal"] or net["IPAM"]["Config"][0]["Subnet"] != "172.30.194.0/24":
            raise RuntimeError("owned network isolation is not intact")
        pid = info["State"]["Pid"]
        if not info["State"]["Running"] or not isinstance(pid, int) or pid <= 1:
            raise RuntimeError("invalid lab container PID")
        if self.pid is not None and self.pid != pid:
            raise RuntimeError("lab container PID changed")
        self.pid = pid
        return ("sudo", "-n", "nsenter", "--target", str(pid), "--net", "/usr/sbin/iptables", "-w", "3")

    def block_pulls(self):
        base = self.verify_namespace()
        for node in (0, 1, 3, 4):
            rule = ("OUTPUT", "-p", "tcp", "-d", f"172.30.194.{10+node}", "--dport", "9001",
                    "-m", "comment", "--comment", self.network, "-j", "DROP")
            self.command(*base, "-I", *rule)
            self.rules.append(rule)
        self.event("outgoing_pulls_blocked", node=2, pid=self.pid, rules=self.rules,
                   scope="owned container network namespace only; inbound pull responses remain allowed")

    def restore(self):
        if self.rules:
            base = self.verify_namespace()
            self.event("fault_firewall_counters", node=2, rules=self.command(*base, "-L", "OUTPUT", "-nvx"))
            for rule in list(reversed(self.rules)):
                self.command(*base, "-D", *rule)
                self.rules.remove(rule)
        for node in list(self.paused):
            self.command("docker", "unpause", self.names[node])
            self.paused.remove(node)
        self.event("faults_restored", ordinal=self.fault_ordinal)

    def run(self):
        try:
            previous = self.wait_phase(0, "Finished", self.previous_ordinal, seconds=300)
            if previous["count"] != 5 or previous["removed"]:
                raise RuntimeError("warm-up did not finish with five healthy facilitators")
            predicted = previous["time"] + 43
            if time.time() >= predicted - 2:
                raise RuntimeError("missed pre-phase fault deadline")
            self.event("staged_fault_planned", fault_ordinal=self.fault_ordinal, predicted_start=predicted)
            self.delay(predicted - 2 - time.time())
            self.command("docker", "pause", self.names[4])
            self.paused.add(4)
            paused_at = time.time()
            self.event("paused", node=4, ordinal=self.fault_ordinal)
            start = self.wait_phase(0, "CollectingFacilities", seconds=15)
            if not 0 < start["time"] - paused_at <= 5 or start["count"] != 5:
                raise RuntimeError("invalid first-stage fault alignment")
            self.wait_phase(3, "CollectingFacilities", seconds=15)
            self.delay(2)  # Let the native queue retain/serve node 3's facility before pausing it.
            if any(r["phase"] != "CollectingFacilities" for r in self.records(3)):
                raise RuntimeError("node 3 escaped facilities before pause")
            self.command("docker", "pause", self.names[3])
            self.paused.add(3)
            self.event("paused", node=3, ordinal=self.fault_ordinal)
            proposal = self.wait_phase(2, "CollectingProposals")
            self.delay(2)  # Keep node 2 alive to serve its proposal and later acknowledgment.
            if any(r["phase"] in ("CollectingSignatures", "Finished") for r in self.records(2)):
                raise RuntimeError("node 2 escaped proposals before transport fault")
            self.block_pulls()
            self.event("proposal_fault_started", proposal=proposal)
            for node in (0, 1):
                self.wait_phase(node, "Finished", seconds=240)
            if any(r["phase"] in ("CollectingSignatures", "Finished") for r in self.records(2)):
                raise RuntimeError("node 2 advanced despite intended missing-signature fault")
            results = [validate_round(self.records(node), self.ids) for node in (0, 1)]
            self.event("staged_recovery_validated", ordinal=self.fault_ordinal, results=results,
                       records=[self.records(node) for node in range(5)])
        except Exception as error:
            self.failure = str(error)
            self.event("staged_fault_failed", error=self.failure)
        finally:
            try:
                self.restore()
            except Exception as error:
                self.failure = f"{self.failure or ''}; restoration failed: {error}"
                self.event("restoration_failed", error=str(error))
            self.finished.set()
