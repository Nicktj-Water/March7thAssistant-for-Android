#!/usr/bin/env python3
"""把 OCI 镜像层展开成 Android 用的 rootfs 资产（无人值守版）。

步骤：
  1. 若 `M7A_STAGE` 还没有展开过，逐层解包 `M7A_ROOTFS_SRC/layer*.tar.gz`；
     同时从层 tar 元数据里收集每个路径的权限（Windows 上解包会丢 +x）。
  2. 可选裁剪：`--strip /m7a` 删掉项目代码目录；
     `--strip-openvino` 删掉 venv 里 site-packages 下的 openvino 系列包。
  3. 按权限表重新打 tar.gz，写到 `app/src/main/assets/rootfs/m7a-rootfs.bin`。

用法：
    # CI 全流程（首次）
    python scripts/assemble_rootfs.py --strip /m7a --strip-openvino

    # 只重新打包（stage 和模式表已经在）
    python scripts/assemble_rootfs.py --pack --strip /m7a

    # 强制重新解包（层更新后）
    python scripts/assemble_rootfs.py --force --strip /m7a --strip-openvino
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import shutil
import sys
import tarfile

from _common import (
    human,
    rootfs_marker,
    rootfs_mode_map,
    rootfs_out,
    rootfs_src,
    rootfs_stage,
)

DEFAULT_FILE_MODE = 0o644
DEFAULT_DIR_MODE = 0o755
DEFAULT_LINK_MODE = 0o777

WHITEOUT = ".wh."
WHITEOUT_OPAQUE = ".wh..wh..opq"


def log(*args):
    print(*args, flush=True)


# ---------------------------------------------------------------- 名字/文件

def norm(name: str) -> str:
    """把 tar 里的 `./usr/bin`、`/usr/bin` 统一成 `usr/bin`。

    注意不能写成 lstrip("./")，那会把 `.dockerenv` 之类的点文件也吃掉。
    """
    while True:
        if name.startswith("./"):
            name = name[2:]
        elif name.startswith("/"):
            name = name[1:]
        else:
            return name


def _safe_rel(spec: str) -> str:
    """把用户给的 --strip 路径规范化，并阻止 ../ 逃出 stage。"""
    rel = norm(spec.strip())
    parts = [p for p in rel.split("/") if p not in ("", ".")]
    if not parts or any(p == ".." for p in parts):
        raise ValueError(f"--strip 路径不合法：{spec!r}")
    return "/".join(parts)


def _remove_path(path: str) -> None:
    """删除文件/目录/符号链接本身（不跟随链接）。"""
    if not os.path.lexists(path):
        return
    if os.path.isdir(path) and not os.path.islink(path):
        shutil.rmtree(path)
    else:
        os.remove(path)


def tree_size(path: str):
    """返回 (字节数, 文件数)，不跟随符号链接。"""
    if not os.path.lexists(path):
        return 0, 0
    if not os.path.isdir(path) or os.path.islink(path):
        try:
            return os.lstat(path).st_size, 1
        except OSError:
            return 0, 1

    total = 0
    count = 0
    for root, dirs, files in os.walk(path):
        dirs.sort()
        for name in files:
            try:
                total += os.lstat(os.path.join(root, name)).st_size
            except OSError:
                pass
            count += 1
        # os.walk 不进入符号链接目录，但它们也占 inode/空间，单独统计
        for name in dirs:
            full = os.path.join(root, name)
            if os.path.islink(full):
                try:
                    total += os.lstat(full).st_size
                except OSError:
                    pass
                count += 1
    return total, count


def find_layers(src: str) -> list[str]:
    if not os.path.isdir(src):
        return []
    return sorted(glob.glob(os.path.join(src, "layer*.tar.gz")))


def load_modes(path: str) -> dict:
    if not os.path.isfile(path):
        return {}
    with open(path, encoding="utf-8") as f:
        raw = json.load(f)
    return {k: tuple(v) for k, v in raw.items()}


def save_modes(path: str, modes: dict) -> None:
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(modes, f, ensure_ascii=False, sort_keys=True)
    os.replace(tmp, path)


def _purge(modes: dict, victim: str) -> None:
    """从权限表里删掉 victim 及其整棵子树。"""
    if not victim:
        modes.clear()
        return
    for key in [k for k in modes if k == victim or k.startswith(victim + "/")]:
        del modes[key]


# ---------------------------------------------------------------- 解包

def harvest_modes(layer_files: list[str]) -> dict:
    """流式扫描每一层，记录最终每个路径的类型和权限。"""
    modes: dict[str, tuple] = {}
    for layer in layer_files:
        log(f"  扫描 {os.path.basename(layer)}")
        with tarfile.open(layer, "r|gz") as tf:
            for member in tf:
                name = norm(member.name)
                if not name:
                    continue
                base = os.path.basename(name)
                if base == WHITEOUT_OPAQUE:
                    _purge(modes, os.path.dirname(name))
                    continue
                if base.startswith(WHITEOUT):
                    victim = os.path.join(
                        os.path.dirname(name), base[len(WHITEOUT):]
                    )
                    _purge(modes, victim)
                    continue

                if member.issym():
                    modes[name] = ("link", member.linkname)
                elif member.isdir():
                    modes[name] = ("dir", member.mode)
                elif member.isfile():
                    modes[name] = ("file", member.mode)
                elif member.islnk():
                    modes[name] = ("hardlink", member.linkname)
    log(f"  权限表：{len(modes)} 个路径")
    return modes


def extract_layer(layer: str, stage: str) -> dict:
    """把一层展开进 stage，处理 whiteout、符号链接、硬链接。"""
    log(f"  解包 {os.path.basename(layer)}")
    counts = {"dir": 0, "file": 0, "symlink": 0, "hardlink": 0, "skip": 0}
    with tarfile.open(layer, "r|gz") as tf:
        for member in tf:
            name = norm(member.name)
            if not name:
                continue
            base = os.path.basename(name)

            # OCI whiteout：删除整棵子树
            if base == WHITEOUT_OPAQUE:
                prefix = os.path.dirname(name)
                target = os.path.join(stage, prefix) if prefix else stage
                if os.path.isdir(target) and not os.path.islink(target):
                    for child in os.listdir(target):
                        _remove_path(os.path.join(target, child))
                counts["skip"] += 1
                continue
            if base.startswith(WHITEOUT):
                victim = os.path.join(
                    os.path.dirname(name), base[len(WHITEOUT):]
                )
                _remove_path(os.path.join(stage, victim))
                counts["skip"] += 1
                continue

            dst = os.path.join(stage, *name.split("/"))
            parent = os.path.dirname(dst)
            if parent:
                os.makedirs(parent, exist_ok=True)

            if member.isdir():
                if os.path.lexists(dst) and not os.path.isdir(dst):
                    _remove_path(dst)
                os.makedirs(dst, exist_ok=True)
                counts["dir"] += 1
            elif member.issym():
                _remove_path(dst)
                os.symlink(member.linkname, dst)
                counts["symlink"] += 1
            elif member.islnk():
                source = os.path.join(stage, *norm(member.linkname).split("/"))
                _remove_path(dst)
                try:
                    os.link(source, dst)
                except OSError:
                    # 跨层硬链接可能指向已被 whiteout 的路径，退化成普通拷贝
                    shutil.copy2(source, dst)
                counts["hardlink"] += 1
            elif member.isfile():
                if os.path.isdir(dst) and not os.path.islink(dst):
                    shutil.rmtree(dst)
                with tf.extractfile(member) as src, open(dst, "wb") as out:
                    shutil.copyfileobj(src, out, 1 << 20)
                counts["file"] += 1
            else:
                # 设备节点 / fifo 在 proot 里没有意义
                counts["skip"] += 1
    return counts


# ---------------------------------------------------------------- 裁剪

def strip_path(stage: str, spec: str) -> int:
    """删除 stage 下的相对路径，返回释放的字节数。

    注意：/m7a 是运行时 bind 挂载点，删掉内容后必须把空目录建回来，
    否则 proot 启动时挂载会失败。
    """
    rel = _safe_rel(spec)
    target = os.path.join(stage, *rel.split("/"))
    log(f"[strip] /{rel}")
    size = 0
    if not os.path.lexists(target):
        log("  不存在，跳过（可能已经裁剪过）")
    else:
        size, count = tree_size(target)
        _remove_path(target)
        log(f"  删除 {human(size)} / {count} 个文件")
    if rel == "m7a":
        os.makedirs(target, exist_ok=True)
        log("  重建空目录 /m7a（运行时 bind 挂载点）")
        return 0
    return size


def _site_packages_dirs(stage: str) -> list[str]:
    root = os.path.join(stage, "opt", "venv")
    found = []
    for lib in ("lib", "lib64"):
        for python_dir in sorted(glob.glob(os.path.join(root, lib, "python*"))):
            candidate = os.path.join(python_dir, "site-packages")
            if os.path.isdir(candidate):
                found.append(candidate)
    # 少数布局直接把 site-packages 放在 lib 下
    fallback = os.path.join(root, "lib", "site-packages")
    if os.path.isdir(fallback) and fallback not in found:
        found.append(fallback)
    return found


def strip_openvino(stage: str) -> int:
    """只删除 site-packages 下以 openvino 开头的条目。

    覆盖 openvino/、openvino_telemetry/、openvino_tokenizers/、
    openvino-*.dist-info/、openvino.libs/ 等；
    numpy / onnxruntime / opencv 一律不碰。
    """
    dirs = _site_packages_dirs(stage)
    if not dirs:
        log("[strip-openvino] 警告：没找到 /opt/venv/**/site-packages，跳过")
        return 0

    freed = 0
    removed = 0
    for site_packages in dirs:
        log(f"[strip-openvino] 扫描 {os.path.relpath(site_packages, stage)}")
        for name in sorted(os.listdir(site_packages)):
            if not name.startswith("openvino"):
                continue
            path = os.path.join(site_packages, name)
            size, count = tree_size(path)
            _remove_path(path)
            freed += size
            removed += 1
            log(f"  - {name:<42} {human(size):>10}  ({count} 个文件)")
    log(f"[strip-openvino] 共删除 {removed} 项，释放 {human(freed)}")
    return freed


# ---------------------------------------------------------------- 打包

def check_stage(stage: str) -> bool:
    # /m7a 即使被 --strip 清空，也必须作为空目录保留（运行时 bind 挂载点）
    required = ["bin", "usr", "etc", "opt", "m7a", "usr/bin/bash"]
    missing = [p for p in required if not os.path.lexists(os.path.join(stage, p))]
    for path in required:
        mark = "OK " if os.path.lexists(os.path.join(stage, path)) else "!! "
        log(f"  {mark}{path}")
    if missing:
        log(f"!! stage 缺少关键路径：{', '.join(missing)}")
        return False
    return True


def pack(stage: str, out: str, modes: dict) -> int:
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    log(f"打包 {stage} -> {out}")
    tmp = out + ".tmp"

    # 先收集全部条目，目录在前、其余在后，保证内容稳定。
    # 注意 os.walk 会把「指向目录的符号链接」放进 dirs 且不遍历它，
    # /bin、/lib、/sbin 这类 usrmerge 链接会因此被漏掉——必须用 scandir 显式收集。
    entries: list[str] = []
    stack = ["."]
    while stack:
        rel = stack.pop()
        full = stage if rel == "." else os.path.join(stage, *rel.split("/"))
        with os.scandir(full) as it:
            children = sorted(it, key=lambda e: e.name)
        for e in children:
            child_rel = e.name if rel == "." else rel + "/" + e.name
            entries.append(child_rel)
            if e.is_dir(follow_symlinks=False):
                stack.append(child_rel)

    dirs_out = []
    others = []
    for rel in sorted(set(entries)):
        full = os.path.join(stage, *rel.split("/"))
        if os.path.isdir(full) and not os.path.islink(full):
            dirs_out.append(rel)
        else:
            others.append(rel)

    stats = {"dir": 0, "file": 0, "link": 0, "skip": 0,
             "exec": 0, "missing_modes": 0}
    with tarfile.open(tmp, "w:gz", compresslevel=6) as tar:
        for rel in dirs_out + others:
            full = os.path.join(stage, *rel.split("/"))
            try:
                ti = tar.gettarinfo(full, arcname=rel)
            except OSError as e:
                log(f"  warn: 跳过 {rel}（{e}）")
                stats["skip"] += 1
                continue

            recorded = modes.get(rel)
            if ti.isdir():
                ti.mode = (recorded[1] if recorded and recorded[0] == "dir"
                           else DEFAULT_DIR_MODE)
                stats["dir"] += 1
            elif ti.issym() or ti.islnk():
                ti.mode = DEFAULT_LINK_MODE
                stats["link"] += 1
            elif ti.isfile():
                if recorded and recorded[0] == "file":
                    ti.mode = recorded[1]
                else:
                    ti.mode = DEFAULT_FILE_MODE
                    stats["missing_modes"] += 1
                if ti.mode & 0o111:
                    stats["exec"] += 1
                stats["file"] += 1
            else:
                stats["skip"] += 1
                continue

            ti.uid = ti.gid = 0
            ti.uname = ti.gname = "root"
            if ti.isfile():
                with open(full, "rb") as f:
                    tar.addfile(ti, f)
            else:
                tar.addfile(ti)

    os.replace(tmp, out)
    size = os.path.getsize(out)

    # 打包后自检：关键路径必须真的进入 tar（防 usrmerge 链接再次漏掉）
    required = ["bin", "lib", "sbin", "usr/bin/bash", "opt/venv/bin/python", "m7a"]
    present = set()
    with tarfile.open(out, "r|gz") as tf:
        for member in tf:
            name = norm(member.name)
            if name in required:
                present.add(name)
            if len(present) == len(required):
                break
    missing = [p for p in required if p not in present]
    if missing:
        log(f"!! 打包结果缺少关键路径：{', '.join(missing)}")
        raise SystemExit(1)
    log(f"自检通过：{', '.join(required)}")

    log(f"输出: {out}")
    log(f"大小: {human(size)} （{size} 字节）")
    log(f"条目: {stats['dir']} 目录 / {stats['file']} 文件 / {stats['link']} 链接，"
        f"跳过 {stats['skip']}")
    log(f"权限: 带 +x 的文件 {stats['exec']} 个；"
        f"权限表未命中 {stats['missing_modes']} 个（按 {oct(DEFAULT_FILE_MODE)} 处理）")
    return size


# ---------------------------------------------------------------- 主流程

def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="展开 OCI 镜像层并打包成 m7a-rootfs.bin（支持裁剪）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--src", default=rootfs_src(),
                        help="镜像层目录（默认 M7A_ROOTFS_SRC）")
    parser.add_argument("--stage", default=rootfs_stage(),
                        help="rootfs 展开目录（默认 M7A_STAGE）")
    parser.add_argument("--out", default=rootfs_out(),
                        help="输出文件（默认 M7A_OUT）")
    parser.add_argument("--modes", default=rootfs_mode_map(),
                        help="权限表 JSON（默认 M7A_MODE_MAP）")
    parser.add_argument("--marker", default=rootfs_marker(),
                        help="展开完成标记（默认 M7A_MARKER）")
    parser.add_argument("--pack", action="store_true",
                        help="只打包，不检查/重新展开 stage")
    parser.add_argument("--strip", action="append", default=[], metavar="PATH",
                        help="从 stage 删除该路径（可重复），例如 --strip /m7a；"
                             "删 /m7a 后会保留空目录作为 bind 挂载点")
    parser.add_argument("--strip-openvino", action="store_true",
                        help="删除 venv site-packages 下的 openvino 系列包")
    parser.add_argument("--force", action="store_true",
                        help="忽略 marker，清空 stage 重新展开")
    args = parser.parse_args(argv)

    stage = os.path.abspath(args.stage)
    layers = find_layers(args.src)
    modes = load_modes(args.modes)

    # ---- 1. 解包（或复用 stage）----
    if args.pack:
        log("--pack：只重新打包")
        if not os.path.isdir(stage):
            log(f"!! stage 不存在：{stage}")
            return 1
    else:
        need_extract = (
            args.force
            or not os.path.isfile(args.marker)
            or not os.path.isdir(stage)
        )
        if need_extract:
            if not layers:
                log(f"!! 在 {args.src} 下没找到 layer*.tar.gz，请先运行 pull_image.py")
                return 1
            if os.path.exists(stage):
                log(f"清空旧 stage：{stage}")
                shutil.rmtree(stage)
            os.makedirs(stage)
            log(f"展开 {len(layers)} 层到 {stage}")
            totals = {"dir": 0, "file": 0, "symlink": 0, "hardlink": 0, "skip": 0}
            for layer in layers:
                counts = extract_layer(layer, stage)
                for key in totals:
                    totals[key] += counts[key]
            log(f"解包完成：{totals}")
            os.makedirs(os.path.dirname(args.marker) or ".", exist_ok=True)
            with open(args.marker, "w", encoding="utf-8") as f:
                f.write("\n".join(os.path.basename(p) for p in layers) + "\n")
        else:
            log(f"stage 已展开（marker 命中），跳过解包：{stage}")

    # 模式表缺失时，从 layer 元数据重扫（只读 tar，不重新解包）
    if not modes:
        if not layers:
            log(f"!! 没有权限表 {args.modes}，也没有 layer 可扫描")
            return 1
        log("权限表不存在，从镜像层重新收集")
        modes = harvest_modes(layers)
        os.makedirs(os.path.dirname(args.modes) or ".", exist_ok=True)
        save_modes(args.modes, modes)

    # ---- 2. 裁剪 ----
    stage_size_before, stage_count = tree_size(stage)
    log(f"stage 当前 {human(stage_size_before)} / {stage_count} 个文件")

    for spec in args.strip:
        before = tree_size(stage)[0]
        strip_path(stage, spec)
        after = tree_size(stage)[0]
        log(f"  stage {human(before)} -> {human(after)}")

    if args.strip_openvino:
        before = tree_size(stage)[0]
        strip_openvino(stage)
        after = tree_size(stage)[0]
        log(f"  stage {human(before)} -> {human(after)}")

    stage_size_after, _ = tree_size(stage)
    if stage_size_before != stage_size_after:
        log(f"裁剪合计：{human(stage_size_before)} -> {human(stage_size_after)} "
            f"(释放 {human(stage_size_before - stage_size_after)})")

    # ---- 3. 打包 ----
    if not check_stage(stage):
        return 1
    size = pack(stage, os.path.abspath(args.out), modes)
    log(f"完成：{size} 字节")
    return 0


if __name__ == "__main__":
    sys.exit(main())
