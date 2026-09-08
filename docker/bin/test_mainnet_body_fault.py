import unittest

from mainnet_body_fault import BodyFaultController, serve


class NamespaceSafetyTests(unittest.TestCase):
    def controller(self, command):
        return BodyFaultController(command, [f"lab-{i}" for i in range(5)], "lab-private",
                                   [], lambda *a, **kw: None, 9, "/tmp/not-created", "stock")

    def test_external_bind_rejected_before_opening_log_or_socket(self):
        for ip in ("0.0.0.0", "127.0.0.1", "203.0.113.10", "172.30.194.10"):
            with self.assertRaises(ValueError):
                serve(ip, "/tmp/not-created", "/tmp/not-created-stop")

    def test_wrong_network_rejected(self):
        command = lambda *a: '[{"NetworkSettings":{"Networks":{"public":{"IPAddress":"172.30.194.10"}}}}]'
        with self.assertRaises(RuntimeError):
            self.controller(command).namespace(0)

    def test_wrong_address_rejected(self):
        command = lambda *a: '[{"NetworkSettings":{"Networks":{"lab-private":{"IPAddress":"172.30.194.11"}}}}]'
        with self.assertRaises(RuntimeError):
            self.controller(command).namespace(0)

    def test_noninternal_bridge_rejected(self):
        answers = iter([
            '[{"NetworkSettings":{"Networks":{"lab-private":{"IPAddress":"172.30.194.10"}}},"State":{"Pid":500,"Running":true}}]',
            '[{"Internal":false,"IPAM":{"Config":[{"Subnet":"172.30.194.0/24"}]}}]'
        ])
        with self.assertRaises(RuntimeError):
            self.controller(lambda *a: next(answers)).namespace(0)

    def test_exact_owned_namespace_allowed(self):
        answers = iter([
            '[{"NetworkSettings":{"Networks":{"lab-private":{"IPAddress":"172.30.194.10"}}},"State":{"Pid":500,"Running":true}}]',
            '[{"Internal":true,"IPAM":{"Config":[{"Subnet":"172.30.194.0/24"}]}}]'
        ])
        self.assertEqual(self.controller(lambda *a: next(answers)).namespace(0),
                         ("sudo", "-n", "nsenter", "--target", "500", "--net"))


if __name__ == "__main__":
    unittest.main()
