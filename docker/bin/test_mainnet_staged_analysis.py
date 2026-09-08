import datetime as dt
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("staged_analysis", Path(__file__).with_name("analyze-mainnet-staged.py"))
analysis = importlib.util.module_from_spec(spec)
spec.loader.exec_module(analysis)


class EvidenceGateTests(unittest.TestCase):
    def fixture(self, root):
        ids = [str(i)*128 for i in range(5)]
        (root/"peers.json").write_text(json.dumps([dict(id=i) for i in ids]))
        events = [dict(kind="staged_fault_planned", fault_ordinal=10)]
        events += [dict(kind=k) for k in ("completed", "post_fault_rounds_observed", "faults_restored")]
        (root/"events.json").write_text(json.dumps(events))
        tips = [dict(ordinal=10, digest="same", signers=ids[:2]) for _ in range(2)] + [None]*3
        (root/"samples.jsonl").write_text(json.dumps(dict(tips=tips))+"\n")
        lines = []
        for ordinal in (8, 9, 10):
            for i, phase in enumerate(analysis.PHASES):
                count = 5-i if ordinal == 10 else 5
                removed = ", ".join(ids[j][:8] for j in (4, 3, 2)[:i]) if ordinal == 10 else ""
                for lock, offset in (("Open", 0), ("Closed", 50)) if ordinal == 10 and i < 3 else (("Open", 0),):
                    stamp = dt.datetime.fromtimestamp(1788809000+ordinal*300+i*(60 if ordinal==10 else 1)+offset, dt.timezone.utc).isoformat()
                    lines += [f"{stamp} INFO State updated ConsensusState{{",
                              f"{stamp} key=SnapshotOrdinal{{value={ordinal}}}, lockStatus={lock}{{}}, facilitatorCount={count}, removedFacilitators=Set({removed}), withdrawnFacilitators=Set(), status={phase}{{}}"]
        for node in (0, 1):
            (root/f"mr-stock-fixture-{node}.log").write_text("\n".join(lines))
        return tips

    def test_complete_evidence_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            self.assertEqual(analysis.analyze(root)["status"], "pass")

    def test_conflicting_value_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tips = self.fixture(root)
            tips[1]["digest"] = "conflict"
            (root/"samples.jsonl").write_text(json.dumps(dict(tips=tips))+"\n")
            result = analysis.analyze(root)
            self.assertEqual(result["status"], "failed_or_incomplete")
            self.assertEqual(result["conflicts"], [10])

    def test_missing_snapshot_or_wrong_signers_fails(self):
        for missing in (True, False):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                tips = self.fixture(root)
                if missing:
                    tips[1] = None
                else:
                    tips[1]["signers"] = ["f"*128]
                (root/"samples.jsonl").write_text(json.dumps(dict(tips=tips))+"\n")
                self.assertEqual(analysis.analyze(root)["status"], "failed_or_incomplete")


if __name__ == "__main__":
    unittest.main()
