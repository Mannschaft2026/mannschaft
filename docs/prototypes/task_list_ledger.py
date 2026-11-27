"""台帳（戦役横断の課題正本）の両配置と変更申告の読み取り（CMP-261124-1222 段A・④）。

scripts/task-list-ledger.mjs（readLedger）と同じ規則で、旧配置（docs/task-list.md 1ファイル）と
新配置（索引 docs/task-list.md ＋ docs/task-list/YYYY-MM.md・legacy.md）のどちらでも、台帳の全ファイルを
所定の順（索引 → docs/task-list/ の名前順）に返す。新配置の判定は「docs/task-list/ に YYYY-MM.md か legacy.md がある」こと。

変更申告（docs/task-list/changes/*.md。CMP-261124-1222 ④）:
  実効台帳（月別ファイル＋未畳み込みの申告を base 連鎖で適用したもの）は Node の実装に委譲する
  （node scripts/task-list-ledger.mjs effective --json --root <root>）。規則を二重に持たないため Python へは移植しない。
  - 申告が0件（docs/task-list/changes/ が無い・空）のときは node を呼ばず、従来どおり生のファイルを Python で読む
    （出力・ハッシュは申告導入前と完全一致。node の無い環境でも従来どおり動く）。
  - 申告が1件以上あるのに node が無い（または effective が失敗した・申告に問題がある）ときは RuntimeError で止める。
    生の月別ファイルだけで続けると未畳み込みの更新を黙って落とすため、続行しない。
  - node の場所は環境変数 TASK_LIST_LEDGER_NODE で差し替えられる（既定は PATH の node）。
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
from pathlib import Path

INDEX_RELATIVE = Path("docs") / "task-list.md"
DIR_RELATIVE = Path("docs") / "task-list"
CHANGES_DIR_NAME = "changes"
CHANGES_RELATIVE = DIR_RELATIVE / CHANGES_DIR_NAME
LEGACY_FILE_NAME = "legacy.md"
MONTH_FILE_NAME = re.compile(r"^\d{4}-(?:0[1-9]|1[0-2])\.md$")
# 実効台帳の計算に使う Node の CLI（本モジュールと同じリポジトリのもの。--root で読む台帳を指す）
NODE_LEDGER_CLI = Path(__file__).resolve().parents[2] / "scripts" / "task-list-ledger.mjs"


def is_ledger_file_name(name: str) -> bool:
    return name == LEGACY_FILE_NAME or bool(MONTH_FILE_NAME.match(name))


def ledger_layout(root: Path) -> str:
    directory = root / DIR_RELATIVE
    names = [path.name for path in directory.iterdir()] if directory.is_dir() else []
    return "split" if any(is_ledger_file_name(name) for name in names) else "single"


def ledger_files(root: Path) -> list[Path]:
    """台帳を構成する生のファイル（索引が先頭。申告ディレクトリ changes/ は含めない）。索引が無ければ FileNotFoundError（不在を0件とは扱わない）。"""
    index = root / INDEX_RELATIVE
    if not index.is_file():
        raise FileNotFoundError(f"台帳の索引が存在しない: {index}")
    files = [index]
    if ledger_layout(root) == "split":
        files.extend(sorted((path for path in (root / DIR_RELATIVE).iterdir() if path.name != CHANGES_DIR_NAME), key=lambda path: path.name))
    return files


def change_files(root: Path) -> list[Path]:
    """docs/task-list/changes/ の中身（名前順。不正なものも含む。判定は Node 側）。
    不在・空ディレクトリは申告0件（空）。存在するがディレクトリでない（通常ファイル等）は RuntimeError（Node・Java と同じく拒否）。"""
    directory = root / CHANGES_RELATIVE
    if not directory.exists() and not directory.is_symlink():
        return []
    if not directory.is_dir():
        raise RuntimeError(f"台帳の変更申告に問題がある: {CHANGES_RELATIVE.as_posix()} がディレクトリでない（申告は {CHANGES_RELATIVE.as_posix()}/ 配下に置く）")
    return sorted(directory.iterdir(), key=lambda path: path.name)


def ledger_source_files(root: Path) -> list[Path]:
    """実効台帳の入力になる全ファイル（生の台帳ファイル＋申告）。最終更新 commit の算出用。"""
    return ledger_files(root) + change_files(root)


def _effective_via_node(root: Path) -> dict:
    node = os.environ.get("TASK_LIST_LEDGER_NODE") or shutil.which("node")
    if not node or not Path(node).exists() and not shutil.which(node):
        raise RuntimeError(
            f"台帳に変更申告（{CHANGES_RELATIVE.as_posix()}/）があるが node が見つからないため実効台帳を作れない。"
            "node を入れて再実行すること（生の月別ファイルだけで続けると未畳み込みの変更を落とす）。"
        )
    try:
        completed = subprocess.run(
            [node, str(NODE_LEDGER_CLI), "effective", "--json", "--root", str(root)],
            capture_output=True,
            text=True,
            encoding="utf-8",
            check=False,
        )
    except OSError as error:
        raise RuntimeError(f"実効台帳の取得（node {NODE_LEDGER_CLI.name} effective）を起動できない: {error}") from error
    if completed.returncode != 0:
        raise RuntimeError(f"実効台帳の取得（node {NODE_LEDGER_CLI.name} effective）に失敗した: {completed.stderr.strip()}")
    data = json.loads(completed.stdout)
    problems = data.get("changes", {}).get("problems", [])
    if problems:
        raise RuntimeError("台帳の変更申告に問題がある（構造検査が赤になる状態）: " + " / ".join(p["message"] for p in problems))
    return data


def effective_files(root: Path) -> list[tuple[str, str]]:
    """実効台帳のファイル（リポジトリ相対パス, 本文）。本文の改行は LF（read_text と同じ扱い）。申告0件なら生のファイルそのもの。"""
    if not change_files(root):
        return [(relative(root, path), path.read_text(encoding="utf-8")) for path in ledger_files(root)]
    ledger_files(root)  # 索引の不在は node を呼ぶ前に FileNotFoundError
    data = _effective_via_node(root)
    return [
        (item["path"], item["content"].replace("\r\n", "\n").replace("\r", "\n"))
        for item in data["files"]
        if item.get("content") is not None
    ]


def read_ledger_text(root: Path) -> str:
    """実効台帳の全ファイルの本文を改行で連結する（行単位で CMP 行を読む消費側の互換用）。"""
    parts = []
    for _, text in effective_files(root):
        parts.append(text if text.endswith("\n") else text + "\n")
    return "".join(parts)


def ledger_sha256(root: Path) -> str:
    """台帳の内容のハッシュ。申告0件では生のファイルのバイト列（旧配置では docs/task-list.md の sha256 と同じ値）。
    申告があるときは実効台帳の本文（UTF-8）で計算する（未畳み込みの変更も反映し、畳み込みの前後で変わらない）。"""
    digest = hashlib.sha256()
    if not change_files(root):
        for path in ledger_files(root):
            digest.update(path.read_bytes())
        return digest.hexdigest()
    ledger_files(root)
    for item in _effective_via_node(root)["files"]:
        if item.get("content") is not None:
            digest.update(item["content"].encode("utf-8"))
    return digest.hexdigest()


def relative(root: Path, path: Path) -> str:
    return path.relative_to(root).as_posix()
