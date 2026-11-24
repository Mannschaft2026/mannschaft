import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

// backend-deploy / frontend-deploy の aws-check step（CMP-260826-2129）の回帰テスト。
// 鍵を設定した時点で空成功が緑のまま残らないよう、
//   全欠如 → skip（緑・job summary に未実行を明記） / 一部欠如 → 赤 / 全設定 → deploy
// を保つ。workflow の run スクリプトを取り出して bash で実際に実行して検証する。
// あわせて、検査対象が後段 step の参照する secrets を網羅していることを検査する。

const repository = fileURLToPath(new URL('../', import.meta.url));
const read = (path) => readFileSync(join(repository, path), 'utf8').replace(/\r\n/g, '\n');

const WORKFLOWS = ['.github/workflows/backend-deploy.yml', '.github/workflows/frontend-deploy.yml'];
const ALL = [
  'AWS_ACCESS_KEY_ID',
  'AWS_SECRET_ACCESS_KEY',
  'ECR_REPOSITORY',
  'ECS_CLUSTER',
  'ECS_SERVICE',
  'ECS_TASK_DEFINITION',
  'ECS_CONTAINER_NAME',
];

const STEP_HEAD = '- name: Check AWS credentials';

/** `Check AWS credentials` step の開始行番号と行群を返す。 */
function awsCheckStep(text) {
  const lines = text.split('\n');
  const start = lines.findIndex((l) => l.trim() === STEP_HEAD);
  assert.ok(start >= 0, 'Check AWS credentials step が無い');
  const indent = lines[start].indexOf('-');
  const block = [lines[start]];
  for (const line of lines.slice(start + 1)) {
    if (line.trim() !== '' && line.search(/\S/) <= indent) break;
    block.push(line);
  }
  return { start, block };
}

/** step 内の `run: |` スクリプトを字下げを除いて取り出す。 */
function runScript(block) {
  const i = block.findIndex((l) => l.trim() === 'run: |');
  assert.ok(i >= 0, 'run: | が無い');
  const baseIndent = block[i].search(/\S/);
  const body = [];
  for (const line of block.slice(i + 1)) {
    if (line.trim() !== '' && line.search(/\S/) <= baseIndent) break;
    body.push(line);
  }
  const min = Math.min(...body.filter((l) => l.trim()).map((l) => l.search(/\S/)));
  return body.map((l) => l.slice(min)).join('\n');
}

function execute(script, setNames) {
  const dir = mkdtempSync(join(tmpdir(), 'aws-check-'));
  const out = join(dir, 'output');
  const summary = join(dir, 'summary');
  writeFileSync(out, '');
  writeFileSync(summary, '');
  const env = { PATH: process.env.PATH, GITHUB_OUTPUT: out, GITHUB_STEP_SUMMARY: summary };
  for (const name of ALL) env[`SECRET_${name}`] = setNames.includes(name) ? `value-of-${name}-xyz` : '';
  const r = spawnSync('bash', ['-c', script], { env, encoding: 'utf8' });
  return {
    status: r.status,
    log: `${r.stdout}${r.stderr}`,
    output: readFileSync(out, 'utf8'),
    summary: readFileSync(summary, 'utf8'),
  };
}

for (const file of WORKFLOWS) {
  const text = read(file);
  const { start, block } = awsCheckStep(text);
  const script = runScript(block);

  test(`${file}: 全 secrets 未設定なら skip（緑）し、job summary に未実行を明記する`, () => {
    const r = execute(script, []);
    assert.equal(r.status, 0);
    assert.match(r.output, /^skip=true$/m);
    assert.match(r.summary, /デプロイ未実行/);
    assert.match(r.summary, /緑はデプロイ成功を意味しない/);
  });

  test(`${file}: 一部だけ設定なら失敗し、未設定の名前だけを列挙する（値は出さない）`, () => {
    for (const missing of ALL) {
      const r = execute(
        script,
        ALL.filter((n) => n !== missing),
      );
      assert.notEqual(r.status, 0, `${missing} 欠如で赤になっていない`);
      assert.match(r.log, /::error::/);
      assert.ok(r.log.includes(`未設定: ${missing}`), `${missing} が列挙されていない`);
      assert.ok(!r.log.includes('value-of-'), 'secret の値がログに出ている');
    }
    // AWS_ACCESS_KEY_ID だけ空で他が設定済みでも検知する（旧 backend の抜け穴）
    assert.notEqual(execute(script, ALL.slice(1)).status, 0);
  });

  test(`${file}: 全 secrets 設定済みなら deploy へ進む`, () => {
    const r = execute(script, ALL);
    assert.equal(r.status, 0);
    assert.match(r.output, /^skip=false$/m);
    assert.equal(r.summary, '');
  });

  test(`${file}: 検査対象が後段 step の参照する secrets を網羅している`, () => {
    const after = text
      .split('\n')
      .filter((l, i) => i >= start + block.length && !l.trim().startsWith('#'))
      .join('\n');
    const referenced = new Set([...after.matchAll(/secrets\.([A-Z0-9_]+)/g)].map((m) => m[1]));
    assert.ok(referenced.size > 0);
    const envBlock = block.join('\n');
    for (const name of referenced) {
      assert.ok(script.includes(name), `後段が参照する secret ${name} が aws-check の検査対象に無い`);
      assert.ok(envBlock.includes(`SECRET_${name}: \${{ secrets.${name} }}`), `${name} が env に渡されていない`);
    }
  });
}
