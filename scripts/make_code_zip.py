#!/usr/bin/env python3
"""把 March7thAssistant 源码打成 APK 内置的 m7a-code.zip。

输出：
    app/src/main/assets/code/m7a-code.zip
    app/src/main/assets/code/version.json   （App 的 CodeInstaller 直接读这个）

zip 内保留源码根目录结构（main.py、pyproject.toml、uv.lock、assets/ …），
额外在根放一份 version.json：
    {"tag": "...", "commit": "...", "built_at": "..."}

排除：.git、__pycache__、logs/、3rdparty/、config.yaml、*.pyc。

用法：
    # 从 GitHub 下载指定 tag（自动带 ghfast.top 镜像回退）
    python scripts/make_code_zip.py --download v2026.11.8

    # 下载最新 release
    python scripts/make_code_zip.py --download latest

    # 用本地源码目录（自动读取 git tag/commit）
    python scripts/make_code_zip.py --src /path/to/March7thAssistant

    # 本地目录但手动指定版本
    python scripts/make_code_zip.py --src . --tag v2026.11.8 --commit abc123
"""
from __future__ import annotations

import argparse
import fnmatch
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime, timezone

from _common import code_version_out, code_zip_out, human, work_dir

DEFAULT_REPO = "moesnow/March7thAssistant"
# GitHub API 不可用时的兜底版本（可以随时被 --download 的参数覆盖）
DEFAULT_CODE_TAG = "v2026.11.8"

FIXED_ZIP_TIME = (1980, 1, 1, 0, 0, 0)

# 任意层级都排除
EXCLUDE_DIR_NAMES = {".git", "__pycache__"}
# 只在源码根目录排除（3rdparty 是 submodule，logs 是运行日志）
EXCLUDE_ROOT_DIRS = {"logs", "3rdparty"}
EXCLUDE_FILE_NAMES = {"config.yaml"}
EXCLUDE_SUFFIXES = (".pyc",)

UA = "M7A-build-script/1.0"


def log(*args):
    print(*args, flush=True)


# ---------------------------------------------------------------- GitHub

def _github_json(url: str):
    headers = {"User-Agent": UA, "Accept": "application/vnd.github+json"}
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def resolve_latest_tag(repo: str) -> str:
    try:
        release = _github_json(f"https://api.github.com/repos/{repo}/releases/latest")
        if release.get("tag_name"):
            return release["tag_name"]
    except Exception as e:  # noqa: BLE001
        log(f"  release/latest 查询失败：{type(e).__name__}: {e}")
    try:
        tags = _github_json(f"https://api.github.com/repos/{repo}/tags?per_page=1")
        if tags:
            return tags[0]["name"]
    except Exception as e:  # noqa: BLE001
        log(f"  tags 查询失败：{type(e).__name__}: {e}")
    log(f"!! 无法查询最新 tag，退回内置版本 {DEFAULT_CODE_TAG}")
    return DEFAULT_CODE_TAG


def fetch_commit(repo: str, tag: str) -> str:
    try:
        data = _github_json(
            f"https://api.github.com/repos/{repo}/commits/"
            f"{urllib.parse.quote(tag, safe='')}"
        )
        return data.get("sha") or "unknown"
    except Exception as e:  # noqa: BLE001
        log(f"  查询 commit 失败：{type(e).__name__}: {e}")
        return "unknown"


# ---------------------------------------------------------------- 下载/解包

def _download_file(url: str, dest: str, attempts: int = 3,
                   timeout: int = 180) -> bool:
    os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)
    part = dest + ".part"
    for attempt in range(1, attempts + 1):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(request, timeout=timeout) as response, \
                    open(part, "wb") as f:
                shutil.copyfileobj(response, f, 1 << 20)
            if os.path.getsize(part) < 1024:
                raise RuntimeError("下载内容太小")
            os.replace(part, dest)
            return True
        except Exception as e:  # noqa: BLE001
            if os.path.exists(part):
                os.remove(part)
            fatal = isinstance(e, urllib.error.HTTPError) and e.code in (400, 401, 403, 404)
            if fatal:
                log(f"    HTTP {e.code}: {url}")
                return False
            if attempt == attempts:
                log(f"    失败：{type(e).__name__}: {e}")
                return False
            log(f"    重试 {attempt}/{attempts}：{type(e).__name__}")
            time.sleep(2)
    return False


def _archive_ok(path: str) -> bool:
    try:
        with tarfile.open(path, "r:gz") as tf:
            names = tf.getnames()
        return any(n == "main.py" or n.endswith("/main.py") for n in names)
    except (tarfile.TarError, OSError):
        return False


def _safe_name(tag: str) -> str:
    """把 tag 变成可安全用作目录/文件名的片段。"""
    safe = re.sub(r"[^A-Za-z0-9._-]+", "_", tag).strip("._")
    return safe or "source"


def download_archive(repo: str, tag: str, work: str, mirror: str,
                     force: bool = False) -> str:
    """下载 tag 的源码 tar.gz，返回本地路径。"""
    safe = _safe_name(tag)
    dest = os.path.join(work, f"{safe}.tar.gz")
    if os.path.isfile(dest) and not force and _archive_ok(dest):
        log(f"  复用已下载源码包：{dest}")
        return dest

    codeload = (f"https://codeload.github.com/{repo}/tar.gz/"
                f"refs/tags/{urllib.parse.quote(tag, safe='')}")
    archive = (f"https://github.com/{repo}/archive/refs/tags/"
               f"{urllib.parse.quote(tag, safe='')}.tar.gz")
    urls = [codeload, archive]
    if mirror:
        urls += [f"{mirror.rstrip('/')}/{codeload}",
                 f"{mirror.rstrip('/')}/{archive}"]

    for url in urls:
        log(f"  下载 {url}")
        if _download_file(url, dest) and _archive_ok(dest):
            log(f"  源码包 OK：{human(os.path.getsize(dest))}")
            return dest
        if os.path.exists(dest):
            os.remove(dest)
    raise RuntimeError(
        f"下载 {repo}@{tag} 失败。可以手动下载 tar.gz 并解压，"
        f"然后用 --src /path/to/source 指定。"
    )


def extract_archive(path: str, dest: str) -> tuple[int, int]:
    """解包 codeload tar.gz，自动去掉单一顶层目录。"""
    with tarfile.open(path, "r:gz") as tf:
        members = tf.getmembers()
        tops = {m.name.split("/", 1)[0] for m in members
                if m.name not in ("", ".")}
        strip_top = len(tops) == 1

        files = 0
        skipped = 0
        for member in members:
            parts = [p for p in member.name.replace("\\", "/").split("/")
                     if p not in ("", ".")]
            if strip_top and parts:
                parts = parts[1:]
            if not parts or any(p == ".." for p in parts):
                skipped += 1
                continue
            target = os.path.join(dest, *parts)
            if member.isdir():
                os.makedirs(target, exist_ok=True)
            elif member.issym():
                skipped += 1  # 源码包不还原符号链接，避免平台差异
            elif member.isfile() or member.islnk():
                os.makedirs(os.path.dirname(target) or dest, exist_ok=True)
                with tf.extractfile(member) as src, open(target, "wb") as out:
                    shutil.copyfileobj(src, out, 1 << 20)
                mode = member.mode & 0o7777
                if mode:
                    os.chmod(target, mode)
                files += 1
    return files, skipped


def prepare_source(repo: str, tag: str, work: str, mirror: str,
                   force: bool = False) -> str:
    safe = _safe_name(tag)
    dest = os.path.join(work, safe)
    if not force and os.path.isfile(os.path.join(dest, "main.py")):
        log(f"  复用已解压源码：{dest}")
        return dest

    archive_path = download_archive(repo, tag, work, mirror, force=force)
    tmp = dest + ".tmp"
    if os.path.exists(tmp):
        shutil.rmtree(tmp)
    os.makedirs(tmp)
    log(f"  解包到 {tmp}")
    files, skipped = extract_archive(archive_path, tmp)
    log(f"  解包 {files} 个文件，跳过 {skipped} 个链接/异常项")

    if not os.path.isfile(os.path.join(tmp, "main.py")):
        shutil.rmtree(tmp)
        raise RuntimeError(
            "源码包里没有 main.py，可能不是 March7thAssistant 仓库"
        )
    if os.path.exists(dest):
        shutil.rmtree(dest)
    os.replace(tmp, dest)
    return dest


# ---------------------------------------------------------------- 版本信息

def _git(src: str, *args: str):
    try:
        result = subprocess.run(
            ["git", "-C", src, *args],
            capture_output=True, text=True, timeout=20,
        )
        if result.returncode == 0 and result.stdout.strip():
            return result.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        pass
    return None


def built_at() -> str:
    epoch = os.environ.get("SOURCE_DATE_EPOCH")
    if epoch and epoch.isdigit():
        moment = datetime.fromtimestamp(int(epoch), timezone.utc)
    else:
        moment = datetime.now(timezone.utc)
    return moment.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def read_existing_version(src: str) -> dict:
    path = os.path.join(src, "version.json")
    if not os.path.isfile(path):
        return {}
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def version_meta(src: str, tag: str | None, commit: str | None) -> dict:
    existing = read_existing_version(src)
    final_tag = (
        tag
        or _git(src, "describe", "--tags", "--abbrev=0")
        or existing.get("tag")
        or "local"
    )
    final_commit = (
        commit
        or _git(src, "rev-parse", "HEAD")
        or existing.get("commit")
        or "unknown"
    )
    return {"tag": final_tag, "commit": final_commit, "built_at": built_at()}


# ---------------------------------------------------------------- 收集/打包

def _excluded(rel_parts: list[str], name: str, extra: list[str]) -> bool:
    if name in EXCLUDE_DIR_NAMES:
        return True
    if not rel_parts and name in EXCLUDE_ROOT_DIRS:
        return True
    if name in EXCLUDE_FILE_NAMES:
        return True
    if name.endswith(EXCLUDE_SUFFIXES):
        return True
    # 根目录的 version.json 由脚本重新生成（本地已安装过的源码会带一个）
    if not rel_parts and name == "version.json":
        return True
    rel = "/".join(rel_parts + [name])
    for pattern in extra:
        if "/" in pattern:
            if fnmatch.fnmatch(rel, pattern):
                return True
        elif fnmatch.fnmatch(name, pattern):
            return True
    return False


def collect(src: str, extra: list[str]):
    """返回 (目录相对路径列表, 文件相对路径列表, 跳过的链接数)。"""
    dirs: list[str] = []
    files: list[str] = []
    links = 0
    for root, dir_names, file_names in os.walk(src):
        rel_root = os.path.relpath(root, src)
        base = [] if rel_root == "." else rel_root.split(os.sep)

        kept = []
        for name in sorted(dir_names):
            full = os.path.join(root, name)
            if os.path.islink(full):
                links += 1
                continue
            if _excluded(base, name, extra):
                continue
            kept.append(name)
            dirs.append("/".join(base + [name]))
        dir_names[:] = kept

        for name in sorted(file_names):
            full = os.path.join(root, name)
            if os.path.islink(full):
                links += 1
                continue
            if _excluded(base, name, extra):
                continue
            files.append("/".join(base + [name]))
    return sorted(dirs), sorted(files), links


def _zip_info(rel: str, is_dir: bool, mode: int) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(rel, date_time=FIXED_ZIP_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3
    # 只保留权限位，去掉 setuid/setgid/sticky，避免解包时出现意外
    mode = mode & 0o777
    if is_dir:
        mode = mode or 0o755
        info.external_attr = (0o040000 | mode) << 16
        info.external_attr |= 0x10  # MS-DOS directory flag
    else:
        mode = mode or 0o644
        info.external_attr = (0o100000 | mode) << 16
    return info


def build_zip(src: str, out: str, meta: dict, extra: list[str]) -> dict:
    dirs, files, links = collect(src, extra)
    if "main.py" not in files:
        raise RuntimeError(f"源码根目录没有 main.py：{src}")

    version_bytes = (json.dumps(meta, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    source_bytes = 0

    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    tmp = out + ".tmp"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED,
                         compresslevel=9, allowZip64=True) as z:
        info = zipfile.ZipInfo("version.json", date_time=FIXED_ZIP_TIME)
        info.compress_type = zipfile.ZIP_DEFLATED
        info.create_system = 3
        info.external_attr = (0o100644 & 0xFFFF) << 16
        z.writestr(info, version_bytes)

        for rel in dirs:
            full = os.path.join(src, *rel.split("/"))
            try:
                mode = os.stat(full).st_mode & 0o7777
            except OSError:
                mode = 0o755
            z.writestr(_zip_info(rel + "/", True, mode), b"")

        for rel in files:
            full = os.path.join(src, *rel.split("/"))
            stat = os.stat(full)
            source_bytes += stat.st_size
            zinfo = _zip_info(rel, False, stat.st_mode & 0o7777)
            with open(full, "rb") as f, z.open(zinfo, "w") as dst:
                shutil.copyfileobj(f, dst, 1 << 20)

    os.replace(tmp, out)
    return {
        "files": len(files),
        "dirs": len(dirs),
        "links": links,
        "source_bytes": source_bytes,
    }


def verify_zip(path: str) -> None:
    with zipfile.ZipFile(path) as z:
        names = set(z.namelist())
        for required in ("version.json", "main.py", "pyproject.toml"):
            if required not in names:
                raise RuntimeError(f"zip 里缺少 {required}")
        with z.open("version.json") as f:
            meta = json.load(f)
        for key in ("tag", "commit", "built_at"):
            if key not in meta:
                raise RuntimeError(f"version.json 缺少 {key}")


def write_version_file(path: str, meta: dict) -> None:
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
        f.write("\n")
    os.replace(tmp, path)


# ---------------------------------------------------------------- 主流程

def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="打包 March7thAssistant 源码为 assets/code/m7a-code.zip",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--src", metavar="DIR", help="本地源码目录")
    group.add_argument("--download", metavar="TAG",
                       help="从 GitHub 下载 tag（latest 表示最新 release）")
    parser.add_argument("--repo", default=os.environ.get("M7A_CODE_REPO", DEFAULT_REPO),
                        help=f"GitHub 仓库（默认 {DEFAULT_REPO}）")
    parser.add_argument("--mirror",
                        default=os.environ.get("M7A_GH_MIRROR", "https://ghfast.top"),
                        help="下载镜像前缀（默认 ghfast.top，设空字符串可关闭）")
    parser.add_argument("--tag", default=None,
                        help="覆盖 version.json 的 tag（--src 模式常用）")
    parser.add_argument("--commit", default=None,
                        help="覆盖 version.json 的 commit")
    parser.add_argument("--out", default=code_zip_out(),
                        help="输出 zip（默认 M7A_CODE_OUT）")
    parser.add_argument("--version-out", default=code_version_out(),
                        help="与 zip 并列的 version.json（默认 M7A_CODE_VERSION_OUT）")
    parser.add_argument("--work", default=os.path.join(work_dir(), "code-src"),
                        help="下载/解包工作目录")
    parser.add_argument("--exclude", action="append", default=[], metavar="PATTERN",
                        help="额外排除的 glob（可重复，按相对路径匹配）")
    parser.add_argument("--force", action="store_true",
                        help="忽略已下载/已解压的缓存")
    args = parser.parse_args(argv)

    work = os.path.abspath(args.work)
    os.makedirs(work, exist_ok=True)

    try:
        if args.download:
            tag = args.download
            if tag == "latest":
                tag = resolve_latest_tag(args.repo)
                log(f"最新 tag：{tag}")
            if args.tag:
                log("提示：--download 模式忽略 --tag，版本号以实际下载的 tag 为准")
            log(f"准备源码：{args.repo}@{tag}")
            src = prepare_source(args.repo, tag, work, args.mirror, force=args.force)
            commit = args.commit or fetch_commit(args.repo, tag)
            meta = {"tag": tag, "commit": commit, "built_at": built_at()}
        else:
            src = os.path.abspath(os.path.expanduser(args.src))
            if not os.path.isdir(src):
                log(f"!! 源码目录不存在：{src}")
                return 2
            if not os.path.isfile(os.path.join(src, "main.py")):
                log(f"!! {src} 里没有 main.py，不是 March7thAssistant 源码目录")
                return 2
            meta = version_meta(src, args.tag, args.commit)
            if meta["tag"] == "local":
                log("警告：没能从 git 读到 tag，version.json 里将写 \"local\"")
    except Exception as e:  # noqa: BLE001 - 下载/解包错误统一报错
        log(f"!! 准备源码失败：{e}")
        return 1

    log(f"源码: {src}")
    log(f"版本: tag={meta['tag']} commit={meta['commit']} built_at={meta['built_at']}")

    try:
        stats = build_zip(src, os.path.abspath(args.out), meta, args.exclude)
        verify_zip(os.path.abspath(args.out))
        write_version_file(os.path.abspath(args.version_out), meta)
    except Exception as e:  # noqa: BLE001
        log(f"!! 打包失败：{e}")
        return 1

    size = os.path.getsize(os.path.abspath(args.out))
    log(f"文件: {stats['files']} 个，目录 {stats['dirs']} 个，"
        f"跳过符号链接 {stats['links']} 个")
    log(f"原始: {human(stats['source_bytes'])}")
    log(f"输出: {os.path.abspath(args.out)}")
    log(f"大小: {human(size)} （{size} 字节）")
    log(f"版本文件: {os.path.abspath(args.version_out)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
