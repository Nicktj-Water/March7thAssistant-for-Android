#!/usr/bin/env python3
"""审计打包后的 rootfs，重点看可执行位有没有丢。

大部分文件本来就不需要 +x；真正要命的是 usr/bin 下的命令。
同时报告执行目录里“看起来该可执行却没有 +x”的文件，
以及权限表里没有记录的路径（这些会按默认 0644 打包）。

用法：
    python scripts/audit_rootfs_permissions.py
    python scripts/audit_rootfs_permissions.py --rootfs build/work/out.bin
    python scripts/audit_rootfs_permissions.py --strict   # exec 目录问题也算失败
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import tarfile
import time

from _common import rootfs_mode_map, rootfs_out

# 这些目录里的文件通常应该可执行
EXEC_DIRS = (
    "usr/bin/", "usr/sbin/", "usr/local/bin/", "usr/local/sbin/",
    "bin/", "sbin/", "opt/venv/bin/", "usr/libexec/",
)

# 这些路径必须可执行，否则 proot 里连 shell 都起不来
MUST_EXEC = {
    "usr/bin/bash", "usr/bin/sh", "usr/bin/dash", "usr/bin/env", "usr/bin/ls",
    "usr/bin/cat", "usr/bin/chmod", "usr/bin/chown", "usr/bin/mkdir",
    "usr/bin/rm", "usr/bin/ln", "usr/bin/cp", "usr/bin/mv", "usr/bin/touch",
    "usr/bin/grep", "usr/bin/sed", "usr/bin/awk", "usr/bin/find", "usr/bin/xargs",
    "usr/bin/tar", "usr/bin/gzip", "usr/bin/uname", "usr/bin/id", "usr/bin/date",
    "usr/bin/head", "usr/bin/tail", "usr/bin/wc", "usr/bin/cut", "usr/bin/sort",
    "usr/bin/tr", "usr/bin/dirname", "usr/bin/basename", "usr/bin/readlink",
    "usr/bin/realpath", "usr/bin/which", "usr/bin/apt", "usr/bin/apt-get",
    "usr/bin/dpkg", "usr/bin/python3", "usr/bin/chromium", "usr/bin/chromedriver",
    "opt/venv/bin/python", "opt/venv/bin/python3", "opt/venv/bin/pip",
}

# 这些后缀在 usr/bin / opt/venv/bin 里没有 +x 是正常的
NO_EXEC_SUFFIXES = (
    ".so", ".py", ".pyc", ".h", ".json", ".txt", ".md", ".1", ".gz",
    ".cfg", ".ini", ".pth", ".dist-info", ".cmake", ".pc", ".a", ".whl",
)


def log(*args):
    print(*args, flush=True)


def _norm(name: str) -> str:
    """与 assemble_rootfs.norm 保持一致（不要用 lstrip('./')）。"""
    while True:
        if name.startswith("./"):
            name = name[2:]
        elif name.startswith("/"):
            name = name[1:]
        else:
            return name


def resolve_link(modes: dict, path: str):
    """顺着符号链接找到最终目标，返回 (最终路径, 权限) 或 (None, None)。"""
    seen = set()
    for _ in range(20):
        if path in seen:
            return None, None
        seen.add(path)
        entry = modes.get(path)
        if entry is None:
            return None, None
        mode, is_sym, target = entry
        if not is_sym:
            return path, mode
        if not target:
            return None, None
        if target.startswith("/"):
            path = _norm(target)
        else:
            joined = os.path.normpath(os.path.join(os.path.dirname(path), target))
            if joined.startswith(".."):
                return None, None
            path = _norm(joined)
    return None, None


def read_tar(rootfs: str) -> dict:
    """流式扫描 rootfs tar，返回 {路径: (权限, 是否符号链接, 目标)}。"""
    modes = {}
    count = 0
    with tarfile.open(rootfs, "r|gz") as tf:
        for member in tf:
            name = _norm(member.name)
            if not name:
                continue
            modes[name] = (
                member.mode & 0o7777,
                member.issym(),
                member.linkname if member.issym() else "",
            )
            count += 1
    return modes, count


def audit(rootfs: str, mode_map: str, strict: bool = False) -> int:
    log("=== rootfs 可执行位审计 ===")
    log(f"rootfs: {rootfs}")
    log(f"权限表: {mode_map}")

    started = time.time()
    try:
        modes, count = read_tar(rootfs)
    except (OSError, tarfile.TarError) as e:
        log(f"!! 打不开 rootfs：{e}")
        return 1
    log(f"扫描 {count} 个条目，用时 {time.time() - started:.0f}s")

    log("\n--- 必须可执行 ---")
    bad = []
    for path in sorted(MUST_EXEC):
        entry = modes.get(path)
        if entry is None:
            log(f"  MISSING   {path}")
            bad.append(path)
            continue
        mode, is_sym, target = entry
        if is_sym:
            final, final_mode = resolve_link(modes, path)
            if final is None or not (final_mode & 0o111):
                bad.append(path)
                log(f"  BAD       {path} -> {target}（链接目标缺失或不可执行）")
            else:
                log(f"  SYMLINK   {path} -> {final} {oct(final_mode)}")
            continue

        ok = bool(mode & 0o111)
        if not ok:
            bad.append(path)
        log(f"  {'OK ' if ok else 'BAD'}       {path:28s} {oct(mode)}")

    log("\n--- 执行目录里缺 +x 的文件（排除库/脚本）---")
    problem = []
    for path, (mode, is_sym, _target) in modes.items():
        if is_sym or not path.startswith(EXEC_DIRS):
            continue
        if mode & 0o111:
            continue
        if path.endswith(NO_EXEC_SUFFIXES):
            continue
        if ".dist-info/" in path or "/site-packages/" in path or "/share/" in path:
            continue
        problem.append((path, mode))

    log(f"  count: {len(problem)}")
    for path, mode in sorted(problem)[:40]:
        log(f"    {path:60s} {oct(mode)}")

    log("\n--- 权限表里没有记录的路径（会按 0644 打包）---")
    recorded = {}
    if os.path.isfile(mode_map):
        try:
            with open(mode_map, encoding="utf-8") as f:
                recorded = json.load(f)
        except (OSError, ValueError) as e:
            log(f"  warn: 读权限表失败：{e}")
    unrecorded = [p for p in modes if p not in recorded and not modes[p][1]]
    log(f"  count: {len(unrecorded)}")
    for path in sorted(unrecorded)[:40]:
        log(f"    {path:60s} {oct(modes[path][0])}")

    log("")
    failed = bool(bad) or (strict and (bool(problem) or bool(unrecorded)))
    if bad:
        log(f"RESULT: FAIL - 以下必须可执行的文件有问题：{', '.join(bad)}")
    elif problem:
        log(f"RESULT: {'FAIL' if strict else 'WARN'} - "
            f"执行目录里有 {len(problem)} 个文件缺 +x")
    elif unrecorded:
        log(f"RESULT: {'FAIL' if strict else 'WARN'} - "
            f"{len(unrecorded)} 个路径没有权限记录")
    else:
        log("RESULT: PASS - 所有必须可执行的文件都有执行位")
    return 1 if failed else 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="审计 rootfs 的可执行位和权限表覆盖率",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--rootfs", default=rootfs_out(),
                        help="打包后的 .bin（默认 M7A_OUT）")
    parser.add_argument("--modes", default=rootfs_mode_map(),
                        help="权限表 JSON（默认 M7A_MODE_MAP）")
    parser.add_argument("--strict", action="store_true",
                        help="执行目录缺 +x / 权限表缺记录也视为失败")
    args = parser.parse_args(argv)
    return audit(os.path.abspath(args.rootfs), os.path.abspath(args.modes),
                 strict=args.strict)


if __name__ == "__main__":
    sys.exit(main())
