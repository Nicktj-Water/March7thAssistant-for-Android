<div align="center">

# March7thAssistant 手机版（v2）

**内置 Termux + 裁剪版 Debian 容器镜像 + 外置 M7A 源码，一个 APK 跑起来**

本仓库是在 [biirekha857-star/March7thAssistant-for-Android](https://github.com/biirekha857-star/March7thAssistant-for-Android)（已停止维护）基础上的自用延续版。
自动化逻辑来自上游 [March7thAssistant](https://github.com/moesnow/March7thAssistant)，本仓库只负责 Android 打包、部署与更新。

</div>

---

## 这个版本改了什么

旧版把 M7A 源码和依赖一起烤进 500MB 的容器镜像，升级只能重装整个镜像。
v2 把**源码外置**：

| | 旧版 | v2 |
|---|---|---|
| M7A 源码 | 烤进容器 `/m7a` | APK 内置 zip，部署时解压到 `$HOME/m7a-shared/m7a-project` |
| 运行方式 | `cd /m7a`（镜像内） | 该目录整体 bind 到容器 `/m7a` |
| 检查更新 | 无 | 首页卡片，只比对上游 Release |
| 更新方式 | 重装镜像 | 只下几 MB 源码包，依赖变了才 `uv sync` |
| 配置/日志/登录态 | 在镜像外，但更新要小心 | 一直在工程目录里，更新自动保留 |
| CDP 实时监看 | 有 | 已移除（本机自用不需要） |
| 签名 | 社区签名 | 本仓库固定 keystore（`app/release.keystore`） |

包名仍是 `com.m7ahsr`（旧版二进制里的绝对路径长度决定了它必须是 10 个字符），
但**签名不同，安装前必须先卸载旧版**。

---

## 工作原理

```
APK
├── Termux bootstrap (assets/bootstrap)       → 解压到 app 私有目录，提供 bash/proot-distro
├── Debian rootfs (assets/rootfs/m7a-rootfs.bin)
│     ├── /usr/bin/chromium + chromedriver
│     ├── /opt/venv            Python 依赖（uv sync --only-group docker 的产物）
│     └── /bin/uv
└── M7A 源码 (assets/code/m7a-code.zip)        → 解压到 $HOME/m7a-shared/m7a-project

运行：
proot-distro login --bind=<project>:/m7a m7a -- \
  /opt/venv/bin/python main.py daily
```

- 镜像里 `/m7a` 只是空的 bind 挂载点；配置、日志、`3rdparty/WebBrowser/UserProfile` 都在宿主工程目录里。
- 首次部署**完全离线**；只有「检查更新 / 更新源码」需要联网。
- 更新源码时永远保留 `config.yaml`、`logs/`、`3rdparty/`、`version.json`。

---

## 安装与首次运行

**要求**：arm64 设备、Android 7.0+（minSdk 24 / targetSdk 28）、至少 3 GB 可用空间。

1. 卸载设备上已有的旧版 M7A（签名不同，无法覆盖安装）。
2. 从本仓库 Releases 下载 `m7a-android.apk` 安装。
   HyperOS / MIUI 还需允许「安装未知应用」，并在应用信息里打开**自启动**、把省电策略设为**无限制**。
3. 打开 App → 「部署」→ 一键部署（解压 bootstrap → 释放镜像 → 安装 proot 容器 → 安装源码）。
   首次约 5–15 分钟，取决于闪存速度。
4. 部署完成后运行一次任务，在「运行状态」里点开二维码，用米游社扫码登录。
5. 之后日常挂机即可；可在设置里配置 `after_finish: Loop` 或定时任务。

> 云游戏模式首次进入排队时可能弹「等待时间较长」，这是上游在 Termux/安卓环境的已知问题，
> 关掉弹窗后任务会继续。

---

## 高级配置

「设置 → 高级配置」打开独立页面，覆盖上游 `config.yaml` 里最常用的 5 类配置（共 76 项）：

- **副本与体力**：副本类型 / 名称 / 挑战次数、培养目标、遗器清理、支援角色
- **模拟宇宙 / 差分 / 货币战争**：运行方式、命途、站点优先级、积分奖励、策略
- **忘却 / 虚构 / 末日**：开关、关卡范围、三支队伍
- **资产与兑换码**：每月兑换、三星光锥叠加、兑换码列表
- **活动**：花藏繁生、异器盈界、位面分裂

值会直接写进 `$HOME/m7a-shared/m7a-project/config.yaml`（即容器内 `/m7a/config.yaml`），**下一次运行生效**。
列表/映射字段在弹窗里按「每行一项」编辑，格式见字段说明；App 自身的任务开关、定时等仍在「设置」页，两处互不覆盖。

---

## 检查更新

首页「March7thAssistant 版本」卡片：

- **检查更新**：只查 `moesnow/March7thAssistant` 的最新 Release，与本机 `version.json` 的 tag 比较。
- **立即更新**：下载 GitHub 源码包（codeload，失败自动走 `ghfast.top` 镜像）→ 解压替换 → 对比 `uv.lock` 哈希。
  - 依赖没变：几秒钟完成。
  - 依赖变了：自动在容器内 `uv sync --only-group docker`，需要联网，可能几分钟；失败可点「重试依赖同步」。
- 更新期间不能运行任务；旧源码保留在 `$HOME/m7a-shared/update/backup`，可用于回滚。

App 自身不检查更新——想换 UI/打包逻辑，重新构建 APK 即可。

---

## 从源码构建

### GitHub Actions（推荐）

推送到 `main` 或手动触发 `build-apk` workflow，自动完成：

1. 下载 Termux APK，抽出 bootstrap，重打包名 `com.termux` → `com.m7ahsr`
2. 从 `ghcr.nju.edu.cn` 拉取 arm64 镜像层，展开、删除 `/m7a` 源码与 openvino，重新打包成 `m7a-rootfs.bin`
3. 下载指定 tag 的 M7A 源码，打成 `m7a-code.zip` + `version.json`
4. `./gradlew :app:assembleRelease`，上传 artifact，并发布到 Release（tag `apk-<run_number>`）

工作流文件：`.github/workflows/build.yml`。手动触发时可填 `code_tag` 固定上游版本。

### 本地构建

需要 JDK 21、Android SDK 37（platform + build-tools）、Python 3.12：

```bash
python -m pip install zstandard

python scripts/build_bootstrap.py --download-apk
python scripts/reprefix_bootstrap.py app/src/main/assets/bootstrap/termux-bootstrap.zip \
  --in-place --new com.m7ahsr
python scripts/pull_image.py
python scripts/assemble_rootfs.py --strip /m7a --strip-openvino
python scripts/make_code_zip.py --download latest

./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`。
资产很大，仓库里不提交（见 `.gitignore`），每个构建现场生成。脚本细节见 [scripts/README.md](scripts/README.md)。

---

## 技术要点（改代码前先看）

- **targetSdk 必须是 28**：Android 10+ 对 targetSdk ≥ 29 的应用私有目录启用 W^X，`proot` 无法执行里面的 ELF。
- **包名必须 ≤ 10 字符**：bootstrap 二进制的 `.dynstr` / shebang 硬编码 `/data/data/<pkg>/files/usr`，替换脚本要求新旧包名等长。
- **rootfs 资产后缀 `.bin`**：AGP 遇到 `.tar.gz` 会先解压再打包，500MB 会膨胀到 1.4GB；`.bin` + `noCompress` 原样存储。
- **proot 兼容**：部分机型需要 `PROOT_NO_SECCOMP=1`，冒烟测试失败时 App 会自动写入 `$HOME/m7a-shared/proot-env` 并在后续命令沿用。
- **容器内路径**：proot 里只有 `/data/data/com.m7ahsr`，没有 `/data/user/0/...`；`TermuxPaths.guestPath` 会做归一化。
- **保活**：前台服务 + `PARTIAL_WAKE_LOCK` + 可选开机自启；系统层面仍需用户手动给自启动/电池白名单。

---

## 常见问题

**安装时报「应用未安装 / 签名不一致」**
先卸载旧版。两个 APK 包名相同但签名不同，不能覆盖安装。

**部署卡在「释放镜像」**
确认剩余空间 ≥ 3GB；首次解压 500MB 镜像需要时间，不要中途杀进程。

**proot 启动失败 / `permission denied`**
重试一次部署，让冒烟测试写入 `PROOT_NO_SECCOMP=1`；仍失败请在「终端」里执行：
`cat $HOME/m7a-shared/proot-env` 查看是否生效。

**检查更新失败**
GitHub API 匿名限流（60 次/小时）或网络不通。稍后再试；更新源码包本身有 `ghfast.top` 回退。

**日志在哪**
App 内「日志」页实时查看；文件在 `$HOME/m7a-shared/m7a-project/logs/`。

---

## 许可证与致谢

- 本仓库：AGPL-3.0（沿用旧仓库与上游许可）。
- [March7thAssistant](https://github.com/moesnow/March7thAssistant) —— 自动化逻辑，AGPL-3.0。
- [Termux](https://github.com/termux/termux-app) —— 运行时与终端模拟器。
- [MAA Meow](https://github.com/AliothMoon/MAA-Meow) —— Material 3 界面语言。
- 原安卓封装：[biirekha857-star/March7thAssistant-for-Android](https://github.com/biirekha857-star/March7thAssistant-for-Android)。

本项目是非官方社区封装，与上游作者无隶属关系；自动化逻辑的问题请反馈给上游。
