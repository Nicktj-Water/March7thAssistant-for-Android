"""scripts/ 下所有构建脚本共用的路径解析与格式化工具。

路径优先级：命令行参数 > 环境变量 > 默认值。

默认值全部相对仓库/工作目录，**不再包含作者本机路径**：

    M7A_PROJECT_ROOT   仓库根目录（默认取本文件上一级）
    M7A_WORK           临时工作目录
                       默认 $GITHUB_WORKSPACE/build/work；
                       没有 GITHUB_WORKSPACE 时用 <仓库根>/build/work
    M7A_ASSETS         APK 资产目录，默认 <仓库根>/app/src/main/assets

其余变量见 README 的“环境变量”一节。需要旧版 Windows 绝对路径时，
直接把这些变量设成原来的 D:\\... 即可，脚本行为不变。
"""
from __future__ import annotations

import os


# ---------------------------------------------------------------- 路径解析

def _abs(path: str) -> str:
    """展开 ~ 与 $VAR，再转绝对路径。"""
    return os.path.abspath(os.path.expanduser(os.path.expandvars(path)))


def _env_path(name: str):
    value = os.environ.get(name)
    return _abs(value) if value else None


def project_root() -> str:
    return _env_path("M7A_PROJECT_ROOT") or os.path.dirname(
        os.path.dirname(os.path.abspath(__file__))
    )


def work_dir() -> str:
    value = _env_path("M7A_WORK")
    if value:
        return value
    # GitHub Actions 里 GITHUB_WORKSPACE 就是 checkout 根目录
    workspace = os.environ.get("GITHUB_WORKSPACE")
    base = _abs(workspace) if workspace else project_root()
    return os.path.join(base, "build", "work")


def assets_dir() -> str:
    return _env_path("M7A_ASSETS") or os.path.join(
        project_root(), "app", "src", "main", "assets"
    )


def _under(env_name: str, base_fn, *parts: str) -> str:
    value = _env_path(env_name)
    return value if value else os.path.join(base_fn(), *parts)


# --- rootfs（pull_image.py + assemble_rootfs.py）---

def rootfs_src() -> str:
    """镜像层目录（layerXX.tar.gz）。"""
    return _under("M7A_ROOTFS_SRC", work_dir, "m7a-rootfs")


def rootfs_stage() -> str:
    """OCI layer 展开后的 rootfs 目录。"""
    return _under("M7A_STAGE", work_dir, "rootfs-stage")


def rootfs_out() -> str:
    return _under("M7A_OUT", assets_dir, "rootfs", "m7a-rootfs.bin")


def rootfs_mode_map() -> str:
    return _under("M7A_MODE_MAP", work_dir, "rootfs-modes.json")


def rootfs_marker() -> str:
    return _under("M7A_MARKER", work_dir, "rootfs-extract.done")


# --- bootstrap（build_bootstrap.py + reprefix_bootstrap.py）---

def bootstrap_src() -> str:
    """从 Termux APK 里抽出的原始 bootstrap zip。"""
    return _under("M7A_BOOTSTRAP_SRC", work_dir, "bootstrap", "bootstrap-aarch64.zip")


def bootstrap_stage() -> str:
    return _under("M7A_BOOTSTRAP_STAGE", work_dir, "bootstrap-stage")


def bootstrap_out() -> str:
    return _under("M7A_BOOTSTRAP_OUT", assets_dir, "bootstrap", "termux-bootstrap.zip")


# --- 源码包（make_code_zip.py）---

def code_zip_out() -> str:
    return _under("M7A_CODE_OUT", assets_dir, "code", "m7a-code.zip")


def code_version_out() -> str:
    """APK 里与 zip 并列的 version.json（App 的 CodeInstaller 直接读它）。"""
    return _under("M7A_CODE_VERSION_OUT", assets_dir, "code", "version.json")


# --- 下载缓存 ---

def cache_dir() -> str:
    return _under("M7A_CACHE", work_dir, "cache")


def pkg_list() -> str:
    return _under("M7A_PKG_LIST", work_dir, "termux-pkgs.txt")


# ---------------------------------------------------------------- 小工具

def ensure_dir(path: str) -> str:
    os.makedirs(path, exist_ok=True)
    return path


def human(size: float) -> str:
    """把字节数格式化成方便看的大小。"""
    value = float(size)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if value < 1024 or unit == "TB":
            if unit == "B":
                return f"{int(value)} B"
            return f"{value:.1f} {unit}"
        value /= 1024.0
    return f"{value:.1f} TB"


def bool_env(name: str, default: bool = False) -> bool:
    value = os.environ.get(name)
    if value is None:
        return default
    return value.strip().lower() in ("1", "true", "yes", "on", "y")
