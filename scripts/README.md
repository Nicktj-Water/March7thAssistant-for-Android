# scripts/ —— 生成 APK 内置资产

仓库里**不包含**两个大资产，APK 构建前必须先在 CI（或本机）生成：

| 资产 | 产物 | 约体积 | 生成脚本 |
|---|---|---|---|
| Termux bootstrap | `app/src/main/assets/bootstrap/termux-bootstrap.zip` | ~40 MB | `build_bootstrap.py` |
| 容器 rootfs | `app/src/main/assets/rootfs/m7a-rootfs.bin` | ~300–500 MB | `pull_image.py` + `assemble_rootfs.py` |
| M7A 源码 | `app/src/main/assets/code/m7a-code.zip` + `version.json` | ~几 MB | `make_code_zip.py` |

所有脚本只依赖 Python 3 标准库；`zstandard` 只在 Termux 的 `.deb` 使用
zstd 压缩时才需要。Windows 和 Linux/CI 共用同一套脚本，路径全部可以用
环境变量覆盖。

---

## 0. 先设置环境（CI 里建议显式写出来）

```bash
export M7A_PROJECT_ROOT="$PWD"          # 仓库根目录（默认 scripts/ 的上一级）
export M7A_WORK="$PWD/build/work"       # 所有中间文件
export M7A_ASSETS="$PWD/app/src/main/assets"
```

不设置也可以：`_common.py` 会自动推断：

- `M7A_WORK`：有 `$GITHUB_WORKSPACE` 时用 `$GITHUB_WORKSPACE/build/work`，
  否则用 `<仓库根>/build/work`
- `M7A_ASSETS`：`<仓库根>/app/src/main/assets`

常用变量：

| 变量 | 默认值 | 用途 |
|---|---|---|
| `M7A_WORK` | `build/work` | 工作目录 |
| `M7A_ASSETS` | `app/src/main/assets` | 最终资产目录 |
| `M7A_ROOTFS_SRC` | `$M7A_WORK/m7a-rootfs` | 镜像层目录 |
| `M7A_STAGE` | `$M7A_WORK/rootfs-stage` | rootfs 展开目录 |
| `M7A_OUT` | `$M7A_ASSETS/rootfs/m7a-rootfs.bin` | rootfs 产物 |
| `M7A_MODE_MAP` | `$M7A_WORK/rootfs-modes.json` | 权限表 |
| `M7A_MARKER` | `$M7A_WORK/rootfs-extract.done` | 解包完成标记 |
| `M7A_BOOTSTRAP_SRC` | `$M7A_WORK/bootstrap/bootstrap-aarch64.zip` | 抽出的原始 bootstrap |
| `M7A_BOOTSTRAP_STAGE` | `$M7A_WORK/bootstrap-stage` | bootstrap 合并目录 |
| `M7A_BOOTSTRAP_OUT` | `$M7A_ASSETS/bootstrap/termux-bootstrap.zip` | bootstrap 产物 |
| `M7A_CODE_OUT` | `$M7A_ASSETS/code/m7a-code.zip` | 源码包产物 |
| `M7A_CODE_VERSION_OUT` | `$M7A_ASSETS/code/version.json` | 源码版本文件 |
| `M7A_CACHE` | `$M7A_WORK/cache` | 下载缓存 |
| `M7A_PKG_LIST` | `$M7A_WORK/termux-pkgs.txt` | Termux 依赖清单 |
| `M7A_GH_MIRROR` | `https://ghfast.top` | GitHub 下载镜像（空字符串关闭） |
| `M7A_TERMUX_MIRROR` | `https://packages-cf.termux.dev/apt/termux-main` | Termux 仓库 |
| `M7A_CODE_REPO` | `moesnow/March7thAssistant` | 源码仓库 |
| `TERMUX_APK` | 无 | 本地 Termux APK 路径 |
| `REG_MIRROR` | `ghcr.nju.edu.cn` | 容器 registry 镜像 |
| `GITHUB_TOKEN` | 无 | 避免 GitHub API 限流（CI 自带） |

---

## 1. bootstrap：Termux 离线包

```bash
# 方式 A：让脚本自动下载 arm64 APK（CI 推荐）
python scripts/build_bootstrap.py --download-apk

# 方式 B：已经手动下好 APK
TERMUX_APK=/path/to/termux-app_..._arm64-v8a.apk python scripts/build_bootstrap.py

# 方式 C：已经有抽好的 bootstrap-aarch64.zip
python scripts/build_bootstrap.py --bootstrap-src build/work/bootstrap/bootstrap-aarch64.zip
```

脚本会：

1. 从 APK 的 `lib/arm64-v8a/libtermux-bootstrap.so` 里定位内嵌 zip
   （扫描 EOCD，反推 central directory 起点），校验其中含 `SYMLINKS.txt` 和 `bin/`；
2. 自动下载 `Packages.bz2`，解析 `proot-distro` 的完整依赖闭包
   （已由 bootstrap 提供的包会跳过，例如 `bash`、`coreutils`、`dpkg`、`apt`）；
3. 下载 `.deb`，解开 ar 容器里的 `data.tar.*`，去掉
   `data/data/com.termux/files/usr` 前缀后合并；
4. 重新生成 `SYMLINKS.txt`，按固定时间戳打包 deflate zip。

产物：

```
app/src/main/assets/bootstrap/termux-bootstrap.zip
```

拿到 zip 后还要把里面的 Termux 包名换成 App 的包名（当前 `com.m7ahsr`，
必须等长，否则 ELF 字符串表会错位）：

```bash
python scripts/reprefix_bootstrap.py \
  app/src/main/assets/bootstrap/termux-bootstrap.zip \
  --in-place --new com.m7ahsr
```

注意：

- 自动下载默认用 Termux **最新稳定版**（当前是 `v0.118.3`）。
  `v0.119.0` 目前是 beta 预发布，tag/文件名不同，别照抄旧文档。
- 需要固定版本时用 `--apk-tag v0.118.3`；脚本只从 APK 里取
  `lib/arm64-v8a/libtermux-bootstrap.so`，`--download-apk` 也只挑
  arm64 资产（universal APK 里同样有这个 so，但体积大得多，不推荐）。
- 依赖清单位于 `M7A_PKG_LIST`，缺失时会自动调用 `resolve_termux_deps.py`。
- 如果 `.deb` 的 `data.tar` 是 zstd，需要 `pip install zstandard`。
- `M7A_BOOTSTRAP_SRC` 已存在时会直接复用（优先于 `--download-apk`）。
  要更新 bootstrap 内容，加 `--force`。

---

## 2. rootfs：拉 arm64 镜像层并裁剪

```bash
# 2.1 只拉 linux/arm64 层（默认走南大 ghcr 镜像，可断点续传）
python scripts/pull_image.py --tag latest

# 直连 ghcr.io / 换其它镜像
python scripts/pull_image.py --mirror ghcr.io --tag latest
REG_MIRROR=ghcr.nju.edu.cn python scripts/pull_image.py

# 2.2 展开 + 裁剪 + 打包
python scripts/assemble_rootfs.py --strip /m7a --strip-openvino
```

产物：

```
app/src/main/assets/rootfs/m7a-rootfs.bin     # 实际是 gzip tar，扩展名故意用 .bin
```

### 裁剪开关

- `--strip /m7a`：删除镜像里的 `/m7a` 项目代码（新方案把 M7A 源码外置，
  运行时 bind 挂载到 `/m7a`）。**删完会重建空目录 `stage/m7a`**，因为它是
  bind 挂载点；`check_stage` 也始终要求这个目录存在。
- `--strip PATH`：可重复，按 stage 内绝对路径删除，例如
  `--strip /usr/share/doc --strip /root/.cache`。
- `--strip-openvino`：只删 venv `site-packages` 下以 `openvino` 开头的
  顶层目录（`openvino`、`openvino_telemetry*`、`openvino_tokenizers*`、
  `openvino-*.dist-info`、`openvino.libs` 等），不会碰
  `numpy` / `onnxruntime` / `opencv`。

脚本会打印每一项裁剪前后的体积、stage 总大小，以及最终产物的**精确字节数**。
重复执行是幂等的；`--pack` 只重新打包，但同样会应用 `--strip*`。

其它常用参数：

```bash
# 镜像更新后强制重新解包
python scripts/assemble_rootfs.py --force --strip /m7a --strip-openvino

# 只重新打包（stage/权限表已存在）
python scripts/assemble_rootfs.py --pack --strip /m7a

# 自定义路径（CI 一般不用）
python scripts/assemble_rootfs.py \
  --src build/work/m7a-rootfs \
  --stage build/work/rootfs-stage \
  --out app/src/main/assets/rootfs/m7a-rootfs.bin
```

校验权限（可选，能提前发现 `proot: '/bin/bash' is not executable`）：

```bash
python scripts/audit_rootfs_permissions.py
python scripts/audit_rootfs_permissions.py --strict
```

`audit_rootfs_permissions.py` 直接流式读取 `.bin`，检查关键可执行文件、
可执行目录里的文件，以及权限表是否覆盖；`--strict` 会把“可执行目录里
缺 +x”和“权限表未记录”也当成失败。

---

## 3. code：M7A 源码包

```bash
# 从 GitHub 下 tag（自动 codeload → github archive → ghfast.top 回退）
python scripts/make_code_zip.py --download latest
python scripts/make_code_zip.py --download v2026.11.8

# 用本地源码目录（自动读 git tag/commit）
python scripts/make_code_zip.py --src /path/to/March7thAssistant

# 本地目录 + 手动版本号
python scripts/make_code_zip.py --src . --tag v2026.11.8 --commit abc1234
```

产物（两个文件都必须存在，App 的 `CodeInstaller` 会分别读取）：

```
app/src/main/assets/code/m7a-code.zip
app/src/main/assets/code/version.json
```

`version.json` 格式：

```json
{
  "tag": "v2026.11.8",
  "commit": "……",
  "built_at": "2026-11-09T05:00:00Z"
}
```

zip 根目录保留源码结构（`main.py`、`pyproject.toml`、`uv.lock`、`assets/` …），
根目录也放一份同样的 `version.json`。排除：

- 任意层级的 `.git/`、`__pycache__/`
- 源码根目录的 `logs/`、`3rdparty/`
- `config.yaml`、`*.pyc`

zip 使用 deflate、固定 1980-01-01 时间戳。设 `SOURCE_DATE_EPOCH` 可以让
`built_at` 也可复现。默认 `--mirror https://ghfast.top`，CI 里如果镜像不稳，
可以 `--mirror ""` 关掉，或换 `M7A_GH_MIRROR`。

> `3rdparty/` 不打包。它是 upstream 的 git submodule，codeload 的 tar 里
> 本来也没有内容；如果运行确实需要，请单独准备后合并，不要指望源码包。

---

## 4. 最终 assets 目录

```
app/src/main/assets/
├── bootstrap/
│   └── termux-bootstrap.zip
├── rootfs/
│   └── m7a-rootfs.bin
└── code/
    ├── m7a-code.zip
    └── version.json
```

路径和文件名与 App 代码里的常量一一对应，不要改名：

- `DeployScripts.kt`：`rootfs/m7a-rootfs.bin`、`code/m7a-code.zip`、`code/version.json`
- `BootstrapInstaller.kt`：`bootstrap/termux-bootstrap.zip`

---

## 5. GitHub Actions 里的完整顺序

```yaml
env:
  M7A_WORK: ${{ github.workspace }}/build/work
  M7A_ASSETS: ${{ github.workspace }}/app/src/main/assets
  GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}

steps:
  - uses: actions/checkout@v4
    with:
      submodules: false          # 3rdparty 不参与打包

  - uses: actions/setup-python@v5
    with:
      python-version: "3.12"

  - name: 准备 Python 依赖（仅 zstd 的 .deb 需要）
    run: pip install zstandard

  - name: bootstrap
    run: |
      python scripts/build_bootstrap.py --download-apk
      python scripts/reprefix_bootstrap.py \
        app/src/main/assets/bootstrap/termux-bootstrap.zip \
        --in-place --new com.m7ahsr

  - name: rootfs
    run: |
      python scripts/pull_image.py --tag latest
      python scripts/assemble_rootfs.py --strip /m7a --strip-openvino

  - name: code zip
    run: python scripts/make_code_zip.py --download latest

  - name: 检查产物
    run: |
      ls -lh app/src/main/assets/bootstrap app/src/main/assets/rootfs app/src/main/assets/code
      python scripts/audit_rootfs_permissions.py --strict   # 可选
```

CI 建议：

- ubuntu-22.04/24.04 自带 `python3`、`curl`、`docker`、`zstd`，不需要 apt。
- 整个过程约占 2–4 GB 磁盘；`assemble_rootfs.py` 会展开约 1.3 GB 的 stage。
  磁盘紧张时可以把 `M7A_WORK` 指到空间更大的盘。
- `GITHUB_TOKEN` 只用于 GitHub API（查 release/commit），可避免匿名限流。
- 不要缓存 `M7A_STAGE` 的中间态；缓存 `M7A_ROOTFS_SRC`（镜像层）和
  `M7A_CACHE`（.deb）就够了。
- 想复现构建，固定 `--download` 的 tag 和 `SOURCE_DATE_EPOCH`。

---

## 6. 必须记住的坑

1. **`.deb` 里的路径是绝对的**
   `data.tar.*` 内是 `./data/data/com.termux/files/usr/...`，不是相对
   `usr/...`。直接 `tar -C stage` 会多出一层 `data/`，必须像
   `build_bootstrap.py` 那样剥掉前缀。

2. **zip 不能存符号链接**
   Android 侧解压不保证还原 symlink，所以 bootstrap 用 `SYMLINKS.txt`，
   每行格式是 `<链接目标>←<链接路径>`（中间是 U+2190），链接目标可以是
   相对路径（如 `coreutils←bin/mkdir`）或绝对路径
   （`/data/data/com.termux/files/usr/...`）。绝对目标**不要**改成
   `/usr/...`：bootstrap 安装到 `$PREFIX`，绝对目标必须带完整包名前缀，
   最后用 `reprefix_bootstrap.py` 等长替换成 App 包名。改了 bootstrap
   内容后必须重新生成这个文件，否则 `python3`、`bash` 等会坏。

3. **权限不能靠解包工具**
   Windows 上解压会丢 `+x`；rootfs 的权限必须从镜像层 tar 的
   `TarInfo.mode` 收集到 `M7A_MODE_MAP`，打包时强制写回。否则运行时报
   `proot error: '/bin/bash' is not executable`。

4. **rootfs 产物必须叫 `.bin`**
   它实际是 gzip tar，但扩展名不能是 `.tar.gz`：AGP 会对 `.tar.gz`
   做二次解压，497 MB 会膨胀成 1.4 GB。不要“好心”改回 `.tar.gz`。

5. **zstd 两种场景别混淆**
   - 镜像层是 zstd 的话 `pull_image.py` 会直接拒绝（当前只处理 gzip 层）。
   - Termux `.deb` 的 `data.tar.zst` 需要 `zstandard` 模块；没装时脚本会
     给出明确报错。`zstd` 命令行工具本身不够。

6. **`/m7a` 不能整个删掉**
   `--strip /m7a` 清空内容后会重建空目录，运行时要用它做 bind 挂载点。
   不要为了省体积把目录本身删掉。

7. **`assets/code/version.json` 是独立文件**
   App 的 `CodeInstaller.bundledTag()` 读的是 `assets/code/version.json`，
   不是 zip 里的那份。`make_code_zip.py` 会同时写两个；手工替换资产时
   两个都要更新，否则 App 会报“内置源码包缺少 version.json”。

8. **bootstrap 包名必须等长**
   新包名必须与 `com.termux` 等长（都是 10 字符）。当前 App 包名是
   `com.m7ahsr`，两者完整前缀都是 31 字节，替换是纯字节操作；
   改大/改小包名会破坏 ELF 的 `.dynstr`。
   `TermuxPrefixInvariantTest` 会检查 bootstrap 里是否还残留
   `com.termux`，以及绝对软链目标是否使用新前缀。

9. **Termux APK 版本**
   默认固定 `v0.118.3` 的 arm64 APK。`v0.119.0` 是 beta.3 预发布，
   release 文件名带 `apt-android-7`，旧 URL 会 404。用 `--apk-tag` 时先
   确认 release 里确实有对应 arm64 资产。

10. **不要重复下载**
   镜像层（`M7A_ROOTFS_SRC`）和 `.deb`（`M7A_CACHE`）都可以复用；
   `--force` 会强制重来，CI 里只在确实需要更新时用。

---

## 7. 本机快速自检（不跑大流程）

```bash
python3 -m py_compile scripts/*.py

for f in scripts/*.py; do
  [ "$(basename "$f")" = "_common.py" ] && continue
  python3 "$f" --help >/dev/null || exit 1
done
```

`--help` 不会联网、不会解包，可以放心在 CI 的 lint job 里跑。
