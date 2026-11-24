#!/usr/bin/env python3
"""仅为已启用的 foodmap 站点加装静态 JS/CSS 压缩。"""
import os
from pathlib import Path
import stat
import subprocess
import tempfile


SITE = Path("/etc/nginx/sites-available/foodmap")
ENABLED = Path("/etc/nginx/sites-enabled/foodmap")
ORIGINAL = rb"""    location /assets/ {
        expires 1y;
        try_files $uri =404;
    }
"""
COMPRESSED = rb"""    location /assets/ {
        expires 1y;
        try_files $uri =404;

        location ~ \.(?:js|css)$ {
            try_files $uri =404;
            gzip on;
            gzip_types application/javascript text/javascript text/css;
            gzip_min_length 1024;
            gzip_comp_level 4;
            gzip_vary on;
        }
    }
"""


def atomic_write(path, content, mode):
    descriptor, temporary = tempfile.mkstemp(prefix=".foodmap-gzip-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(content)
            output.flush()
            os.chmod(temporary, mode)
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def enable(site, validate, reload):
    """只替换已核对的资产块；未知配置、失败或并行改写不静默覆盖。"""
    info = site.lstat()
    if not stat.S_ISREG(info.st_mode):
        raise RuntimeError("Expected a regular site configuration.")
    before = site.read_bytes()
    if before.count(b"    location /assets/ {") == 1 and before.count(COMPRESSED) == 1:
        validate()
        return None
    if before.count(b"    location /assets/ {") != 1 or before.count(ORIGINAL) != 1:
        raise RuntimeError("Unexpected /assets/ block; configuration preserved.")
    after = before.replace(ORIGINAL, COMPRESSED, 1)
    validate()
    if site.read_bytes() != before:
        raise RuntimeError("Site changed during preflight; configuration preserved.")
    backup = Path(tempfile.mkdtemp(prefix=".foodmap-before-static-gzip-", dir=site.parent.parent))
    atomic_write(backup / "foodmap.conf", before, 0o600)
    mode = stat.S_IMODE(info.st_mode)
    try:
        atomic_write(site, after, mode)
        validate()
        reload()
    except Exception:
        try:
            current = site.read_bytes()
            if current not in (before, after):
                raise RuntimeError("Unknown concurrent modification.")
            if current == after:
                atomic_write(site, before, mode)
                validate()
                reload()
        except Exception:
            raise RuntimeError(f"Update and automatic recovery failed; inspect Nginx. Backup: {backup}") from None
        raise RuntimeError(f"Update failed; original configuration retained or restored. Backup: {backup}") from None
    return backup


def run(arguments):
    """避免在诊断输出里打印可能包含私有文件内容的校验消息。"""
    result = subprocess.run(arguments, capture_output=True, timeout=30, check=False)
    if result.returncode:
        raise RuntimeError(f"{arguments[0]} failed; inspect the server locally.")


def main():
    if os.name != "posix" or os.geteuid() != 0:
        raise RuntimeError("Run on the server using sudo python3 enable-static-gzip.py.")
    if not ENABLED.is_symlink() or ENABLED.resolve() != SITE:
        raise RuntimeError("Expected the root-owned enabled foodmap site.")
    site_info = SITE.lstat()
    if site_info.st_uid != 0 or site_info.st_gid != 0:
        raise RuntimeError("Expected root:root ownership of the foodmap site.")
    backup = enable(SITE, lambda: run(["nginx", "-t"]), lambda: run(["systemctl", "reload", "nginx"]))
    print(f"Static gzip applied; backup: {backup}" if backup else "Static gzip configuration already present.")


if __name__ == "__main__":
    main()
