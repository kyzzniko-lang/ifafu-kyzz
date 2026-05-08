import unittest

from ifafu_kyzz import ifafu_kyzz_create


class IfafuKyzzTests(unittest.TestCase):
    def test_ifafu_kyzz_create_returns_expected_value(self) -> None:
        self.assertEqual(ifafu_kyzz_create(), "ifafu_kyzz_create")


if __name__ == "__main__":
    unittest.main()
