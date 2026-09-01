import unittest

from cmux_relay import is_tailnet_address, resolve_tailnet_bind


class RelayAddressTest(unittest.TestCase):
    def test_accepts_tailscale_ipv4(self):
        self.assertTrue(is_tailnet_address("100.100.10.4"))

    def test_rejects_wildcard_and_loopback(self):
        self.assertFalse(is_tailnet_address("0.0.0.0"))
        self.assertFalse(is_tailnet_address("127.0.0.1"))
        self.assertFalse(is_tailnet_address("192.168.1.20"))

    def test_rejects_non_tailnet_hostname(self):
        with self.assertRaises(ValueError):
            resolve_tailnet_bind("localhost")


if __name__ == "__main__":
    unittest.main()
