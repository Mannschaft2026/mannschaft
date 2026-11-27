"""台帳（戦役横断の課題正本）の両配置の読み取り（CMP-261124-1222 段A）。

scripts/task-list-ledger.mjs（readLedger）と同じ規則で、旧配置（docs/task-list.md 1ファイル）と
新配置（索引 docs/task-list.md ＋ docs/task-list/YYYY-MM.md・legacy.md）のどちらでも、台帳の全ファイルを
所定の順（索引 → docs/task-list/ の名前順）に返す。新配置の判定は「docs/task-list/ に YYYY-MM.md か legacy.md がある」こと。
"""

from __future__ import annotations

import hashlib
import re
from pathlib import Path

INDEX_RELATIVE = Path("docs") / "task-list.md"
DIR_RELATIVE = Path("docs") / "task-list"
LEGACY_FILE_NAME = "legacy.md"
MONTH_FILE_NAME = re.compile(r"^\d{4}-(?:0[1-9]|1[0-2])\.md$")


def is_ledger_file_name(name: str) -> bool:
    return name == LEGACY_FILE_NAME or bool(MONTH_FILE_NAME.match(name))


def ledger_layout(root: Path) -> str:
    directory = root / DIR_RELATIVE
    names = [path.name for path in directory.iterdir()] if directory.is_dir() else []
    return "split" if any(is_ledger_file_name(name) for name in names) else "single"


def ledger_files(root: Path) -> list[Path]:
    """台帳を構成するファイル（索引が先頭）。索引が無ければ FileNotFoundError（不在を0件とは扱わない）。"""
    index = root / INDEX_RELATIVE
    if not index.is_file():
        raise FileNotFoundError(f"台帳の索引が存在しない: {index}")
    files = [index]
    if ledger_layout(root) == "split":
        files.extend(sorted((root / DIR_RELATIVE).iterdir(), key=lambda path: path.name))
    return files


def read_ledger_text(root: Path) -> str:
    """全ファイルの本文を改行で連結する（行単位で CMP 行を読む消費側の互換用）。"""
    parts = []
    for path in ledger_files(root):
        text = path.read_text(encoding="utf-8")
        parts.append(text if text.endswith("\n") else text + "\n")
    return "".join(parts)


def ledger_sha256(root: Path) -> str:
    """台帳の内容のハッシュ。旧配置では docs/task-list.md の sha256 と同じ値になる。"""
    digest = hashlib.sha256()
    for path in ledger_files(root):
        digest.update(path.read_bytes())
    return digest.hexdigest()


def relative(root: Path, path: Path) -> str:
    return path.relative_to(root).as_posix()
