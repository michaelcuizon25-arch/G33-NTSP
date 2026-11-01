"""Regression for Git for Windows changing a byte-verified OCR asset before APK packaging."""
import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest


class PaddleAssetCheckoutTest(unittest.TestCase):
    def test_dictionary_retains_official_bytes_under_windows_line_ending_settings(self):
        root = Path(__file__).resolve().parents[1]
        name = 'app/src/main/assets/ocr/paddle/ppocrv5_en_dict.txt'
        expected_hash = 'e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6'
        original = (root / name).read_bytes()
        indexed = subprocess.check_output(['git', '-C', str(root), 'show', ':' + name])
        self.assertEqual(expected_hash, hashlib.sha256(indexed).hexdigest())
        for autocrlf in ['true', 'false']:
            with self.subTest(autocrlf=autocrlf), tempfile.TemporaryDirectory(prefix='paddle-checkout-') as directory:
                # Reads the real repository attributes; writes only into a temporary prefix.
                subprocess.run(['git', '-C', str(root), '-c', 'core.autocrlf=' + autocrlf,
                    '-c', 'core.eol=crlf', 'checkout-index', '--prefix=' + Path(directory).as_posix() + '/',
                    '--', name], check=True)
                checked_out = (Path(directory) / name).read_bytes()
                self.assertEqual(indexed, checked_out, 'Git checkout changed the byte-verified asset')
                self.assertEqual(expected_hash, hashlib.sha256(checked_out).hexdigest())
                self.assertEqual(1416, len(checked_out))
                self.assertNotIn(b'\r', checked_out)
        self.assertEqual(original, (root / name).read_bytes(), 'Regression test must not modify the working asset')


if __name__ == '__main__':
    unittest.main()
