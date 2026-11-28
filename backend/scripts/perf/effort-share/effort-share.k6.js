// F06.4.1 PR-3b AC-60 負荷実測（k6。CMP-261115-2145）。CI では流さない（マスター裁定: ローカル実測のみ）。
// 目標: 100並列・5分で 履歴（page20）の p95 <= 500ms、集計（366日）の p95 <= 1s。チーム一覧（page20）も同条件で測る。
// 環境変数: BASE_URL（既定 http://host.docker.internal:8081）、TOKEN（実測本人の Bearer）、TEAM_ID（実測チーム）。
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8081';
const TOKEN = __ENV.TOKEN;
const TEAM_ID = __ENV.TEAM_ID;
const STUDY = '01938a00-0000-7000-8000-000000000001';
const headers = { Authorization: `Bearer ${TOKEN}` };

export const options = {
  scenarios: {
    history: { executor: 'constant-vus', vus: 100, duration: '5m', exec: 'history' },
    stats: { executor: 'constant-vus', vus: 100, duration: '5m', exec: 'stats', startTime: '5m' },
    team: { executor: 'constant-vus', vus: 100, duration: '5m', exec: 'team', startTime: '10m' },
  },
  thresholds: {
    'http_req_duration{scenario:history}': ['p(95)<500'],
    'http_req_duration{scenario:stats}': ['p(95)<1000'],
    'http_req_duration{scenario:team}': ['p(95)<500'],
    'checks': ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function history() {
  const res = http.get(`${BASE}/api/v1/effort-records/me?limit=20`, { headers });
  check(res, { 'history 200': (r) => r.status === 200 });
}

export function stats() {
  const q = `date_from=2025-11-30&date_to=2026-11-30&definition_uuid=${STUDY}&field_key=duration&chart=BAR&grain=MONTH`;
  const res = http.get(`${BASE}/api/v1/effort-records/me/stats?${q}`, { headers });
  check(res, { 'stats 200': (r) => r.status === 200 });
}

export function team() {
  const res = http.get(`${BASE}/api/v1/teams/${TEAM_ID}/effort-records?limit=20`, { headers });
  check(res, { 'team 200': (r) => r.status === 200 });
}
