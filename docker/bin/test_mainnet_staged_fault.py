import copy
import json
import unittest

from mainnet_staged_fault import StagedFaultController, PHASES, parse_states, validate_round


class StagedFaultTests(unittest.TestCase):
    def setUp(self):
        self.ids = [str(i)*128 for i in range(5)]
        self.rows = []
        for i, phase in enumerate(PHASES):
            row = dict(time=60*i, ordinal=10, lock="Open", count=5-i,
                       removed=sorted(self.ids[j][:8] for j in (4, 3, 2)[:i]), withdrawn=[], phase=phase)
            self.rows.append(row)
            if i < 3:
                self.rows.append(dict(row, time=60*i+50, lock="Closed"))

    def test_valid_three_stage_round(self):
        self.assertEqual(validate_round(self.rows, self.ids)["phase_seconds"], [60, 60, 60])

    def test_wrong_removed_peer_rejected(self):
        self.rows[-1]["removed"][-1] = "ffffffff"
        with self.assertRaises(ValueError):
            validate_round(self.rows, self.ids)

    def test_withdrawal_not_misreported_as_removal(self):
        self.rows[-1]["withdrawn"] = [self.ids[2][:8]]
        with self.assertRaises(ValueError):
            validate_round(self.rows, self.ids)

    def test_missing_or_early_lock_rejected(self):
        for rows in ([r for r in self.rows if r["lock"] != "Closed"], copy.deepcopy(self.rows)):
            if len(rows) == len(self.rows):
                rows[1]["time"] = 40
            with self.assertRaises(ValueError):
                validate_round(rows, self.ids)

    def test_real_multiline_format_short_ids(self):
        log = "2026-09-07T18:23:00.123456789Z INFO State updated ConsensusState{\n2026-09-07T18:23:00.123456790Z key=SnapshotOrdinal{value=10}, lockStatus=Open{}, facilitatorCount=4, removedFacilitators=Set(44444444), withdrawnFacilitators=Set(), spreadAckKinds=Set(), status=CollectingProposals{foo}\n"
        rows = parse_states(log)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["removed"], ["44444444"])
        self.assertEqual(rows[0]["phase"], "CollectingProposals")

    def test_namespace_rejects_other_network_and_changed_pid(self):
        info = dict(NetworkSettings=dict(Networks={"lab": dict(IPAddress="172.30.194.12")}),
                    State=dict(Running=True, Pid=1234))
        net = dict(Internal=True, IPAM=dict(Config=[dict(Subnet="172.30.194.0/24")]))
        def command(*args):
            return json.dumps([net if args[1] == "network" else info])
        controller = StagedFaultController(command, ["lab-"+str(i) for i in range(5)], "lab",
                                           [dict(id=i) for i in self.ids], lambda *a, **k: None, 9)
        self.assertEqual(controller.verify_namespace()[:6], ("sudo", "-n", "nsenter", "--target", "1234", "--net"))
        info["State"]["Pid"] = 1235
        with self.assertRaises(RuntimeError):
            controller.verify_namespace()
        info["State"]["Pid"] = 1234
        net["Internal"] = False
        with self.assertRaises(RuntimeError):
            controller.verify_namespace()
        net["Internal"] = True
        info["NetworkSettings"]["Networks"]["public"] = dict(IPAddress="1.2.3.4")
        with self.assertRaises(RuntimeError):
            controller.verify_namespace()


if __name__ == "__main__":
    unittest.main()
