#!/usr/bin/env python3
"""构建离线 Termux bootstrap 资产（无人值守版）。

流程：
  1. 从 Termux APK 的 lib/arm64-v8a/libtermux-bootstrap.so 里抽出原始 bootstrap zip；
  2. 解析 proot-distro 在 Termux 仓库里的完整依赖闭包；
  3. 下载 .deb，解开 ar 包里的 data.tar.*，去掉
     data/data/com.termux/files/usr 前缀后并入 stage；
  4. 重新生成 SYMLINKS.txt 并打成
     app/src/main/assets/bootstrap/termux-bootstrap.zip。

bootstrap 来源三选一（优先级从高到低）：
  --termux-apk PATH / TERMUX_APK      本地 Termux APK
  --download-apk                       自动从 GitHub release 下载 arm64 APK
  --bootstrap-src PATH / M7A_BOOTSTRAP_SRC
                                       已经抽好的 bootstrap-aarch64.zip

用法：
    TERMUX_APK=/path/termux.apk python scripts/build_bootstrap.py
    python scripts/build_bootstrap.py --download-apk
    python scripts/build_bootstrap.py --apk-tag v0.118.3 --download-apk
"""
from __future__ import annotations

import argparse
import bz2
import gzip
import hashlib
import io
import json
import lzma
import os
import shutil
import struct
import sys
import tarfile
import time
import urllib.error
import urllib.request
import zipfile

from _common import (
    bool_env,
    bootstrap_out,
    bootstrap_src,
    bootstrap_stage,
    cache_dir,
    human,
    pkg_list,
)

DEFAULT_MIRROR = "https://packages-cf.termux.dev/apt/termux-main"
DEFAULT_ARCH = "aarch64"
APK_REPO = "termux/termux-app"

# Termux 最新稳定版（2026-11）。没有 TERMUX_APK 且 --download-apk 时先走
# GitHub API 找最新 release；API 不可用才退回这个固定 URL。
DEFAULT_APK_URL = (
    "https://github.com/termux/termux-app/releases/download/v0.118.3/"
    "termux-app_v0.118.3+github-debug_arm64-v8a.apk"
)

UA = "M7A-build-script/1.0"

ABS_PREFIXES = (
    "data/data/com.termux/files/usr/",
    "data/data/com.termux/files/",
    "/data/data/com.termux/files/usr/",
    "/data/data/com.termux/files/",
    "./",
    "/",
)


def log(*args):
    print(*args, flush=True)


# ---------------------------------------------------------------- 网络

def download(url: str, dest: str, attempts: int = 10, timeout: int = 120) -> str:
    """下载到 dest；失败重试，成功用 os.replace 落盘。"""
    os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)
    part = dest + ".part"
    for attempt in range(1, attempts + 1):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(request, timeout=timeout) as response, \
                    open(part, "wb") as f:
                shutil.copyfileobj(response, f, 1 << 20)
            if os.path.getsize(part) == 0:
                raise RuntimeError("下载内容为空")
            os.replace(part, dest)
            return dest
        except Exception as e:  # noqa: BLE001 - 网络异常种类多，统一重试
            if os.path.exists(part):
                os.remove(part)
            fatal = isinstance(e, urllib.error.HTTPError) and e.code in (400, 401, 403, 404)
            if fatal or attempt == attempts:
                raise
            if attempt == 1 or attempt % 3 == 0:
                log(f"    下载失败（{attempt}/{attempts}）：{type(e).__name__}: {e}")
            time.sleep(2)
    raise RuntimeError(f"下载失败：{url}")


def _github_json(url: str):
    headers = {"User-Agent": UA, "Accept": "application/vnd.github+json"}
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def resolve_apk_url(tag: str | None = None) -> str:
    """查 GitHub release 里名字带 arm64 的 APK；失败则用固定 URL。"""
    try:
        if tag:
            url = f"https://api.github.com/repos/{APK_REPO}/releases/tags/{tag}"
        else:
            url = f"https://api.github.com/repos/{APK_REPO}/releases/latest"
        release = _github_json(url)
        assets = [a for a in release.get("assets", [])
                  if a.get("name", "").endswith(".apk")
                  and "arm64" in a["name"].lower()]
        if assets:
            # 优先体积小的 split APK（universal 不会被 arm64 过滤命中）
            assets.sort(key=lambda a: a.get("size") or 0)
            name = assets[0]["browser_download_url"]
            log(f"  release {release.get('tag_name')} -> {assets[0]['name']}")
            return name
        log("  release 里没有 arm64 APK，改用固定 URL")
    except Exception as e:  # noqa: BLE001 - 查不到就用兜底
        log(f"  查询 Termux release 失败：{type(e).__name__}: {e}")
    return DEFAULT_APK_URL


def download_apk(url: str, dest: str, force: bool = False) -> str:
    if os.path.isfile(dest) and not force and zipfile.is_zipfile(dest):
        log(f"  复用已下载的 APK：{dest}")
        return dest

    mirror = os.environ.get("M7A_GH_MIRROR", "https://ghfast.top").rstrip("/")
    candidates = [url]
    if mirror and not url.startswith(mirror):
        candidates.append(f"{mirror}/{url}")

    for candidate in candidates:
        log(f"  下载 APK：{candidate}")
        try:
            download(candidate, dest, attempts=3, timeout=300)
            if zipfile.is_zipfile(dest):
                log(f"  APK OK：{human(os.path.getsize(dest))}")
                return dest
            log("  下载内容不是 zip，删掉重试")
        except Exception as e:  # noqa: BLE001
            log(f"  失败：{type(e).__name__}: {e}")
        if os.path.exists(dest):
            os.remove(dest)

    raise RuntimeError(
        "Termux APK 下载失败。可以手动下载 arm64 APK，然后用 "
        "TERMUX_APK=/path/to/termux.apk 重新运行。"
    )


# ---------------------------------------------------------------- APK 抽取

def _bootstrap_zip_ok(blob: bytes) -> bool:
    try:
        with zipfile.ZipFile(io.BytesIO(blob)) as z:
            names = z.namelist()
            return "SYMLINKS.txt" in names and any(n.startswith("bin/") for n in names)
    except (zipfile.BadZipFile, OSError):
        return False


def find_zip_blob(data: bytes):
    """在 libtermux-bootstrap.so 里定位内嵌 zip。

    APK 里的 .so 是「SoLoader 包装 + 原始 zip 字节」，zip 不一定是文件开头，
    因此从 EOCD 反推中央目录起点：
        start = eocd_offset - cd_size - cd_offset
    """
    pos = data.rfind(b"PK\x05\x06")
    while pos >= 0:
        if pos + 22 <= len(data):
            try:
                cd_size = struct.unpack_from("<I", data, pos + 12)[0]
                cd_offset = struct.unpack_from("<I", data, pos + 16)[0]
                comment_len = struct.unpack_from("<H", data, pos + 20)[0]
            except struct.error:
                cd_size = -1
            if cd_size >= 0:
                end = pos + 22 + comment_len
                start = pos - cd_size - cd_offset
                if (start >= 0 and end <= len(data)
                        and data[start:start + 4] == b"PK\x03\x04"):
                    blob = data[start:end]
                    if _bootstrap_zip_ok(blob):
                        return blob, start
        pos = data.rfind(b"PK\x05\x06", 0, pos)
    return None, -1


def extract_from_apk(apk: str, dest: str, force: bool = False) -> str:
    """从 APK 抽 bootstrap zip 到 dest。"""
    if os.path.isfile(dest) and not force and zipfile.is_zipfile(dest):
        log(f"  复用已抽取的 bootstrap：{dest}")
        return dest

    if not os.path.isfile(apk):
        raise RuntimeError(f"Termux APK 不存在：{apk}")

    with zipfile.ZipFile(apk) as z:
        names = [n for n in z.namelist() if n.endswith("libtermux-bootstrap.so")]
        if not names:
            raise RuntimeError(
                "APK 里没有 libtermux-bootstrap.so，请确认这是 Termux 官方 APK"
            )
        names.sort(key=lambda n: (0 if "arm64-v8a" in n else 1, n))
        member = names[0]
        log(f"  从 {member} 抽取 bootstrap")
        data = z.read(member)

    blob, offset = find_zip_blob(data)
    if blob is None:
        raise RuntimeError(
            "在 libtermux-bootstrap.so 里没找到内嵌的 bootstrap zip；"
            "可能是新版 Termux 改了打包方式，请用 --bootstrap-src 直接给 zip"
        )

    os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)
    tmp = dest + ".tmp"
    with open(tmp, "wb") as f:
        f.write(blob)
    os.replace(tmp, dest)
    log(f"  bootstrap 源：{human(len(blob))}（zip 偏移 {offset}）")
    return dest


# ---------------------------------------------------------------- .deb 处理

def read_ar(path: str):
    """读 ar 归档，yield (成员名, 数据)。"""
    with open(path, "rb") as f:
        magic = f.read(8)
        if magic != b"!<arch>\n":
            raise ValueError(f"不是 ar 归档：{path}")
        while True:
            header = f.read(60)
            if len(header) < 60:
                return
            name = header[:16].decode("utf-8", "replace").strip()
            if name.endswith("/"):
                name = name[:-1]
            size_text = header[48:58].decode("ascii", "replace").strip()
            if not size_text:
                return
            size = int(size_text)
            data = f.read(size)
            if size % 2:
                f.read(1)
            yield name, data


def normalize_guest_path(name: str) -> str:
    """去掉 Termux 的 data/data/com.termux/files/usr 等前缀。"""
    name = name.replace("\\", "/")
    changed = True
    while changed:
        changed = False
        for prefix in ABS_PREFIXES:
            if name.startswith(prefix):
                name = name[len(prefix):]
                changed = True
                break
    return name


def _decompress(data: bytes) -> bytes:
    if data[:2] == b"\x1f\x8b":
        return gzip.decompress(data)
    if data[:6] == b"\xfd7zXZ\x00":
        return lzma.decompress(data)
    if data[:4] == b"\x28\xb5\x2f\xfd":
        try:
            import zstandard  # 可选依赖，Ubuntu runner 上需要 pip install zstandard
        except ImportError as e:
            raise RuntimeError(
                "这个 .deb 的 data.tar 是 zstd，需要先 pip install zstandard"
            ) from e
        return zstandard.ZstdDecompressor().decompress(
            data, max_output_size=1 << 30
        )
    if data[:3] == b"BZh":
        return bz2.decompress(data)
    # 没压缩的 tar 也可能出现
    if len(data) > 262 and data[257:262] == b"ustar":
        return data
    raise ValueError(f"不认识的 data.tar 压缩格式（magic={data[:4]!r}）")


def extract_data_tar(data: bytes, stage: str) -> dict:
    """把 data.tar.* 的内容展开到 stage。"""
    raw = _decompress(data)
    counts = {"dir": 0, "file": 0, "symlink": 0, "hardlink": 0, "skip": 0}

    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as tf:
        for member in tf:
            rel = normalize_guest_path(member.name)
            if not rel or rel == ".":
                continue
            parts = [p for p in rel.split("/") if p not in ("", ".")]
            if any(p == ".." for p in parts):
                counts["skip"] += 1
                continue
            rel = "/".join(parts)

            # dpkg 数据库对运行没用，还会带一堆绝对路径
            if rel.startswith("var/lib/dpkg/") and not member.isdir():
                counts["skip"] += 1
                continue

            dst = os.path.join(stage, *parts)
            parent = os.path.dirname(dst)
            if parent:
                os.makedirs(parent, exist_ok=True)

            if member.isdir():
                if os.path.lexists(dst) and not os.path.isdir(dst):
                    _remove(dst)
                os.makedirs(dst, exist_ok=True)
                counts["dir"] += 1
            elif member.issym():
                target = member.linkname
                if target.startswith("/data/data/com.termux/files/"):
                    target = "/" + target[len("/data/data/com.termux/files/"):]
                _remove(dst)
                os.symlink(target, dst)
                counts["symlink"] += 1
            elif member.islnk():
                source_rel = normalize_guest_path(member.linkname)
                source = os.path.join(stage, *[p for p in source_rel.split("/") if p])
                _remove(dst)
                try:
                    os.link(source, dst)
                except OSError:
                    shutil.copy2(source, dst)
                counts["hardlink"] += 1
            elif member.isfile():
                if os.path.isdir(dst) and not os.path.islink(dst):
                    shutil.rmtree(dst)
                with tf.extractfile(member) as src, open(dst, "wb") as out:
                    shutil.copyfileobj(src, out, 1 << 20)
                os.chmod(dst, member.mode & 0o7777)
                counts["file"] += 1
            else:
                counts["skip"] += 1
    return counts


def _remove(path: str) -> None:
    if not os.path.lexists(path):
        return
    if os.path.isdir(path) and not os.path.islink(path):
        shutil.rmtree(path)
    else:
        os.remove(path)


def _sha256_file(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


# ---------------------------------------------------------------- bootstrap 组装

def load_pkg_list(path: str, mirror: str, arch: str, cache: str,
                  refresh: bool = False) -> list[str]:
    lines: list[str] = []
    if os.path.isfile(path) and not refresh:
        with open(path, encoding="utf-8") as f:
            lines = [line.strip() for line in f
                     if line.strip() and not line.startswith("#")]
        if lines:
            return lines

    log("包清单不存在，自动解析 Termux 依赖闭包（首次会下载 Packages.bz2）")
    try:
        import resolve_termux_deps as resolver
    except ImportError as e:
        raise RuntimeError(
            f"无法导入 resolve_termux_deps.py（{e}），请确认 scripts/ 完整"
        ) from e
    lines = resolver.resolve(mirror=mirror, arch=arch, cache=cache,
                             refresh=refresh, log_fn=log)
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    log(f"已写入 {path}（{len(lines)} 个包）")
    return lines


def apply_symlinks(stage: str, symlinks_file: str) -> int:
    """按 SYMLINKS.txt 重建符号链接（格式：<target>←<path>）。"""
    if not os.path.isfile(symlinks_file):
        log(f"  警告：没有 {symlinks_file}")
        return 0
    made = 0
    with open(symlinks_file, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or "←" not in line:
                continue
            target, path = line.split("←", 1)
            parts = [p for p in path.split("/") if p not in ("", ".")]
            if not parts or any(p == ".." for p in parts):
                continue
            dst = os.path.join(stage, *parts)
            os.makedirs(os.path.dirname(dst) or stage, exist_ok=True)
            if os.path.lexists(dst):
                _remove(dst)
            os.symlink(target, dst)
            made += 1
    return made


def unpack_bootstrap(src: str, stage: str) -> int:
    """把源 bootstrap zip 展开到 stage，并恢复文件权限。"""
    if os.path.exists(stage):
        shutil.rmtree(stage)
    os.makedirs(stage)

    files = 0
    with zipfile.ZipFile(src) as z:
        for info in z.infolist():
            rel = info.filename.replace("\\", "/")
            parts = [p for p in rel.split("/") if p not in ("", ".")]
            if not parts or any(p == ".." for p in parts):
                continue
            if info.is_dir():
                os.makedirs(os.path.join(stage, *parts), exist_ok=True)
                continue
            dst = os.path.join(stage, *parts)
            os.makedirs(os.path.dirname(dst) or stage, exist_ok=True)
            with z.open(info) as src_file, open(dst, "wb") as out:
                shutil.copyfileobj(src_file, out, 1 << 20)
            mode = (info.external_attr >> 16) & 0xFFFF
            if mode:
                os.chmod(dst, mode & 0o7777)
            files += 1
    return files


def install_packages(stage: str, lines: list[str], mirror: str, cache: str,
                     attempts: int = 10) -> dict:
    """按包清单下载并展开所有 .deb。"""
    debs_dir = os.path.join(cache, "debs")
    os.makedirs(debs_dir, exist_ok=True)
    totals = {"dir": 0, "file": 0, "symlink": 0, "hardlink": 0, "skip": 0}
    count = 0

    for line in lines:
        parts = line.split("\t")
        if len(parts) < 5:
            log(f"  警告：包清单行格式不对，跳过：{line!r}")
            continue
        name, version, filename, size_text, sha = parts[:5]
        filename = filename[2:] if filename.startswith("./") else filename
        deb = os.path.join(debs_dir, os.path.basename(filename))
        expected_size = int(size_text) if size_text.isdigit() else 0

        # 已有缓存就校验大小/sha，通过则复用
        reuse = False
        if os.path.isfile(deb) and (not expected_size
                                    or os.path.getsize(deb) == expected_size):
            if not sha or _sha256_file(deb) == sha:
                reuse = True
        if not reuse:
            url = f"{mirror.rstrip('/')}/{filename}"
            count += 1
            log(f"  [{count}] {name} {version}  {human(expected_size)}")
            download(url, deb, attempts=attempts)
        if sha:
            actual = _sha256_file(deb)
            if actual != sha:
                raise RuntimeError(
                    f"校验失败：{name}\n  期望 {sha}\n  实际 {actual}\n"
                    f"  （镜像内容可能已更新，删掉 {deb} 后重试）"
                )

        found = False
        for member, data in read_ar(deb):
            if member.startswith("data.tar"):
                counts = extract_data_tar(data, stage)
                for key in totals:
                    totals[key] += counts[key]
                found = True
                break
        if not found:
            raise RuntimeError(f"{deb} 里没有 data.tar.*")

    log(f"  安装文件：{totals['file']} 个，符号链接 {totals['symlink']} 个")
    return totals


def ensure_python_links(stage: str) -> None:
    """确保 python3.14 / python3 / python 三个名字至少有一个能用。"""
    bin_dir = os.path.join(stage, "bin")
    if not os.path.isdir(bin_dir):
        return
    existing = os.listdir(bin_dir)
    python_bins = [n for n in existing if n.startswith("python3.")]
    if not python_bins:
        return
    python_bins.sort(reverse=True)
    target = python_bins[0]
    for link in ("python3", "python"):
        path = os.path.join(bin_dir, link)
        if not os.path.lexists(path):
            os.symlink(target, path)
            log(f"  补链接 bin/{link} -> {target}")


def collect_symlinks(stage: str) -> list[tuple[str, str]]:
    links = []
    for root, dirs, files in os.walk(stage):
        dirs.sort()
        for name in dirs:
            full = os.path.join(root, name)
            if os.path.islink(full):
                rel = os.path.relpath(full, stage).replace(os.sep, "/")
                links.append((rel, os.readlink(full)))
        for name in sorted(files):
            full = os.path.join(root, name)
            if os.path.islink(full):
                rel = os.path.relpath(full, stage).replace(os.sep, "/")
                links.append((rel, os.readlink(full)))
    links.sort()
    return links


def fixed_zip_time() -> tuple:
    """固定时间戳，保证重复构建 zip 字节一致。"""
    epoch = os.environ.get("SOURCE_DATE_EPOCH")
    if epoch and epoch.isdigit():
        t = time.gmtime(int(epoch))
        return (max(t.tm_year, 1980), t.tm_mon, t.tm_mday,
                t.tm_hour, t.tm_min, t.tm_sec)
    return (1980, 1, 1, 0, 0, 0)


def pack_bootstrap(stage: str, out: str) -> int:
    """把 stage 重新打 zip；符号链接写进 SYMLINKS.txt。"""
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    tmp = out + ".tmp"
    links = collect_symlinks(stage)
    symlinks_text = "".join(f"{target}←{rel}\n" for rel, target in links)
    stamp = fixed_zip_time()

    files = 0
    dirs = 0
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED,
                         compresslevel=9) as z:
        info = zipfile.ZipInfo("SYMLINKS.txt", date_time=stamp)
        info.compress_type = zipfile.ZIP_DEFLATED
        info.create_system = 3
        info.external_attr = (0o100644 & 0xFFFF) << 16
        z.writestr(info, symlinks_text.encode("utf-8"))

        for root, subdirs, names in os.walk(stage):
            subdirs.sort()
            for name in sorted(names):
                full = os.path.join(root, name)
                if os.path.islink(full):
                    continue
                rel = os.path.relpath(full, stage).replace(os.sep, "/")
                if rel == "SYMLINKS.txt":
                    continue
                stat = os.stat(full)
                info = zipfile.ZipInfo(rel, date_time=stamp)
                info.compress_type = zipfile.ZIP_DEFLATED
                info.create_system = 3
                info.external_attr = (stat.st_mode & 0xFFFF) << 16
                with open(full, "rb") as f, z.open(info, "w") as dst:
                    shutil.copyfileobj(f, dst, 1 << 20)
                files += 1
            # zip 不保存空目录也无所谓，这里只统计
            dirs += len(subdirs)

    os.replace(tmp, out)
    size = os.path.getsize(out)
    log(f"输出: {out}")
    log(f"大小: {human(size)} （{size} 字节）")
    log(f"内容: {files} 个文件 / {len(links)} 个符号链接 / {dirs} 个目录")
    return size


# ---------------------------------------------------------------- 主流程

def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="生成离线 Termux bootstrap（从 APK 抽取 + 合并 Termux 依赖）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--termux-apk", default=os.environ.get("TERMUX_APK"),
                        help="本地 Termux APK 路径（也可用 TERMUX_APK 环境变量）")
    parser.add_argument("--apk-url", default=os.environ.get("TERMUX_APK_URL"),
                        help="直接指定 APK 下载地址")
    parser.add_argument("--apk-tag", default=os.environ.get("TERMUX_APK_TAG"),
                        help="Termux release tag，例如 v0.118.3")
    parser.add_argument("--download-apk", action="store_true",
                        default=bool_env("M7A_DOWNLOAD_APK"),
                        help="从 GitHub release 自动下载 arm64 APK")
    parser.add_argument("--bootstrap-src", default=bootstrap_src(),
                        help="bootstrap 源 zip（默认 M7A_BOOTSTRAP_SRC）")
    parser.add_argument("--stage", default=bootstrap_stage(),
                        help="解包/合并目录（默认 M7A_BOOTSTRAP_STAGE）")
    parser.add_argument("--out", default=bootstrap_out(),
                        help="输出 zip（默认 M7A_BOOTSTRAP_OUT）")
    parser.add_argument("--cache", default=cache_dir(),
                        help="下载缓存目录（默认 M7A_CACHE）")
    parser.add_argument("--pkg-list", default=pkg_list(),
                        help="包清单（默认 M7A_PKG_LIST）")
    parser.add_argument("--mirror", default=os.environ.get("M7A_TERMUX_MIRROR", DEFAULT_MIRROR),
                        help=f"Termux 仓库地址（默认 {DEFAULT_MIRROR}）")
    parser.add_argument("--arch", default=os.environ.get("M7A_TERMUX_ARCH", DEFAULT_ARCH),
                        help="Termux 架构（默认 aarch64）")
    parser.add_argument("--force", action="store_true",
                        help="忽略缓存，重新抽取/下载/解包")
    args = parser.parse_args(argv)

    src = os.path.abspath(args.bootstrap_src)
    stage = os.path.abspath(args.stage)
    cache = os.path.abspath(args.cache)
    os.makedirs(cache, exist_ok=True)

    # ---- 1. 拿到 bootstrap 源 zip ----
    try:
        if args.termux_apk:
            apk = os.path.abspath(os.path.expanduser(args.termux_apk))
            log(f"bootstrap 来源：APK {apk}")
            src = extract_from_apk(apk, src, force=args.force)
        elif os.path.isfile(src) and not args.force:
            log(f"bootstrap 来源：已有 zip {src}")
        elif args.download_apk:
            apk_url = args.apk_url or resolve_apk_url(args.apk_tag)
            apk = os.path.join(cache, "apk", "termux-arm64.apk")
            log(f"bootstrap 来源：下载 APK {apk_url}")
            download_apk(apk_url, apk, force=args.force)
            src = extract_from_apk(apk, src, force=args.force)
        elif os.path.isfile(src):
            log(f"bootstrap 来源：已有 zip {src}")
        else:
            log("!! 找不到 bootstrap 来源。三选一：")
            log("   1) TERMUX_APK=/path/to/termux.apk python scripts/build_bootstrap.py")
            log("   2) python scripts/build_bootstrap.py --download-apk")
            log("   3) python scripts/build_bootstrap.py --bootstrap-src /path/to/bootstrap.zip")
            return 2
    except Exception as e:  # noqa: BLE001 - 网络/格式错误统一报错
        log(f"!! 准备 bootstrap 源失败：{e}")
        return 1

    if not os.path.isfile(src) or not zipfile.is_zipfile(src):
        log(f"!! bootstrap 源不是有效 zip：{src}")
        return 1

    # ---- 2. 展开源 zip + 重建符号链接 ----
    files = unpack_bootstrap(src, stage)
    symlinks_file = os.path.join(stage, "SYMLINKS.txt")
    links = apply_symlinks(stage, symlinks_file)
    log(f"展开 bootstrap：{files} 个文件 / {links} 个符号链接")

    # ---- 3. 依赖闭包 + 下载 .deb ----
    try:
        lines = load_pkg_list(args.pkg_list, args.mirror, args.arch, cache,
                              refresh=args.force)
        install_packages(stage, lines, args.mirror, cache)
    except Exception as e:  # noqa: BLE001
        log(f"!! 安装 Termux 依赖失败：{e}")
        return 1

    ensure_python_links(stage)
    # .deb 可能覆盖/新增链接，最后再扫一遍
    remade = apply_symlinks(stage, symlinks_file)
    log(f"最终符号链接：{remade} 个")
    os.remove(symlinks_file)

    # ---- 4. 打包 ----
    try:
        pack_bootstrap(stage, os.path.abspath(args.out))
    except Exception as e:  # noqa: BLE001
        log(f"!! 打包失败：{e}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
