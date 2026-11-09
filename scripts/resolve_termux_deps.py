#!/usr/bin/env python3
"""解析 proot-distro 在 Termux 仓库里的完整依赖闭包。

输出 `M7A_PKG_LIST`（默认 build/work/termux-pkgs.txt），每行 5 列：
    Package<TAB>Version<TAB>Filename<TAB>Size<TAB>SHA256

Packages.bz2 缺失时会自动从 Termux 镜像下载，不再需要手工准备。

用法：
    python scripts/resolve_termux_deps.py
    python scripts/resolve_termux_deps.py --refresh
    M7A_TERMUX_MIRROR=https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main \
        python scripts/resolve_termux_deps.py
"""
from __future__ import annotations

import argparse
import bz2
import collections
import os
import re
import sys
import time
import urllib.error
import urllib.request

from _common import cache_dir, human, pkg_list

DEFAULT_MIRROR = "https://packages-cf.termux.dev/apt/termux-main"
DEFAULT_ARCH = "aarch64"

# 只装这些包，依赖自动展开
ROOTS = ["proot-distro"]

# bootstrap 里已经有的基础包，不再从仓库装（避免版本冲突/重复解包）
PROVIDED = {
    "sh", "bash", "coreutils", "dash", "libc", "shell", "awk", "sed", "grep",
    "findutils", "diffutils", "gzip", "tar", "bzip2", "xz-utils", "zlib",
    "libandroid-support", "termux-am", "termux-core", "termux-exec", "dpkg",
    "apt", "ca-certificates", "openssl", "libcurl", "libssl", "ncurses",
    "ncurses-utils", "readline", "libreadline",
}

UA = "M7A-build-script/1.0"


def log(*args):
    print(*args, flush=True)


def download(url: str, dest: str, attempts: int = 5, timeout: int = 120) -> str:
    os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)
    part = dest + ".part"
    for attempt in range(1, attempts + 1):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(request, timeout=timeout) as response, \
                    open(part, "wb") as f:
                while True:
                    block = response.read(1 << 20)
                    if not block:
                        break
                    f.write(block)
            if os.path.getsize(part) == 0:
                raise RuntimeError("下载内容为空")
            os.replace(part, dest)
            return dest
        except Exception as e:  # noqa: BLE001
            if os.path.exists(part):
                os.remove(part)
            if attempt == attempts:
                raise
            log(f"    下载失败（{attempt}/{attempts}）：{type(e).__name__}: {e}")
            time.sleep(2)
    raise RuntimeError(f"下载失败：{url}")


def index_url(mirror: str, arch: str) -> str:
    return (f"{mirror.rstrip('/')}/dists/stable/main/"
            f"binary-{arch}/Packages.bz2")


def load_index(mirror: str, arch: str, path: str, refresh: bool = False,
               log_fn=log) -> str:
    if refresh or not os.path.isfile(path):
        url = index_url(mirror, arch)
        log_fn(f"下载索引：{url}")
        download(url, path)
        log_fn(f"索引大小：{human(os.path.getsize(path))}")
    with bz2.open(path, "rt", encoding="utf-8", errors="replace") as f:
        return f.read()


def parse_index(text: str) -> dict[str, dict]:
    """把 Packages 文本解析成 {包名: 字段字典}。"""
    packages: dict[str, dict] = {}
    current: dict[str, str] = {}

    def flush():
        if current.get("Package"):
            packages[current["Package"]] = dict(current)

    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line:
            flush()
            current = {}
            continue
        if line.startswith("#") or ":" not in line:
            continue
        key, value = line.split(":", 1)
        current[key.strip()] = value.strip()
    flush()
    return packages


def parse_deps(value: str) -> list[str]:
    """解析 Depends 字段：处理 `a | b` 备选和版本约束。"""
    deps = []
    for item in value.split(","):
        item = item.strip()
        if not item:
            continue
        # 多选一先取第一个；bootstrap 通常提供不了备选里的第二个
        name = item.split("|")[0].strip()
        name = re.split(r"[\s(]", name)[0]
        name = re.split(r"[<>=]", name)[0]
        if name:
            deps.append(name)
    return deps


def resolve(mirror: str, arch: str, cache: str,
            packages_path: str | None = None,
            refresh: bool = False, log_fn=log) -> list[str]:
    """返回包清单行（不写文件）。"""
    index_path = packages_path or os.path.join(cache, f"Packages-{arch}.bz2")
    text = load_index(mirror, arch, index_path, refresh=refresh, log_fn=log_fn)
    packages = parse_index(text)
    log_fn(f"仓库共 {len(packages)} 个包，开始从 {', '.join(ROOTS)} 展开依赖")

    resolved: set[str] = set()
    needed: list[dict] = []
    missing: list[str] = []
    queue = collections.deque(ROOTS)

    while queue:
        name = queue.popleft()
        if name in resolved or name in PROVIDED:
            continue
        info = packages.get(name)
        if not info:
            missing.append(name)
            resolved.add(name)
            continue
        resolved.add(name)
        needed.append(info)
        for field in ("Pre-Depends", "Depends"):
            for dep in parse_deps(info.get(field, "")):
                if dep not in resolved and dep not in PROVIDED:
                    queue.append(dep)

    if missing:
        log_fn(f"警告：仓库里找不到这些依赖（可能由 bootstrap 提供）："
               f"{', '.join(sorted(set(missing)))}")
    if not needed:
        raise RuntimeError("没有解析出任何包，检查镜像地址/架构是否正确")

    needed.sort(key=lambda p: p["Package"])
    lines = []
    total = 0
    for info in needed:
        size = info.get("Size", "0")
        total += int(size) if size.isdigit() else 0
        lines.append("\t".join([
            info.get("Package", ""),
            info.get("Version", ""),
            info.get("Filename", ""),
            size,
            info.get("SHA256", ""),
        ]))
    log_fn(f"依赖闭包：{len(lines)} 个包，下载合计 {human(total)}")
    return lines


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="解析 Termux 依赖闭包并生成包清单",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--mirror", default=os.environ.get("M7A_TERMUX_MIRROR", DEFAULT_MIRROR),
                        help=f"Termux 仓库（默认 {DEFAULT_MIRROR}）")
    parser.add_argument("--arch", default=os.environ.get("M7A_TERMUX_ARCH", DEFAULT_ARCH),
                        help="架构（默认 aarch64）")
    parser.add_argument("--packages", default=None,
                        help="Packages.bz2 路径（默认放缓存目录，缺失自动下载）")
    parser.add_argument("--out", default=pkg_list(),
                        help="输出清单（默认 M7A_PKG_LIST）")
    parser.add_argument("--cache", default=cache_dir(),
                        help="下载缓存目录（默认 M7A_CACHE）")
    parser.add_argument("--refresh", action="store_true",
                        help="强制重新下载 Packages.bz2")
    args = parser.parse_args(argv)

    try:
        lines = resolve(args.mirror, args.arch, os.path.abspath(args.cache),
                        packages_path=args.packages, refresh=args.refresh)
    except Exception as e:  # noqa: BLE001
        log(f"!! 解析依赖失败：{e}")
        return 1

    out = os.path.abspath(args.out)
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    log(f"写入 {out}（{len(lines)} 个包）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
