#!/usr/bin/env python3
"""从容器镜像仓库拉取 March7thAssistant 的 arm64 镜像层。

只取 `linux/arm64` 子清单：索引里其它架构（amd64 等）的层完全不下载。
层文件写到 `M7A_ROOTFS_SRC`（默认 `build/work/m7a-rootfs`），
供 `assemble_rootfs.py` 展开成 rootfs。

用法：
    # 国内走南京大学 ghcr 镜像（默认）
    REG_MIRROR=ghcr.nju.edu.cn python scripts/pull_image.py

    # 指定 tag / 输出目录
    python scripts/pull_image.py --tag latest --out build/work/m7a-rootfs

    # 直连 ghcr.io（脚本会自动走匿名 bearer token 流程）
    python scripts/pull_image.py --mirror ghcr.io
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from _common import human, rootfs_src

DEFAULT_MIRROR = "ghcr.nju.edu.cn"
DEFAULT_REPO = "moesnow/march7thassistant"
DEFAULT_TAG = "latest"
DEFAULT_ARCH = "arm64"

UA = "M7A-build-script/1.0"

ACCEPT_INDEX = ", ".join([
    "application/vnd.oci.image.index.v1+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.docker.distribution.manifest.v2+json",
])
ACCEPT_MANIFEST = ", ".join([
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.docker.distribution.manifest.v2+json",
])


def log(*args):
    print(*args, flush=True)


def _sha256_file(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(1 << 20)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def _parse_challenge(value: str) -> dict:
    """解析 `Bearer realm="...",service="...",scope="..."`。"""
    if not value or not value.lower().startswith("bearer "):
        return {}
    return {m.group(1).lower(): m.group(2)
            for m in re.finditer(r'(\w+)="([^"]*)"', value[7:])}


class Registry:
    """极简 registry 客户端，按需处理匿名 bearer token。"""

    def __init__(self, mirror: str, repo: str):
        base = mirror if "://" in mirror else "https://" + mirror
        self.base = base.rstrip("/")
        self.repo = repo
        self.token = None

    # -- 请求 ----------------------------------------------------------
    def _headers(self, accept=None) -> dict:
        headers = {"User-Agent": UA}
        if accept:
            headers["Accept"] = accept
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        return headers

    def _open(self, url: str, accept=None, timeout=60):
        request = urllib.request.Request(url, headers=self._headers(accept))
        last = None
        for attempt in range(4):
            try:
                return urllib.request.urlopen(request, timeout=timeout)
            except urllib.error.HTTPError as e:
                if e.code >= 500:
                    last = e
                    if attempt < 3:
                        log(f"  镜像站 {e.code}，{attempt + 1}/3 后重试")
                        time.sleep(5 * (attempt + 1))
                    continue
                if e.code != 401:
                    raise
                self._login(e.headers.get("WWW-Authenticate", ""))
                request = urllib.request.Request(url, headers=self._headers(accept))
                continue
            except (urllib.error.URLError, TimeoutError, OSError) as e:
                # 镜像站偶发超时/断连，重试几次再失败
                last = e
                if attempt < 3:
                    log(f"  网络异常（{e}），{attempt + 1}/3 后重试")
                    time.sleep(3 * (attempt + 1))
        raise last

    def _login(self, challenge: str):
        params = _parse_challenge(challenge)
        realm = params.get("realm")
        if not realm:
            raise RuntimeError(
                "registry 要求认证，但没有给出 Bearer realm；"
                "可以换一个 REG_MIRROR，或设置 REG_TOKEN"
            )
        token = os.environ.get("REG_TOKEN")
        if token:
            self.token = token
            return
        query = {"scope": params.get("scope") or f"repository:{self.repo}:pull"}
        if params.get("service"):
            query["service"] = params["service"]
        url = realm + ("&" if "?" in realm else "?") + urllib.parse.urlencode(query)
        log(f"  获取匿名 token: {realm}")
        request = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(request, timeout=60) as response:
            data = json.load(response)
        self.token = data.get("token") or data.get("access_token")
        if not self.token:
            raise RuntimeError("token 服务没有返回 token")

    # -- 高层 API -------------------------------------------------------
    def get_json(self, path: str, accept: str):
        with self._open(self.base + path, accept=accept) as response:
            return json.load(response)


def download(registry: Registry, url: str, dest: str, size: int,
             sha256: str | None = None, attempts: int = 40) -> bool:
    """断点续传 + 重试；下载完校验 sha256。"""
    for attempt in range(1, attempts + 1):
        have = os.path.getsize(dest) if os.path.exists(dest) else 0
        if have >= size:
            if sha256 and _sha256_file(dest) != sha256:
                log("      sha256 不符，删掉重下")
                os.remove(dest)
                have = 0
            else:
                return True

        headers = {}
        if have:
            headers["Range"] = f"bytes={have}-"
        try:
            request = urllib.request.Request(
                url, headers={**registry._headers("application/octet-stream"), **headers}
            )
            with urllib.request.urlopen(request, timeout=120) as response:
                status = getattr(response, "status", 200)
                mode = "ab" if (have and status == 206) else "wb"
                if mode == "wb":
                    have = 0
                with open(dest, mode) as f:
                    while True:
                        block = response.read(1 << 20)
                        if not block:
                            break
                        f.write(block)
        except Exception as e:  # noqa: BLE001 - 网络错误种类很多，统一重试
            if attempt % 5 == 0:
                got = os.path.getsize(dest) if os.path.exists(dest) else 0
                log(f"      retry {attempt}: {type(e).__name__} at {human(got)}")
            time.sleep(2)
            continue

        if os.path.getsize(dest) >= size:
            if sha256 and _sha256_file(dest) != sha256:
                log("      sha256 不符，删掉重下")
                os.remove(dest)
                continue
            return True
    return os.path.exists(dest) and os.path.getsize(dest) >= size


def _write_json(path: str, data) -> None:
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2, sort_keys=True)
        f.write("\n")
    os.replace(tmp, path)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="拉取 linux/arm64 镜像层（只下 arm64，支持 REG_MIRROR）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--mirror", default=os.environ.get("REG_MIRROR", DEFAULT_MIRROR),
                        help=f"registry 镜像域名，可带 https://（默认 {DEFAULT_MIRROR}）")
    parser.add_argument("--repo", default=os.environ.get("IMG_REPO", DEFAULT_REPO),
                        help=f"镜像仓库（默认 {DEFAULT_REPO}）")
    parser.add_argument("--tag", default=os.environ.get("IMG_TAG", DEFAULT_TAG),
                        help="tag（默认 latest）")
    parser.add_argument("--arch", default=os.environ.get("IMG_ARCH", DEFAULT_ARCH),
                        help="只拉这个架构的清单（默认 arm64）")
    parser.add_argument("--out", default=os.environ.get("IMG_OUT") or rootfs_src(),
                        help="层输出目录（默认 M7A_ROOTFS_SRC）")
    parser.add_argument("--no-verify", action="store_true",
                        help="跳过 sha256 校验（不建议）")
    args = parser.parse_args(argv)

    registry = Registry(args.mirror, args.repo)
    out = os.path.abspath(os.path.expanduser(args.out))
    os.makedirs(out, exist_ok=True)

    log(f"registry = {registry.base}")
    log(f"image    = {args.repo}:{args.tag}")
    log(f"arch     = {args.arch}")
    log(f"out      = {out}")

    # ---- 1. manifest / index ----
    try:
        manifest = registry.get_json(
            f"/v2/{args.repo}/manifests/{args.tag}", ACCEPT_INDEX
        )
    except urllib.error.HTTPError as e:
        log(f"!! 拉 manifest 失败：HTTP {e.code} {e.reason}")
        log("   国内直连 ghcr.io 容易卡住，建议 REG_MIRROR=ghcr.nju.edu.cn")
        return 1

    index_digest = None
    if "manifests" in manifest:
        index_digest = manifest.get("digest") or ""
        pick = None
        available = []
        for entry in manifest["manifests"]:
            platform = entry.get("platform") or {}
            available.append(f"{platform.get('os')}/{platform.get('architecture')}")
            if (platform.get("os") == "linux"
                    and platform.get("architecture") == args.arch):
                pick = entry
        if pick is None:
            log(f"!! 索引里没有 linux/{args.arch}，只有：{', '.join(available)}")
            return 1
        child_digest = pick["digest"]
        log(f"arm64 子清单 -> {child_digest}")
        manifest = registry.get_json(
            f"/v2/{args.repo}/manifests/{child_digest}", ACCEPT_MANIFEST
        )

    config = manifest.get("config") or {}
    layers = manifest.get("layers") or []
    if not layers:
        log("!! manifest 里没有 layers，镜像格式不认识")
        return 1

    # 原样保存，便于排查“今天到底拉了哪个镜像”
    _write_json(os.path.join(out, "manifest.json"), manifest)
    _write_json(os.path.join(out, "image-info.json"), {
        "mirror": registry.base,
        "repo": args.repo,
        "tag": args.tag,
        "arch": args.arch,
        "index_digest": index_digest,
        "config_digest": config.get("digest"),
        "layers": [
            {"digest": layer.get("digest"), "size": layer.get("size"),
             "mediaType": layer.get("mediaType")}
            for layer in layers
        ],
    })

    total = sum(int(layer.get("size") or 0) for layer in layers)
    log(f"layers={len(layers)} total={human(total)}")

    # ---- 2. config ----
    if config:
        config_path = os.path.join(out, "config.json")
        config_size = int(config.get("size") or 0)
        log("  config")
        if not download(registry, f"{registry.base}/v2/{args.repo}/blobs/{config['digest']}",
                        config_path, config_size,
                        None if args.no_verify else config.get("digest", "").split(":")[-1]):
            log("!! config 下载失败")
            return 1
        try:
            with open(config_path, encoding="utf-8") as f:
                image_config = json.load(f)
            actual_arch = image_config.get("architecture")
            if actual_arch and actual_arch != args.arch:
                log(f"!! config.architecture={actual_arch}，与请求的 {args.arch} 不符")
                return 1
        except (ValueError, OSError) as e:
            log(f"  warn: 读 config.json 失败：{e}")

    # ---- 3. layers ----
    for i, layer in enumerate(layers):
        size = int(layer.get("size") or 0)
        digest = layer.get("digest") or ""
        dest = os.path.join(out, f"layer{i:02d}.tar.gz")
        if os.path.exists(dest) and os.path.getsize(dest) == size:
            log(f"  [{i:02d}] cached ({human(size)})")
            continue

        media_type = layer.get("mediaType") or ""
        if "zstd" in media_type:
            log(f"!! [{i:02d}] 是 zstd 层（{media_type}），assemble_rootfs.py 只支持 gzip")
            return 1

        log(f"  [{i:02d}] {human(size)}  {digest[:19]}")
        started = time.time()
        ok = download(
            registry,
            f"{registry.base}/v2/{args.repo}/blobs/{digest}",
            dest, size,
            None if args.no_verify else digest.split(":")[-1],
        )
        got = os.path.getsize(dest) if os.path.exists(dest) else 0
        speed = got / max(time.time() - started, 0.01) / 1048576
        log(f"  [{i:02d}] {'OK' if ok else 'INCOMPLETE'} {human(got)} "
            f"in {time.time() - started:.0f}s ({speed:.1f} MB/s)")
        if not ok:
            return 1

        # 早期发现压缩格式不对，避免把 500MB 白送给 assemble
        with open(dest, "rb") as f:
            magic = f.read(2)
        if magic != b"\x1f\x8b":
            log(f"!! [{i:02d}] 不是 gzip 数据（magic={magic!r}）")
            return 1

    log(f"ALL LAYERS OK: {len(layers)} 层，{human(total)}，"
        f"{sum(os.path.getsize(os.path.join(out, f'layer{i:02d}.tar.gz')) for i in range(len(layers)))} 字节")
    return 0


if __name__ == "__main__":
    sys.exit(main())
