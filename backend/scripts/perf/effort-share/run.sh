#!/usr/bin/env bash
# F06.4.1 PR-3b AC-60 負荷実測の入口（CMP-261115-2145）。CI では流さない。重い実測は出陣後に殿が判断して流す。
#
# 手順（設計書 §5 の骨子）:
#   1. ローカル Docker の mannschaft-mysql に専用スキーマを作り、Flyway で本番と同じ migration を適用する。
#   2. 実測本人（JWT を発行できる実在の利用者）とその人が所属するチームを用意し、seed.sql で 10万／100万件を投入する。
#   3. 検証用 BE（8081）を起動する（CLAUDE.md のポート規約。CORS は不要＝k6 は API を直接叩く）。
#   4. 本スクリプトで k6（grafana/k6 を Docker）と EXPLAIN ANALYZE を取り、生ログは外部 evidence-dir に置く。
#   5. 確定 JSON は docs/development/completion-evidence.md の正本手順に従ってリポジトリへ置く（生ログの全量は入れない）。
#   6. 終わったら cleanup.sql で投入行だけを消す（seed.sql が記録表 perf_effort_seed_ids に残した ID と一致する行だけ）。
#
# 使い方: EVIDENCE_DIR=/path/outside/repo SCHEMA=effort_perf TOKEN=... TEAM_ID=... PROBE_OWNER=... MYSQL_PWD=... ./run.sh
set -euo pipefail

: "${EVIDENCE_DIR:?外部の evidence-dir を指定する（リポジトリの外）}"
: "${SCHEMA:?実測用スキーマ名}"
: "${TOKEN:?実測本人の Bearer token}"
: "${TEAM_ID:?実測チーム ID}"
: "${PROBE_OWNER:?実測本人の user ID}"
BASE_URL="${BASE_URL:-http://host.docker.internal:8081}"
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$EVIDENCE_DIR"

{
  echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "host: $(uname -a)"
  docker exec mannschaft-mysql mysql -uroot -e "SELECT VERSION(); SHOW VARIABLES LIKE 'innodb_buffer_pool_size';"
  docker exec mannschaft-mysql mysql -uroot "$SCHEMA" -e "SELECT COUNT(*) AS owner_rows FROM effort_records; SELECT COUNT(*) AS team_rows FROM effort_team_shares;"
} > "$EVIDENCE_DIR/environment.txt" 2>&1

docker exec mannschaft-mysql mysql -uroot "$SCHEMA" -e "
EXPLAIN ANALYZE SELECT id FROM effort_records WHERE owner_user_id = $PROBE_OWNER AND deleted_at IS NULL
  ORDER BY recorded_on DESC, created_at DESC, id DESC LIMIT 21;
EXPLAIN ANALYZE SELECT recorded_on, COUNT(*) FROM effort_records WHERE owner_user_id = $PROBE_OWNER
  AND definition_id = UNHEX(REPLACE('01938a00-0000-7000-8000-000000000001','-','')) AND deleted_at IS NULL
  AND recorded_on BETWEEN '2025-11-30' AND '2026-11-30' GROUP BY recorded_on;
EXPLAIN ANALYZE SELECT id FROM effort_team_shares WHERE team_id = $TEAM_ID AND deleted_at IS NULL AND state = 'VISIBLE'
  ORDER BY recorded_on DESC, created_at DESC, id DESC LIMIT 1000;" > "$EVIDENCE_DIR/explain.txt" 2>&1

docker run --rm -i --add-host=host.docker.internal:host-gateway \
  -e BASE_URL="$BASE_URL" -e TOKEN="$TOKEN" -e TEAM_ID="$TEAM_ID" \
  -v "$HERE:/scripts:ro" -v "$EVIDENCE_DIR:/out" \
  grafana/k6 run --summary-export=/out/k6-summary.json /scripts/effort-share.k6.js | tee "$EVIDENCE_DIR/k6.log"

echo "p95 は $EVIDENCE_DIR/k6-summary.json の metrics.http_req_duration{scenario:*} を見る"
