// 気づき箱の状態管理コア（CMP-261124-2306）。副作用のない純関数だけを置く。
// ブラウザでは window.InsightStatusCore、node（テスト・validator）では vm の { window: {} } で評価して使う。
(function (root) {
  'use strict';

  const STATUSES = ['inbox', 'planned', 'fixing', 'done'];
  const STATUS_LABELS = { inbox: '未整理', planned: '課題化済', fixing: '修正中', done: '対応済' };
  const SCHEMA_VERSION = 1;
  const ISO_PATTERN = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?(?:Z|[+-]\d{2}:\d{2}))?$/;

  function sanitizeRunId(runId) {
    return String(runId).replace(/[^A-Za-z0-9_-]/g, '-');
  }

  function isPlainObject(value) {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
  }

  // ISO 8601（日付のみ、またはタイムゾーン付きの日時）だけを受け付け、時刻（ms）を返す。不正なら null。
  function parseIsoTime(value) {
    if (typeof value !== 'string') return null;
    const match = ISO_PATTERN.exec(value);
    if (!match) return null;
    const year = Number(match[1]);
    const month = Number(match[2]);
    const day = Number(match[3]);
    if (month < 1 || month > 12 || day < 1) return null;
    if (day > new Date(Date.UTC(year, month, 0)).getUTCDate()) return null;
    if (match[4] !== undefined) {
      if (Number(match[4]) > 23 || Number(match[5]) > 59) return null;
      if (match[6] !== undefined && Number(match[6]) > 59) return null;
    }
    // 日付のみはその日の終わり（日本時間 23:59:59.999）として扱う。タイムゾーン無しの日時は上の正規表現で不正にしている
    const time = match[4] === undefined ? Date.parse(`${value}T23:59:59.999+09:00`) : Date.parse(value);
    return Number.isNaN(time) ? null : time;
  }

  function nextStatus(status) {
    const index = STATUSES.indexOf(status);
    return STATUSES[(index + 1) % STATUSES.length];
  }

  function statusToTarget(status) {
    if (status === 'done') return 'ready';
    if (status === 'planned' || status === 'fixing') return 'working';
    return 'unknown';
  }

  function originalInsightId(record, overlayRunId) {
    if (!record || record.registrationSource !== 'alicization' || typeof record.id !== 'string') return null;
    if (overlayRunId === undefined || overlayRunId === null || overlayRunId === '') return null;
    const prefix = `ALI-${sanitizeRunId(overlayRunId)}-`;
    if (!record.id.startsWith(prefix) || record.id.length === prefix.length) return null;
    return record.id.slice(prefix.length);
  }

  function validateStatusFile(file) {
    if (file === undefined) return { items: {}, warnings: [] };
    if (!isPlainObject(file)) return { items: {}, warnings: [{ key: '(file)', reason: '状態ファイルがオブジェクトではありません' }] };
    if (file.schemaVersion !== SCHEMA_VERSION) {
      return { items: {}, warnings: [{ key: '(file)', reason: `schemaVersion が ${SCHEMA_VERSION} ではありません（${JSON.stringify(file.schemaVersion)}）。ファイル全体を適用しません` }] };
    }
    if (!isPlainObject(file.items)) return { items: {}, warnings: [{ key: '(file)', reason: 'items がオブジェクトではありません' }] };
    const items = Object.create(null);
    const warnings = [];
    for (const [key, entry] of Object.entries(file.items)) {
      if (!isPlainObject(entry)) {
        warnings.push({ key, reason: '項目がオブジェクトではありません' });
      } else if (!STATUSES.includes(entry.status)) {
        warnings.push({ key, reason: `status が未知の値です（${JSON.stringify(entry.status)}）。${STATUSES.join('・')} のいずれかにしてください` });
      } else if (parseIsoTime(entry.updatedAt) === null) {
        warnings.push({ key, reason: `updatedAt が ISO 8601 の日付・日時ではありません（${JSON.stringify(entry.updatedAt)}）` });
      } else if (entry.pr !== undefined && typeof entry.pr !== 'string') {
        warnings.push({ key, reason: 'pr は文字列にしてください' });
      } else if (entry.note !== undefined && typeof entry.note !== 'string') {
        warnings.push({ key, reason: 'note は文字列にしてください' });
      } else {
        items[key] = entry;
      }
    }
    return { items, warnings };
  }

  // 完全一致（ボード id）を優先し、無ければ住民 overlay の元 ID で状態ファイルの項目を探す。
  function findEntry(record, items, overlayRunId) {
    if (Object.prototype.hasOwnProperty.call(items, record.id)) return { key: record.id, entry: items[record.id] };
    const original = originalInsightId(record, overlayRunId);
    if (original !== null && Object.prototype.hasOwnProperty.call(items, original)) return { key: original, entry: items[original] };
    return null;
  }

  // updatedAt が createdAt と同じ記録は取り込み・作成以降に状態を動かしていない。取り込み時刻が状態ファイルより新しいだけで
  // 「ローカルの変更」と誤認しないよう、状態を動かした記録（updatedAt が createdAt と異なる）だけをローカル優先の候補にする。
  function isLocalNewer(record, entry) {
    return Number(record.updatedAt) !== Number(record.createdAt)
      && Number(record.updatedAt) > parseIsoTime(entry.updatedAt)
      && record.status !== entry.status;
  }

  function resolveInsightStatuses(records, file, options) {
    const overlayRunId = options && options.overlayRunId;
    const { items, warnings } = validateStatusFile(file);
    const insights = (records || []).map((record) => {
      const found = findEntry(record, items, overlayRunId);
      if (!found) return { ...record, statusOrigin: 'local', statusKey: null, pr: record.pr || '', note: record.note || '' };
      const localWins = isLocalNewer(record, found.entry);
      return {
        ...record,
        status: localWins ? record.status : found.entry.status,
        statusOrigin: localWins ? 'local-unexported' : 'file',
        statusKey: found.key,
        pr: found.entry.pr || '',
        note: found.entry.note || ''
      };
    });
    return { insights, warnings };
  }

  function cleanEntry(entry) {
    const result = { status: entry.status };
    // 未整理へ戻した項目に古い PR を残さない
    if (entry.pr !== undefined && entry.status !== 'inbox') result.pr = entry.pr;
    result.updatedAt = entry.updatedAt;
    if (entry.note !== undefined) result.note = entry.note;
    return result;
  }

  // 状態ファイルを書き出す。ファイル全体が不正（schemaVersion 不一致・items 不正など）なら Error を投げて書き出しを拒否する。
  // 不正な項目は黙って消さず、元のまま出力に残す（警告は validateStatusFile で確認できる）。
  function buildStatusFileSource(records, file, options) {
    const overlayRunId = options && options.overlayRunId;
    const validated = validateStatusFile(file);
    const fileLevel = validated.warnings.find((warning) => warning.key === '(file)');
    if (fileLevel) throw new Error(`状態ファイルが不正なため書き出せません: ${fileLevel.reason}`);
    const items = Object.create(null);
    if (file !== undefined) {
      for (const [key, entry] of Object.entries(file.items)) {
        items[key] = Object.prototype.hasOwnProperty.call(validated.items, key) ? cleanEntry(entry) : entry;
      }
    }
    for (const record of records || []) {
      const found = findEntry(record, validated.items, overlayRunId);
      const stamp = Number.isFinite(Number(record.updatedAt)) ? new Date(Number(record.updatedAt)).toISOString() : null;
      if (found) {
        if (stamp !== null && isLocalNewer(record, found.entry)) {
          items[found.key] = cleanEntry({ ...found.entry, status: record.status, updatedAt: stamp });
        }
      } else if (STATUSES.includes(record.status) && record.status !== 'inbox' && stamp !== null) {
        const newKey = originalInsightId(record, overlayRunId) || record.id;
        // 同じキーに不正項目がある場合は上書きせず元のまま残す
        if (!Object.prototype.hasOwnProperty.call(items, newKey)) items[newKey] = { status: record.status, pr: '', updatedAt: stamp, note: '' };
      }
    }
    const sorted = Object.create(null);
    for (const key of Object.keys(items).sort()) sorted[key] = items[key];
    return `window.BETA_INSIGHT_STATUS = ${JSON.stringify({ schemaVersion: SCHEMA_VERSION, items: sorted }, null, 2)};\n`;
  }

  root.InsightStatusCore = {
    STATUSES,
    STATUS_LABELS,
    nextStatus,
    statusToTarget,
    originalInsightId,
    validateStatusFile,
    resolveInsightStatuses,
    buildStatusFileSource
  };
})(typeof window !== 'undefined' ? window : globalThis);
