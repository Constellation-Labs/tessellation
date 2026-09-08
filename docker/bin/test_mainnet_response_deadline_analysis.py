import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("body_analysis", Path(__file__).with_name("analyze-mainnet-response-deadline.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def fixture(mode):
    events = [dict(kind="identity", mode=mode, jar_sha256="test", peer_gossip_workers=2),
              dict(kind="five_ready"), dict(kind="body_fault_installed", time=100),
              dict(kind="body_fault_observed", time=310, finished=[mode == "fixed"]*3),
              dict(kind="body_fault_restored", ordinal=10),
              dict(kind="post_fault_rounds_observed", count=3), dict(kind="completed")]

    def sample(time, ordinal, epoch):
        return dict(time=time, tips=[dict(ordinal=ordinal, epoch=epoch, digest=f"hash-{ordinal}") for _ in range(5)])

    samples = [sample(95, 8, 19), sample(101, 9, 20), sample(309, 12 if mode == "fixed" else 9, 23 if mode == "fixed" else 20)]
    rewards = [dict(time=200, node=0, ordinal=10, reward_total_atomic=100)] if mode == "fixed" else []
    return events, samples, rewards


class EvidenceTests(unittest.TestCase):
    def analyze(self, data):
        events, samples, rewards = data
        with tempfile.TemporaryDirectory(prefix="gossip-deadline-test-") as directory:
            root = Path(directory)
            (root / "events.json").write_text(json.dumps(events))
            for name, rows in (("samples.jsonl", samples), ("reward-observations.jsonl", rewards)):
                if rows is None:
                    continue
                (root / name).write_text("".join(json.dumps(row)+"\n" for row in rows))
            return module.analyze(root)

    def test_stock_warmup_first_observed_after_injection_is_not_new_progress(self):
        report = self.analyze(fixture("stock"))
        self.assertTrue(report["checks_passed"])
        self.assertEqual([p["timed_epoch_increase"] for p in report["progress"]], [0]*3)
        self.assertFalse(report["reward_capture_available"])
        self.assertIsNone(report["captured_reward_total_atomic"])
        self.assertFalse(report["post_restoration_rounds_qualified"])

    def test_fixed_requires_actual_epoch_progress_and_counts_rewards_once(self):
        data = fixture("fixed")
        data[2].append(data[2][0].copy())
        report = self.analyze(data)
        self.assertEqual(report["captured_reward_total_atomic"], 100)
        self.assertEqual([p["timed_epoch_increase"] for p in report["progress"]], [3]*3)

    def test_absent_optional_reward_capture_is_not_zero_rewards(self):
        events, samples, _ = fixture("fixed")
        report = self.analyze((events, samples, None))
        self.assertFalse(report["reward_capture_available"])
        self.assertIsNone(report["captured_reward_total_atomic"])
        self.assertIsNone(report["evidence_sha256"]["reward-observations.jsonl"])

    def test_observed_value_conflict_fails(self):
        data = fixture("fixed")
        data[1][-1]["tips"][1]["digest"] = "conflict"
        with self.assertRaises(ValueError):
            self.analyze(data)

    def test_candidate_without_timed_progress_fails(self):
        data = fixture("fixed")
        for tip in data[1][-1]["tips"]:
            tip["epoch"] = 20
        with self.assertRaises(ValueError):
            self.analyze(data)

    def test_missing_completion_fails(self):
        data = fixture("stock")
        data[0].pop()
        with self.assertRaises(ValueError):
            self.analyze(data)

    def test_short_fault_window_fails(self):
        data = fixture("stock")
        data[0][3]["time"] = 200
        with self.assertRaises(ValueError):
            self.analyze(data)

    def test_stock_that_advances_fails(self):
        data = fixture("stock")
        for tip in data[1][-1]["tips"]:
            tip.update(ordinal=10, epoch=21, digest="hash-10")
        with self.assertRaises(ValueError):
            self.analyze(data)

    def restored_fixture(self):
        data = fixture("fixed")
        data[0][4]["time"] = 311
        data[0].insert(5, dict(kind="post_restoration_baseline", time=312, ordinal=12))
        data[0][6].update(restoration_baseline=12,
                          tips=[dict(ordinal=15, digest="same") for _ in range(3)])
        return data

    def test_stronger_recovery_gate_requires_recorded_post_restore_baseline(self):
        self.assertTrue(self.analyze(self.restored_fixture())["post_restoration_rounds_qualified"])

    def test_recovery_baseline_before_restoration_fails(self):
        data = self.restored_fixture()
        data[0][5]["time"] = 309
        with self.assertRaises(ValueError):
            self.analyze(data)

    def test_recovery_third_observer_conflict_fails(self):
        data = self.restored_fixture()
        data[0][6]["tips"][2]["digest"] = "different"
        with self.assertRaises(ValueError):
            self.analyze(data)


if __name__ == "__main__":
    unittest.main()
