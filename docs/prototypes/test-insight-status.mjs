#!/usr/bin/env node
// CMP-261124-2306 気づき箱の状態をリポジトリの状態ファイル（insight-status.js）で管理する — 受け入れテスト（試練）
// 実行: node --test docs/prototypes/test-insight-status.mjs （外部依存なし）
//
// 対象と API 契約（このテストで確定させる形）:
//   docs/prototypes/insight-status-core.js  … UMD 風。ブラウザでは window.InsightStatusCore、node では vm で評価する。
//     STATUSES                       ['inbox', 'planned', 'fixing', 'done']（循環順）
//     STATUS_LABELS                  { inbox: '未整理', planned: '課題化済', fixing: '修正中', done: '対応済' }
//     nextStatus(status)             inbox→planned→fixing→done→inbox
//     statusToTarget(status)         done→'ready' / planned・fixing→'working' / それ以外→'unknown'
//     originalInsightId(record, overlayRunId)
//                                    アリシゼーション記録の id から `ALI-${sanitize(overlayRunId)}-` を除いた元 ID。手入力・不一致は null
//     validateStatusFile(file)       → { items: {妥当な項目のみ}, warnings: [{ key, reason }] }（例外を投げない）
//     resolveInsightStatuses(records, file, { overlayRunId })
//                                    → { insights: [{ ...record, status, statusOrigin, statusKey, pr, note }], warnings }
//                                       statusOrigin: 'file'（状態ファイル優先）/ 'local'（ファイルに無い）/ 'local-unexported'（ローカルが新しく未書き出し）
//     buildStatusFileSource(records, file, { overlayRunId })
//                                    → `window.BETA_INSIGHT_STATUS = {...};` 形式の決定的な文字列
//   docs/prototypes/insight-status.js       … 状態の正本 window.BETA_INSIGHT_STATUS = { schemaVersion: 1, items: {...} }
//
// 時刻は固定値のみを使い、現在時刻には依存しない（TEST_CONVENTION.md §2.4）。

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const prototypeDirectory = path.dirname(fileURLToPath(import.meta.url));
const corePath = path.join(prototypeDirectory, 'insight-status-core.js');
const statusPath = path.join(prototypeDirectory, 'insight-status.js');
const htmlPath = path.join(prototypeDirectory, 'beta-inventory-board.html');
const dataPath = path.join(prototypeDirectory, 'beta-inventory-board-data.js');
const validatorPath = path.join(prototypeDirectory, 'validate-beta-inventory-board.mjs');

const RUN_ID = 'october-wave2-login-resident-overlay-20261026';
const ORIGINAL_ID = 'ALIC-CMP0035-B-YA-01';
const BOARD_ID = `ALI-${RUN_ID}-${ORIGINAL_ID}`;

function readOrFail(filePath, label) {
  if (!fs.existsSync(filePath)) assert.fail(`${label} が存在しない（未実装）: ${path.relative(prototypeDirectory, filePath)}`);
  return fs.readFileSync(filePath, 'utf8');
}

function loadCore() {
  const source = readOrFail(corePath, 'insight-status-core.js');
  const context = { window: {} };
  vm.createContext(context);
  vm.runInContext(source, context, { filename: 'insight-status-core.js' });
  const core = context.window.InsightStatusCore;
  assert.ok(core, 'insight-status-core.js は window.InsightStatusCore を公開すること');
  return core;
}

function evaluateStatusSource(source, filename = 'insight-status.js') {
  const context = { window: {} };
  vm.createContext(context);
  vm.runInContext(source, context, { filename });
  // vm の別レルム由来のオブジェクトを deepEqual で比較できるよう JSON で正規化する
  return context.window.BETA_INSIGHT_STATUS === undefined
    ? undefined
    : JSON.parse(JSON.stringify(context.window.BETA_INSIGHT_STATUS));
}

const plain = (value) => JSON.parse(JSON.stringify(value));
const ms = (iso) => Date.parse(iso);

function aliRecord(originalId, overrides = {}) {
  return {
    id: `ALI-${RUN_ID}-${originalId}`,
    registrationSource: 'alicization',
    runId: 'alicization-cmp0035-run-20261030',
    originalRunId: 'alicization-cmp0035-run-20261030',
    status: 'inbox',
    title: `観測 ${originalId}`,
    featureKey: 'authz-boundary',
    createdAt: ms('2026-10-30T00:00:00Z'),
    updatedAt: ms('2026-10-30T00:00:00Z'),
    ...overrides
  };
}

function noteRecord(id, overrides = {}) {
  return {
    id,
    registrationSource: 'self',
    status: 'inbox',
    title: `手入力 ${id}`,
    featureKey: 'auth',
    createdAt: ms('2026-11-01T00:00:00Z'),
    updatedAt: ms('2026-11-01T00:00:00Z'),
    ...overrides
  };
}

function statusFile(items, schemaVersion = 1) {
  return { schemaVersion, items };
}

function resolveOne(core, record, file) {
  const result = core.resolveInsightStatuses([record], file, { overlayRunId: RUN_ID });
  assert.equal(result.insights.length, 1);
  return { insight: result.insights[0], warnings: result.warnings };
}

// ---------------------------------------------------------------- AC1
describe('AC1 状態値は4つで inbox→planned→fixing→done→inbox と循環する', () => {
  test('状態値は inbox・planned・fixing・done の4つで、この順に並ぶ', () => {
    const core = loadCore();
    assert.deepEqual(plain(core.STATUSES), ['inbox', 'planned', 'fixing', 'done']);
  });

  test('状態の表示名は 未整理・課題化済・修正中・対応済 である', () => {
    const core = loadCore();
    assert.deepEqual(plain(core.STATUS_LABELS), { inbox: '未整理', planned: '課題化済', fixing: '修正中', done: '対応済' });
  });

  test('遷移は inbox→planned→fixing→done→inbox と一周する', () => {
    const core = loadCore();
    assert.equal(core.nextStatus('inbox'), 'planned');
    assert.equal(core.nextStatus('planned'), 'fixing');
    assert.equal(core.nextStatus('fixing'), 'done');
    assert.equal(core.nextStatus('done'), 'inbox');
  });

  test('未知の状態からの遷移も4状態のいずれかに収まり例外を投げない', () => {
    const core = loadCore();
    assert.ok(core.STATUSES.includes(core.nextStatus('unknown-status')));
    assert.ok(core.STATUSES.includes(core.nextStatus(undefined)));
  });

  test('ボード対象への写像で fixing は working、done は ready、planned は working、inbox は unknown になる', () => {
    const core = loadCore();
    assert.equal(core.statusToTarget('fixing'), 'working');
    assert.equal(core.statusToTarget('done'), 'ready');
    assert.equal(core.statusToTarget('planned'), 'working');
    assert.equal(core.statusToTarget('inbox'), 'unknown');
    assert.equal(core.statusToTarget('bogus'), 'unknown');
  });
});

// ---------------------------------------------------------------- AC2
describe('AC2 状態ファイルのキーはボード id 完全一致または住民 overlay の元 ID で照合できる', () => {
  test('アリシゼーション記録の元 ID は overlay の runId 接頭辞を除いた部分になる', () => {
    const core = loadCore();
    assert.equal(core.originalInsightId(aliRecord(ORIGINAL_ID), RUN_ID), ORIGINAL_ID);
  });

  test('runId に記号を含む場合はボードと同じく英数字・_・- 以外を - に置換した接頭辞で照合する', () => {
    const core = loadCore();
    const record = aliRecord('X-01', { id: 'ALI-run-2026-11-24T00-00-00Z-X-01' });
    assert.equal(core.originalInsightId(record, 'run:2026-11-24T00:00:00Z'), 'X-01');
  });

  test('手入力 NOTE や接頭辞が一致しない記録の元 ID は null になる', () => {
    const core = loadCore();
    assert.equal(core.originalInsightId(noteRecord('NOTE-261124-090000-000'), RUN_ID), null);
    assert.equal(core.originalInsightId(aliRecord(ORIGINAL_ID), 'another-run'), null);
  });

  test('ボード id と完全一致するキーで状態ファイルの状態が適用される', () => {
    const core = loadCore();
    const file = statusFile({ [BOARD_ID]: { status: 'planned', updatedAt: '2026-11-24T00:00:00Z' } });
    const { insight } = resolveOne(core, aliRecord(ORIGINAL_ID), file);
    assert.equal(insight.status, 'planned');
    assert.equal(insight.statusKey, BOARD_ID);
    assert.equal(insight.statusOrigin, 'file');
  });

  test('住民 overlay の元 ID のキーで状態ファイルの状態が適用される', () => {
    const core = loadCore();
    const file = statusFile({ [ORIGINAL_ID]: { status: 'done', pr: '#4240', updatedAt: '2026-11-24' } });
    const { insight } = resolveOne(core, aliRecord(ORIGINAL_ID), file);
    assert.equal(insight.status, 'done');
    assert.equal(insight.statusKey, ORIGINAL_ID);
    assert.equal(insight.statusOrigin, 'file');
    assert.equal(insight.pr, '#4240');
  });

  test('完全一致キーと元 ID キーが両方ある場合は完全一致を優先する', () => {
    const core = loadCore();
    const file = statusFile({
      [BOARD_ID]: { status: 'fixing', updatedAt: '2026-11-24T00:00:00Z' },
      [ORIGINAL_ID]: { status: 'done', updatedAt: '2026-11-24T00:00:00Z' }
    });
    const { insight } = resolveOne(core, aliRecord(ORIGINAL_ID), file);
    assert.equal(insight.status, 'fixing');
    assert.equal(insight.statusKey, BOARD_ID);
  });

  test('手入力 NOTE は完全一致キーでのみ照合する', () => {
    const core = loadCore();
    const id = 'NOTE-261124-090000-000';
    const exact = resolveOne(core, noteRecord(id), statusFile({ [id]: { status: 'planned', updatedAt: '2026-11-24' } }));
    assert.equal(exact.insight.status, 'planned');
    assert.equal(exact.insight.statusOrigin, 'file');
    // 時刻部分だけのキーや接頭辞違いでは当たらない
    const partial = resolveOne(core, noteRecord(id), statusFile({ '261124-090000-000': { status: 'done', updatedAt: '2026-11-24' } }));
    assert.equal(partial.insight.status, 'inbox');
    assert.equal(partial.insight.statusOrigin, 'local');
  });

  test('別 run の接頭辞で組まれたアリシゼーション記録は元 ID キーに当たらない', () => {
    const core = loadCore();
    const record = aliRecord(ORIGINAL_ID, { id: `ALI-other-run-${ORIGINAL_ID}` });
    const { insight } = resolveOne(core, record, statusFile({ [ORIGINAL_ID]: { status: 'done', updatedAt: '2026-11-24' } }));
    assert.equal(insight.status, 'inbox');
    assert.equal(insight.statusOrigin, 'local');
  });
});

// ---------------------------------------------------------------- AC3
describe('AC3 状態ファイル優先、ただしローカルが新しければ未書き出しとして区別する', () => {
  test('状態ファイルに該当があり IndexedDB 側が古ければ状態ファイルの状態を表示する', () => {
    const core = loadCore();
    const record = aliRecord(ORIGINAL_ID, { status: 'planned', updatedAt: ms('2026-11-01T00:00:00Z') });
    const { insight } = resolveOne(core, record, statusFile({ [ORIGINAL_ID]: { status: 'done', updatedAt: '2026-11-24T00:00:00Z' } }));
    assert.equal(insight.status, 'done');
    assert.equal(insight.statusOrigin, 'file');
  });

  test('IndexedDB 側の updatedAt が新しく状態が異なればローカルの状態を表示し未書き出しとする', () => {
    const core = loadCore();
    const record = aliRecord(ORIGINAL_ID, { status: 'inbox', updatedAt: ms('2026-11-25T00:00:00Z') });
    const { insight } = resolveOne(core, record, statusFile({ [ORIGINAL_ID]: { status: 'done', updatedAt: '2026-11-24T00:00:00Z' } }));
    assert.equal(insight.status, 'inbox');
    assert.equal(insight.statusOrigin, 'local-unexported');
    assert.equal(insight.statusKey, ORIGINAL_ID);
  });

  test('updatedAt が同時刻なら状態ファイルを優先する（境界）', () => {
    const core = loadCore();
    const record = aliRecord(ORIGINAL_ID, { status: 'fixing', updatedAt: ms('2026-11-24T00:00:00Z') });
    const { insight } = resolveOne(core, record, statusFile({ [ORIGINAL_ID]: { status: 'done', updatedAt: '2026-11-24T00:00:00Z' } }));
    assert.equal(insight.status, 'done');
    assert.equal(insight.statusOrigin, 'file');
  });

  test('ローカルが新しくても状態が同じなら未書き出しにしない', () => {
    const core = loadCore();
    const record = aliRecord(ORIGINAL_ID, { status: 'done', updatedAt: ms('2026-11-30T00:00:00Z') });
    const { insight } = resolveOne(core, record, statusFile({ [ORIGINAL_ID]: { status: 'done', updatedAt: '2026-11-24' } }));
    assert.equal(insight.status, 'done');
    assert.equal(insight.statusOrigin, 'file');
  });

  test('タイムゾーン付き ISO 日時も時刻として比較する', () => {
    const core = loadCore();
    // 2026-11-24T09:00:00+09:00 は 2026-11-24T00:00:00Z。ローカルはその1分後
    const record = aliRecord(ORIGINAL_ID, { status: 'planned', updatedAt: ms('2026-11-24T00:01:00Z') });
    const { insight } = resolveOne(core, record, statusFile({ [ORIGINAL_ID]: { status: 'done', updatedAt: '2026-11-24T09:00:00+09:00' } }));
    assert.equal(insight.statusOrigin, 'local-unexported');
    assert.equal(insight.status, 'planned');
  });

  test('状態ファイルに無いレコードは IndexedDB の状態のまま local とする', () => {
    const core = loadCore();
    const record = noteRecord('NOTE-261120-100000-000', { status: 'fixing' });
    const { insight } = resolveOne(core, record, statusFile({ [ORIGINAL_ID]: { status: 'done', updatedAt: '2026-11-24' } }));
    assert.equal(insight.status, 'fixing');
    assert.equal(insight.statusOrigin, 'local');
    assert.equal(insight.statusKey, null);
  });

  test('解決は元レコードを書き換えず、他のフィールドを保持する', () => {
    const core = loadCore();
    const record = aliRecord(ORIGINAL_ID, { status: 'inbox' });
    const snapshot = structuredClone(record);
    const { insight } = resolveOne(core, record, statusFile({ [ORIGINAL_ID]: { status: 'done', updatedAt: '2026-11-24' } }));
    assert.deepEqual(record, snapshot);
    assert.equal(insight.id, record.id);
    assert.equal(insight.title, record.title);
    assert.equal(insight.featureKey, record.featureKey);
  });
});

// ---------------------------------------------------------------- AC4
describe('AC4 状態ファイルの不正項目は警告一覧として返し、適用しない', () => {
  test('妥当な状態ファイルは警告なしで全項目を返す', () => {
    const core = loadCore();
    const result = core.validateStatusFile(statusFile({
      A: { status: 'inbox', updatedAt: '2026-11-24' },
      B: { status: 'planned', updatedAt: '2026-11-24T10:20:30Z' },
      C: { status: 'fixing', updatedAt: '2026-11-24T10:20:30.123+09:00', pr: '#1', note: 'メモ' },
      D: { status: 'done', updatedAt: '2026-11-24T10:20Z' }
    }));
    assert.deepEqual(plain(result.warnings), []);
    assert.deepEqual(Object.keys(result.items).sort(), ['A', 'B', 'C', 'D']);
  });

  test('未知の status は警告に載り items から除外される', () => {
    const core = loadCore();
    const result = core.validateStatusFile(statusFile({
      OK: { status: 'done', updatedAt: '2026-11-24' },
      BAD: { status: 'closed', updatedAt: '2026-11-24' }
    }));
    assert.deepEqual(Object.keys(result.items), ['OK']);
    assert.equal(result.warnings.length, 1);
    assert.equal(result.warnings[0].key, 'BAD');
    assert.equal(typeof result.warnings[0].reason, 'string');
    assert.ok(result.warnings[0].reason.length > 0);
  });

  for (const [label, updatedAt] of [
    ['スラッシュ区切り', '2026/11/24'],
    ['自然言語', 'yesterday'],
    ['エポックミリ秒の数値', 1795392000000],
    ['存在しない月日', '2026-13-40'],
    ['空文字', ''],
    ['未指定', undefined]
  ]) {
    test(`updatedAt が ISO 日時でない（${label}）項目は警告に載り適用されない`, () => {
      const core = loadCore();
      const entry = { status: 'done' };
      if (updatedAt !== undefined) entry.updatedAt = updatedAt;
      const result = core.validateStatusFile(statusFile({ BAD: entry }));
      assert.deepEqual(Object.keys(result.items), []);
      assert.equal(result.warnings.length, 1);
      assert.equal(result.warnings[0].key, 'BAD');
    });
  }

  test('項目がオブジェクトでない場合は警告に載る', () => {
    const core = loadCore();
    const result = core.validateStatusFile(statusFile({ NULL: null, TEXT: 'done' }));
    assert.deepEqual(Object.keys(result.items), []);
    // 別レルム配列の比較不備の修正（期待値は不変）: vm 由来の配列を plain() で正規化してから比較する
    assert.deepEqual(plain(result.warnings.map((warning) => warning.key).sort()), ['NULL', 'TEXT']);
  });

  test('schemaVersion 不一致ならファイル全体を適用せず警告を返す', () => {
    const core = loadCore();
    const result = core.validateStatusFile(statusFile({ OK: { status: 'done', updatedAt: '2026-11-24' } }, 2));
    assert.deepEqual(Object.keys(result.items), []);
    assert.ok(result.warnings.length >= 1);
  });

  test('items がオブジェクトでなければ警告を返し例外を投げない', () => {
    const core = loadCore();
    const result = core.validateStatusFile({ schemaVersion: 1, items: [] });
    assert.deepEqual(Object.keys(result.items), []);
    assert.ok(result.warnings.length >= 1);
  });

  test('不正項目は解決時にも適用されず、警告が resolveInsightStatuses から返る', () => {
    const core = loadCore();
    const record = aliRecord(ORIGINAL_ID, { status: 'planned' });
    const { insight, warnings } = resolveOne(core, record, statusFile({ [ORIGINAL_ID]: { status: 'closed', updatedAt: '2026-11-24' } }));
    assert.equal(insight.status, 'planned');
    assert.equal(insight.statusOrigin, 'local');
    assert.equal(warnings.length, 1);
    assert.equal(warnings[0].key, ORIGINAL_ID);
  });

  test('schemaVersion 不一致の状態ファイルは解決時に無視され警告が返る', () => {
    const core = loadCore();
    const record = aliRecord(ORIGINAL_ID, { status: 'planned' });
    const { insight, warnings } = resolveOne(core, record, statusFile({ [ORIGINAL_ID]: { status: 'done', updatedAt: '2026-11-24' } }, 99));
    assert.equal(insight.status, 'planned');
    assert.equal(insight.statusOrigin, 'local');
    assert.ok(warnings.length >= 1);
  });
});

// ---------------------------------------------------------------- AC5
describe('AC5 状態ファイルとローカル未書き出し変更をマージした insight-status.js を生成する', () => {
  const baseFile = () => statusFile({
    [ORIGINAL_ID]: { status: 'done', pr: '#4240', updatedAt: '2026-11-24', note: '殿確認済み' },
    'ALIC-ZZZ-01': { status: 'planned', pr: '', updatedAt: '2026-11-20T00:00:00Z', note: '' },
    'ORPHAN-ONLY-IN-FILE': { status: 'fixing', pr: '#9999', updatedAt: '2026-11-10', note: '記録なし' }
  });
  const records = () => [
    // 状態ファイルの元 ID キーにローカル未書き出し変更がある
    aliRecord(ORIGINAL_ID, { status: 'fixing', updatedAt: ms('2026-11-25T03:04:05.000Z') }),
    // 状態ファイルの値のまま（ローカルが古い）
    aliRecord('ALIC-ZZZ-01', { status: 'inbox', updatedAt: ms('2026-10-30T00:00:00Z') }),
    // ファイルに無いアリシゼーション記録で状態を動かした
    aliRecord('ALIC-NEW-02', { status: 'planned', updatedAt: ms('2026-11-23T00:00:00Z') }),
    // ファイルに無いアリシゼーション記録で inbox のまま（含めない）
    aliRecord('ALIC-UNTOUCHED-03', { status: 'inbox' }),
    // 手入力 NOTE: 状態を動かしたもの（含める）と inbox のまま（含めない）
    noteRecord('NOTE-261122-080000-000', { status: 'fixing', updatedAt: ms('2026-11-22T08:00:00.000Z') }),
    noteRecord('NOTE-261122-090000-000', { status: 'inbox' })
  ];

  function buildItems(core, file = baseFile(), list = records()) {
    const source = core.buildStatusFileSource(list, file, { overlayRunId: RUN_ID });
    assert.equal(typeof source, 'string');
    const evaluated = evaluateStatusSource(source, 'generated-insight-status.js');
    assert.ok(evaluated, '生成文字列は window.BETA_INSIGHT_STATUS を定義すること');
    return { source, evaluated };
  }

  test('生成文字列を vm で再評価すると schemaVersion 1 の状態ファイルになり、検証で警告が出ない（往復一致）', () => {
    const core = loadCore();
    const { evaluated } = buildItems(core);
    assert.equal(evaluated.schemaVersion, 1);
    assert.deepEqual(plain(core.validateStatusFile(evaluated).warnings), []);
    // 再評価結果を再び入力にしても同じ文字列になる
    const again = core.buildStatusFileSource([], evaluated, { overlayRunId: RUN_ID });
    assert.deepEqual(evaluateStatusSource(again), evaluated);
  });

  test('元 ID で登録されていたキーは元 ID のまま維持し、ボード id キーを新設しない', () => {
    const core = loadCore();
    const { evaluated } = buildItems(core);
    assert.ok(ORIGINAL_ID in evaluated.items);
    assert.ok(!(BOARD_ID in evaluated.items));
  });

  test('ローカル未書き出し変更は status と updatedAt を上書きし、pr と note は状態ファイル側の値を保持する', () => {
    const core = loadCore();
    const { evaluated } = buildItems(core);
    const entry = evaluated.items[ORIGINAL_ID];
    assert.equal(entry.status, 'fixing');
    assert.equal(Date.parse(entry.updatedAt), ms('2026-11-25T03:04:05.000Z'));
    assert.equal(entry.pr, '#4240');
    assert.equal(entry.note, '殿確認済み');
  });

  test('ローカルが古い項目と記録の無い項目は状態ファイルの内容をそのまま保持する', () => {
    const core = loadCore();
    const { evaluated } = buildItems(core);
    assert.deepEqual(evaluated.items['ALIC-ZZZ-01'], { status: 'planned', pr: '', updatedAt: '2026-11-20T00:00:00Z', note: '' });
    assert.deepEqual(evaluated.items['ORPHAN-ONLY-IN-FILE'], { status: 'fixing', pr: '#9999', updatedAt: '2026-11-10', note: '記録なし' });
  });

  test('状態ファイルに無いアリシゼーション記録は状態を動かした分だけ元 ID キーで追加する', () => {
    const core = loadCore();
    const { evaluated } = buildItems(core);
    assert.equal(evaluated.items['ALIC-NEW-02']?.status, 'planned');
    assert.equal(Date.parse(evaluated.items['ALIC-NEW-02'].updatedAt), ms('2026-11-23T00:00:00Z'));
    assert.ok(!('ALIC-UNTOUCHED-03' in evaluated.items));
    assert.ok(!(`ALI-${RUN_ID}-ALIC-NEW-02` in evaluated.items));
  });

  test('手入力 NOTE はローカルで状態を動かした分だけ完全一致キーで含める', () => {
    const core = loadCore();
    const { evaluated } = buildItems(core);
    assert.equal(evaluated.items['NOTE-261122-080000-000']?.status, 'fixing');
    assert.ok(!('NOTE-261122-090000-000' in evaluated.items));
  });

  test('キー順はソート済みで、入力順を変えても同一の文字列になる（決定的）', () => {
    const core = loadCore();
    const first = buildItems(core).source;
    const second = buildItems(core, baseFile(), records().reverse()).source;
    assert.equal(first, second);
    const keys = Object.keys(buildItems(core).evaluated.items);
    assert.deepEqual(keys, [...keys].sort());
  });

  test('状態ファイルが未定義でもローカル変更だけで書き出せる', () => {
    const core = loadCore();
    const source = core.buildStatusFileSource(records(), undefined, { overlayRunId: RUN_ID });
    const evaluated = evaluateStatusSource(source);
    assert.equal(evaluated.schemaVersion, 1);
    assert.deepEqual(Object.keys(evaluated.items).sort(), ['ALIC-CMP0035-B-YA-01', 'ALIC-NEW-02', 'NOTE-261122-080000-000']);
  });

  test('不正項目は書き出しへ持ち込まない', () => {
    const core = loadCore();
    const file = statusFile({ ...baseFile().items, BROKEN: { status: 'closed', updatedAt: '2026-11-24' } });
    const { evaluated } = buildItems(core, file);
    assert.ok(!('BROKEN' in evaluated.items));
  });
});

// ---------------------------------------------------------------- AC6
describe('AC6 状態ファイルが読めないときは IndexedDB の状態だけで動く', () => {
  for (const [label, file] of [['undefined', undefined], ['null', null], ['文字列', 'broken'], ['空オブジェクト', {}]]) {
    test(`状態ファイルが ${label} でも例外を投げずローカル状態をそのまま返す`, () => {
      const core = loadCore();
      const list = [aliRecord(ORIGINAL_ID, { status: 'planned' }), noteRecord('NOTE-261120-100000-000', { status: 'fixing' })];
      const result = core.resolveInsightStatuses(list, file, { overlayRunId: RUN_ID });
      assert.deepEqual(result.insights.map((insight) => insight.status), ['planned', 'fixing']);
      assert.deepEqual(result.insights.map((insight) => insight.statusOrigin), ['local', 'local']);
      assert.ok(Array.isArray(result.warnings));
    });
  }

  test('状態ファイル未定義（script 不在）のときは警告も出さない', () => {
    const core = loadCore();
    const result = core.resolveInsightStatuses([aliRecord(ORIGINAL_ID)], undefined, { overlayRunId: RUN_ID });
    assert.deepEqual(plain(result.warnings), []);
    assert.deepEqual(plain(core.validateStatusFile(undefined)), { items: {}, warnings: [] });
  });

  test('overlayRunId が無くても例外を投げず完全一致キーだけで照合する', () => {
    const core = loadCore();
    const file = statusFile({ [BOARD_ID]: { status: 'done', updatedAt: '2026-11-24' }, 'ALIC-X': { status: 'done', updatedAt: '2026-11-24' } });
    const result = core.resolveInsightStatuses([aliRecord(ORIGINAL_ID), aliRecord('ALIC-X')], file);
    assert.deepEqual(result.insights.map((insight) => insight.status), ['done', 'inbox']);
  });

  test('レコードが空でも空の結果を返す', () => {
    const core = loadCore();
    const result = core.resolveInsightStatuses([], undefined, { overlayRunId: RUN_ID });
    assert.deepEqual(plain(result), { insights: [], warnings: [] });
  });
});

// ---------------------------------------------------------------- AC7
describe('AC7 ボード html が状態ファイルとコアを読み、修正中・書き出しボタン・注記を持つ', () => {
  const html = () => fs.readFileSync(htmlPath, 'utf8');

  test('insight-status.js と insight-status-core.js を data.js の近くで、本体スクリプトより前に読み込む', () => {
    const source = html();
    const dataTag = source.indexOf('<script src="./beta-inventory-board-data.js"></script>');
    const statusTag = source.indexOf('<script src="./insight-status.js"></script>');
    const coreTag = source.indexOf('<script src="./insight-status-core.js"></script>');
    const mainScript = source.indexOf('const baseInventory = window.BETA_INVENTORY_DATA');
    assert.ok(dataTag >= 0, 'data.js の script タグ');
    assert.ok(statusTag >= 0, 'insight-status.js の script タグがない');
    assert.ok(coreTag >= 0, 'insight-status-core.js の script タグがない');
    assert.ok(statusTag < mainScript && coreTag < mainScript, '本体スクリプトより前に読み込むこと');
    assert.ok(Math.abs(statusTag - dataTag) < 300 && Math.abs(coreTag - dataTag) < 300, 'data.js の近くで読み込むこと');
  });

  test('状態判定はインラインではなく InsightStatusCore を使い、旧3状態の循環を残さない', () => {
    const source = html();
    assert.match(source, /InsightStatusCore\.(resolveInsightStatuses|nextStatus|statusToTarget|buildStatusFileSource)/);
    assert.match(source, /window\.BETA_INSIGHT_STATUS/);
    assert.ok(!source.includes("insight.status === 'planned' ? 'done'"), '旧 advanceInsightStatus の3状態循環が残っている');
  });

  test('件数チップに修正中の件数が含まれる', () => {
    const source = html();
    assert.match(source, /class="wip-chip"[^\n]*修正中/);
  });

  test('ヘッダに「状態を書き出す」ボタン（id="exportInsightStatus"）がある', () => {
    const source = html();
    assert.match(source, /<button[^>]*id="exportInsightStatus"[^>]*>[\s\S]{0,80}状態を書き出す/);
  });

  test('storage-note が状態ファイル insight-status.js に言及する', () => {
    const notes = [...html().matchAll(/<div class="storage-note">([\s\S]*?)<\/div>/g)].map((match) => match[1]);
    assert.ok(notes.length >= 1);
    assert.ok(notes.some((note) => note.includes('insight-status.js')), 'storage-note に insight-status.js の記述がない');
    assert.ok(!notes.some((note) => /IndexedDB[^。]*にだけ保存/.test(note)), '「IndexedDB にだけ保存」の記述が残っている');
  });

  test('状態ファイルの警告件数をヘッダに表示する要素がある', () => {
    assert.match(html(), /id="insightStatusWarnings"/);
  });
});

// ---------------------------------------------------------------- AC8
describe('AC8 初期の insight-status.js に ALIC-CMP0035-B-YA-01 を対応済で登録する', () => {
  test('insight-status.js は schemaVersion 1 で検証警告がない', () => {
    const core = loadCore();
    const file = evaluateStatusSource(readOrFail(statusPath, 'insight-status.js'));
    assert.equal(file?.schemaVersion, 1);
    assert.deepEqual(plain(core.validateStatusFile(file).warnings), []);
  });

  test('ALIC-CMP0035-B-YA-01 が done / PR #4240 / 2026-11-24 で元 ID キー登録されている', () => {
    const file = evaluateStatusSource(readOrFail(statusPath, 'insight-status.js'));
    const entry = file?.items?.[ORIGINAL_ID];
    assert.ok(entry, `${ORIGINAL_ID} が登録されていない`);
    assert.equal(entry.status, 'done');
    assert.match(String(entry.pr), /#?4240\b/);
    assert.match(entry.updatedAt, /^2026-11-24/);
  });

  test('ボード上の id は overlay 全体の runId で組まれ、実データの記録に状態ファイルの done が当たる', () => {
    const core = loadCore();
    const context = { window: {} };
    vm.runInNewContext(fs.readFileSync(dataPath, 'utf8'), context, { filename: 'beta-inventory-board-data.js' });
    const overlay = context.window.BETA_INVENTORY_DATA.b0RunOverlay;
    // collectAlicizationInsights と同じ規則（overlay.runId を記号置換）で id を組む
    const stableRunPart = String(overlay.runId).replace(/[^A-Za-z0-9_-]/g, '-');
    assert.equal(stableRunPart, RUN_ID);
    const finding = [...(overlay.insights || []), ...(overlay.journeys || []).flatMap((journey) => journey.insights || [])]
      .find((item) => item.id === ORIGINAL_ID);
    assert.ok(finding, '実データに対象の気づきがない');
    const record = aliRecord(ORIGINAL_ID, { id: `ALI-${stableRunPart}-${finding.id}`, updatedAt: Date.parse(finding.observedAt || '2026-10-30') || ms('2026-10-30') });
    assert.equal(record.id, BOARD_ID);
    const file = evaluateStatusSource(readOrFail(statusPath, 'insight-status.js'));
    const { insight } = resolveOne(core, record, file);
    assert.equal(insight.status, 'done');
    assert.equal(insight.statusOrigin, 'file');
    assert.equal(core.statusToTarget(insight.status), 'ready');
  });
});

// ---------------------------------------------------------------- AC7 validator
describe('AC7 validate-beta-inventory-board.mjs が状態ファイルを検証する', () => {
  function withTempStatus(content, run) {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'insight-status-test-'));
    const file = path.join(directory, 'insight-status.js');
    fs.writeFileSync(file, content);
    try {
      return run(file);
    } finally {
      fs.rmSync(directory, { recursive: true, force: true });
    }
  }
  const runValidator = (...args) => spawnSync(process.execPath, [validatorPath, ...args], { encoding: 'utf8' });
  const statusSource = (file) => `window.BETA_INSIGHT_STATUS = ${JSON.stringify(file, null, 2)};\n`;

  test('validator のソースが AC7 の必須文字列と状態ファイル検証を要求している', () => {
    const source = fs.readFileSync(validatorPath, 'utf8');
    for (const required of ['insight-status.js', 'insight-status-core.js', 'id="exportInsightStatus"', '修正中', '--insight-status', 'validateStatusFile']) {
      assert.ok(source.includes(required), `validator に ${required} の検査がない`);
    }
  });

  test('既定（引数なし）でリポジトリの insight-status.js を検証して成功する', () => {
    // 既存の data.js 陳腐化（CMP正本行数と参照抽出数の不一致）で validator 全体は非0終了する。これは CMP-261124-2306 の範囲外のため、
    // 状態ファイル検証の成否（成功行の出力と insight-status 起因のエラー無し）だけを確認する。
    const result = runValidator();
    assert.match(result.stdout, /insight-status\.js: OK/);
    assert.doesNotMatch(result.stderr, /insight-status/);
  });

  test('妥当な状態ファイルを --insight-status で与えると成功する', () => {
    const result = withTempStatus(
      statusSource({ schemaVersion: 1, items: { [ORIGINAL_ID]: { status: 'fixing', pr: '', updatedAt: '2026-11-24T00:00:00Z', note: '' } } }),
      (file) => runValidator('--insight-status', file)
    );
    // 既存の data.js 陳腐化（CMP正本行数と参照抽出数の不一致）で validator 全体は非0終了する。これは CMP-261124-2306 の範囲外のため、
    // 状態ファイル検証の成否（成功行の出力と insight-status 起因のエラー無し）だけを確認する。
    assert.match(result.stdout, /insight-status\.js: OK/);
    assert.doesNotMatch(result.stderr, /insight-status/);
  });

  for (const [label, file] of [
    ['未知の status', { schemaVersion: 1, items: { X: { status: 'closed', updatedAt: '2026-11-24' } } }],
    ['ISO でない updatedAt', { schemaVersion: 1, items: { X: { status: 'done', updatedAt: '2026/11/24' } } }],
    ['schemaVersion 不一致', { schemaVersion: 2, items: {} }]
  ]) {
    test(`壊れた状態ファイル（${label}）を与えると validator が非0で終了する`, () => {
      const result = withTempStatus(statusSource(file), (filePath) => runValidator('--insight-status', filePath));
      assert.notEqual(result.status, 0, `validator が ${label} を見逃した`);
      assert.match(result.stderr, /insight-status/);
    });
  }

  test('window.BETA_INSIGHT_STATUS を定義しない状態ファイルを与えると validator が非0で終了する', () => {
    const result = withTempStatus('// 空\n', (filePath) => runValidator('--insight-status', filePath));
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /insight-status/);
  });
});

// ---------------------------------------------------------------- 検分指摘 R1〜R5（回帰）
describe('R1 日付のみの updatedAt はその日の終わり（JST 23:59:59.999）として比較する', () => {
  const dayFile = () => statusFile({ [ORIGINAL_ID]: { status: 'fixing', pr: '', updatedAt: '2026-11-24', note: '' } });

  test('ローカルが JST 当日内（2026-11-24T10:00:00Z = JST 19:00）ならファイル優先で fixing', () => {
    const core = loadCore();
    const { insight } = resolveOne(core, aliRecord(ORIGINAL_ID, { status: 'done', updatedAt: ms('2026-11-24T10:00:00Z') }), dayFile());
    assert.equal(insight.status, 'fixing');
    assert.equal(insight.statusOrigin, 'file');
  });

  test('ローカルが JST 翌日（2026-11-24T15:30:00Z = JST 翌 00:30）なら local-unexported', () => {
    const core = loadCore();
    const { insight } = resolveOne(core, aliRecord(ORIGINAL_ID, { status: 'done', updatedAt: ms('2026-11-24T15:30:00Z') }), dayFile());
    assert.equal(insight.status, 'done');
    assert.equal(insight.statusOrigin, 'local-unexported');
  });

  test('タイムゾーン無しの日時（2026-11-24T10:00）は不正として警告になり適用されない', () => {
    const core = loadCore();
    const file = statusFile({ [ORIGINAL_ID]: { status: 'done', updatedAt: '2026-11-24T10:00' } });
    const result = core.validateStatusFile(file);
    assert.equal(result.warnings.length, 1);
    assert.deepEqual(Object.keys(result.items), []);
  });
});

describe('R2 書き出し時、inbox の項目は pr を出力しない', () => {
  test('ファイル done/#4240 をローカルで inbox に戻した（新しい）場合、pr が無く status inbox、note は保持', () => {
    const core = loadCore();
    const file = statusFile({ [ORIGINAL_ID]: { status: 'done', pr: '#4240', updatedAt: '2026-11-24', note: '差し戻し' } });
    const records = [aliRecord(ORIGINAL_ID, { status: 'inbox', updatedAt: ms('2026-11-25T03:00:00Z') })];
    const evaluated = evaluateStatusSource(core.buildStatusFileSource(records, file, { overlayRunId: RUN_ID }));
    const item = evaluated.items[ORIGINAL_ID];
    assert.equal(item.status, 'inbox');
    assert.ok(!('pr' in item), 'inbox の項目に pr が残っている');
    assert.equal(item.note, '差し戻し');
  });
});

describe('R3 不正項目は書き出し時に元のまま保持し、ファイル全体が不正なら書き出しを拒否する（例外）', () => {
  test('未知 status の項目は warnings に載りつつ、書き出しでは元のまま保持される', () => {
    const core = loadCore();
    const broken = { status: 'closed', updatedAt: '2026-11-24', extra: 1 };
    const file = statusFile({ [ORIGINAL_ID]: { status: 'done', pr: '#4240', updatedAt: '2026-11-24', note: '' }, BROKEN: broken });
    assert.equal(core.validateStatusFile(file).warnings.length, 1);
    const evaluated = evaluateStatusSource(core.buildStatusFileSource([], file, { overlayRunId: RUN_ID }));
    assert.deepEqual(evaluated.items.BROKEN, broken);
    assert.ok(evaluated.items[ORIGINAL_ID]);
  });

  test('schemaVersion 不一致のファイルでは buildStatusFileSource が例外を投げる', () => {
    const core = loadCore();
    assert.throws(() => core.buildStatusFileSource([], statusFile({}, 2), { overlayRunId: RUN_ID }), /schemaVersion/);
  });

  test('items がオブジェクトでないファイルでは buildStatusFileSource が例外を投げる', () => {
    const core = loadCore();
    assert.throws(() => core.buildStatusFileSource([], { schemaVersion: 1, items: [] }, { overlayRunId: RUN_ID }), /items/);
  });

  test('html は書き出しを try/catch で止めて理由をトースト表示し、不正項目の残置件数を伝える', () => {
    const source = fs.readFileSync(htmlPath, 'utf8');
    const handler = source.slice(source.indexOf("querySelector('#exportInsightStatus').addEventListener"));
    const body = handler.slice(0, handler.indexOf("querySelector('#insightStatusWarnings')"));
    assert.match(body, /try\s*\{/);
    assert.match(body, /catch/);
    assert.match(body, /書き出せません|書き出しを中止/);
    assert.match(body, /不正な項目 \$\{[^}]+\}件はそのまま残しました/);
  });
});

describe('R4 サイドバー集計（気づき表示）で fixing が数えられる', () => {
  test('課題化済の集計に fixing（修正中）が含まれ、ラベルにも修正中が出る', () => {
    const source = fs.readFileSync(htmlPath, 'utf8');
    assert.match(source, /selectedView === 'insights' \? visibleInsights\.filter\([^\n]*fixing[^\n]*\)\.length/);
    assert.match(source, /selectedView === 'insights' \? '[^'\n]*修正中[^'\n]*'/);
  });
});

describe('R5 readInsights 失敗時も警告件数を更新し、__proto__ キーで汚染されない', () => {
  test('html の readInsights 失敗の catch でも状態ファイルの警告表示を更新する', () => {
    const source = fs.readFileSync(htmlPath, 'utf8');
    const tail = source.slice(source.lastIndexOf('readInsights()'));
    const catchPart = tail.slice(tail.indexOf('.catch('));
    assert.match(catchPart, /refreshInsightStatusWarnings\(\)/);
    assert.match(catchPart, /保存済みメモを読めませんでした/);
  });

  test('状態ファイルの __proto__ キーは通常のキーとして扱われ、プロトタイプを汚染しない', () => {
    const core = loadCore();
    const file = JSON.parse(`{"schemaVersion":1,"items":{"__proto__":{"status":"done","updatedAt":"2026-11-24"}}}`);
    const result = core.validateStatusFile(file);
    assert.equal(result.items.status, undefined, 'items のプロトタイプが書き換わっている');
    assert.deepEqual(Object.keys(result.items), ['__proto__']);
    assert.equal(({}).status, undefined);
    const resolved = core.resolveInsightStatuses([noteRecord('NOTE-261122-080000-000')], file, { overlayRunId: RUN_ID });
    assert.equal(resolved.insights[0].statusOrigin, 'local');
  });
});
