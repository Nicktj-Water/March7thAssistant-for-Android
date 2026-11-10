#!/usr/bin/env python3
"""生成 APK 构建时的上游基线，供运行时对比「上游新增了什么」。

输出 app/src/main/assets/meta/upstream-baseline.json：
  {
    "source_tag": "v2026.11.8",
    "config_keys": [...],   # config.example.yaml 顶层键
    "tasks": [...]          # utils/tasks.py AVAILABLE_TASKS 的任务 ID
  }

基线取的是「打包进 APK 的源码包」本身，所以之后上游新增的键/任务
一定能被 App 识别为「本 APK 构建之后才出现」。

用法：
  python scripts/make_baseline.py --zip app/src/main/assets/code/m7a-code.zip
  python scripts/make_baseline.py --dir build/work/m7a-code
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUT = ROOT / "app/src/main/assets/meta/upstream-baseline.json"

CONFIG_RE = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*):")
TASKS_BLOCK_RE = re.compile(r"AVAILABLE_TASKS\s*=\s*\{(.*?)\n\}", re.S)
TASK_KEY_RE = re.compile(r'^\s+"([a-z0-9_]+)"\s*:', re.M)


def parse_config_keys(text: str) -> list[str]:
    return [m.group(1) for line in text.splitlines() if (m := CONFIG_RE.match(line))]


def parse_task_ids(text: str) -> list[str]:
    block = TASKS_BLOCK_RE.search(text)
    if not block:
        raise SystemExit("utils/tasks.py 中找不到 AVAILABLE_TASKS 字典")
    return TASK_KEY_RE.findall(block.group(1))


def read_members_zip(path: Path) -> dict[str, str]:
    wanted = {"assets/config/config.example.yaml", "utils/tasks.py", "version.json"}
    found: dict[str, str] = {}
    with zipfile.ZipFile(path) as zf:
        for name in zf.namelist():
            for suffix in wanted:
                if name == suffix or name.endswith("/" + suffix):
                    found[suffix] = zf.read(name).decode("utf-8", "replace")
    return found


def read_members_dir(path: Path) -> dict[str, str]:
    found: dict[str, str] = {}
    for suffix in ("assets/config/config.example.yaml", "utils/tasks.py", "version.json"):
        f = path / suffix
        if f.is_file():
            found[suffix] = f.read_text(encoding="utf-8", errors="replace")
    return found


def main() -> int:
    ap = argparse.ArgumentParser(description="生成上游基线 JSON")
    group = ap.add_mutually_exclusive_group(required=True)
    group.add_argument("--zip", type=Path, help="源码包 zip（assets/code/m7a-code.zip）")
    group.add_argument("--dir", type=Path, help="已解压的源码目录")
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = ap.parse_args()

    members = read_members_zip(args.zip) if args.zip else read_members_dir(args.dir)
    missing = [k for k in ("assets/config/config.example.yaml", "utils/tasks.py") if k not in members]
    if missing:
        print(f"缺少文件：{', '.join(missing)}", file=sys.stderr)
        return 1

    config_keys = parse_config_keys(members["assets/config/config.example.yaml"])
    tasks = parse_task_ids(members["utils/tasks.py"])
    tag = ""
    if "version.json" in members:
        try:
            tag = json.loads(members["version.json"]).get("tag", "")
        except json.JSONDecodeError:
            tag = ""

    payload = {
        "source_tag": tag,
        "config_keys": sorted(set(config_keys)),
        "tasks": sorted(set(tasks)),
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"基线已生成：{args.out}（tag={tag or '?'}，配置键 {len(payload['config_keys'])}，任务 {len(payload['tasks'])}）"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
