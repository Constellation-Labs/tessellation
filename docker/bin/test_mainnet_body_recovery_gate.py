import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("body_devnet", Path(__file__).with_name("mainnet-response-deadline-devnet.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RecoveryGateTests(unittest.TestCase):
    def test_three_healthy_observers_must_agree(self):
        tips = [dict(ordinal=15, digest="same") for _ in range(3)]
        self.assertTrue(module.recovery_agreed(tips, 12, 3))
        tips[2]["digest"] = "different"
        self.assertFalse(module.recovery_agreed(tips, 12, 3))

    def test_pre_restoration_progress_does_not_satisfy_gate(self):
        tips = [dict(ordinal=13, digest="same") for _ in range(3)]
        self.assertFalse(module.recovery_agreed(tips, 12, 3))

    def test_missing_observer_fails(self):
        tips = [dict(ordinal=15, digest="same") for _ in range(2)]
        self.assertFalse(module.recovery_agreed(tips, 12, 3))
        self.assertFalse(module.recovery_agreed(tips + [None], 12, 3))


if __name__ == "__main__":
    unittest.main()
