#!/usr/bin/env bash
# CMP-261120-1042: 復元した Gradle 依存キャッシュの整合性を検証し、壊れていれば取り除いて再取得する。
#
# 背景: setup-gradle の cache cleanup や、別ジョブが書いた部分集合のキャッシュを restore-key で復元すると、
# modules-2 に POM（メタデータ）はあるのに jar（files-2.1）が無い状態が生じ、
# 「Could not find xxx.jar」で依存解決が落ちる。Gradle は欠けた jar を再取得しないため再実行でも直らない。
# 復元直後に必要な構成を全て解決して検証し、壊れていたら modules-2 を捨てて再取得する。
# 再取得（online 解決）に失敗した場合は本物の障害なのでジョブを失敗させる。
#
# 使い方: backend/ をカレントにして  bash ../.github/scripts/verify-gradle-dependency-cache.sh
set -u

WORKSPACE_ROOT="${GITHUB_WORKSPACE:-$(cd "$(dirname "$0")/../.." && pwd)}"
INIT_SCRIPT="$WORKSPACE_ROOT/.github/scripts/gradle-resolve-all.init.gradle"
GRADLE_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}"
GRADLE_OPTS_COMMON=(--no-daemon --console=plain -q -I "$INIT_SCRIPT")

# build-logic（Kotlin テスト依存 kotlin-test-junit5 等）と本体ビルドの両方を検証対象にする。
verify() {
  local mode="$1"   # --offline または空
  ./gradlew "${GRADLE_OPTS_COMMON[@]}" $mode verifyCachedDependencies && \
  ./gradlew "${GRADLE_OPTS_COMMON[@]}" $mode -p build-logic verifyCachedDependencies
}

echo "::group::Gradle 依存キャッシュの整合性検証（offline）"
if verify "--offline"; then
  echo "依存キャッシュは完全です（jar の欠落なし）。"
  echo "::endgroup::"
  exit 0
fi
echo "::endgroup::"

echo "::warning title=Gradle 依存キャッシュが不完全::復元した modules-2 に jar の欠落があります。modules-2 を破棄して再取得します（CMP-261120-1042）"
rm -rf "$GRADLE_HOME_DIR/caches/modules-2"

echo "::group::Gradle 依存の再取得と検証（online）"
if verify ""; then
  echo "再取得後の依存解決に成功しました。"
  echo "::endgroup::"
  exit 0
fi
echo "::endgroup::"
echo "::error title=Gradle 依存の解決に失敗::キャッシュを破棄して再取得しても解決できません。リポジトリ側の依存宣言またはネットワークを確認してください"
exit 1
