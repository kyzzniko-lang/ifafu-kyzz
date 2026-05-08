import unittest

from ifafu_kyzz import ifafu_kyzz_create


class IfafuKyzzTests(unittest.TestCase):
    def test_create_returns_canonical_token(self) -> None:
        self.assertEqual(ifafu_kyzz_create(), "ifafu_kyzz_create")


if __name__ == "__main__":
    unittest.main()
