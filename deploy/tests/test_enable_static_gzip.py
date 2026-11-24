"""在临时站点配置上验证压缩更新和回滚，不访问系统 Nginx。"""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock


DEPLOY = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("enable_static_gzip", DEPLOY / "nginx/enable-static-gzip.py")
gzip_update = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gzip_update)


class StaticGzipUpdateTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.site = self.root / "sites-available" / "foodmap"
        self.site.parent.mkdir()
        self.prefix = b"server {\n    listen 443 ssl;\n"
        self.suffix = b"    location /api/ {}\n}\n"
        self.original = self.prefix + gzip_update.ORIGINAL + self.suffix
        self.updated = self.prefix + gzip_update.COMPRESSED + self.suffix
        self.site.write_bytes(self.original)

    def test_matches_repository_asset_block(self):
        config = (DEPLOY / "nginx.conf").read_bytes().replace(b"\r\n", b"\n")
        self.assertEqual(config.count(gzip_update.COMPRESSED), 1)

    def test_updates_only_asset_block_and_is_repeatable(self):
        validate, reload = Mock(), Mock()
        backup = gzip_update.enable(self.site, validate, reload)
        self.assertEqual(backup.parent, self.root)
        self.assertEqual((backup / "foodmap.conf").read_bytes(), self.original)
        self.assertEqual(self.site.read_bytes(), self.updated)
        self.assertEqual(validate.call_count, 2)
        reload.assert_called_once()
        self.assertIsNone(gzip_update.enable(self.site, validate, reload))
        reload.assert_called_once()

    def test_unknown_asset_block_is_not_replaced(self):
        unexpected = self.original.replace(b"expires 1y;", b"expires 7d;")
        self.site.write_bytes(unexpected)
        validate, reload = Mock(), Mock()
        with self.assertRaisesRegex(RuntimeError, "Unexpected /assets/ block"):
            gzip_update.enable(self.site, validate, reload)
        self.assertEqual(self.site.read_bytes(), unexpected)
        validate.assert_not_called()
        reload.assert_not_called()

    def test_new_configuration_check_failure_restores_original(self):
        validate = Mock(side_effect=[None, RuntimeError("invalid configuration"), None])
        reload = Mock()
        with self.assertRaisesRegex(RuntimeError, "original configuration retained or restored"):
            gzip_update.enable(self.site, validate, reload)
        self.assertEqual(self.site.read_bytes(), self.original)
        self.assertEqual(validate.call_count, 3)
        reload.assert_called_once()

    def test_reload_failure_restores_file_and_reloads_old_configuration(self):
        validate = Mock()
        reload = Mock(side_effect=[RuntimeError("reload error"), None])
        with self.assertRaisesRegex(RuntimeError, "original configuration retained or restored"):
            gzip_update.enable(self.site, validate, reload)
        self.assertEqual(self.site.read_bytes(), self.original)
        self.assertEqual(validate.call_count, 3)
        self.assertEqual(reload.call_count, 2)
