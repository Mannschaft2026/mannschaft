import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// CMP-261121-1114: Task-List PR Gate の merge queue（merge_group）対応の静的検査。
// 必須チェック名 "Task-List PR Gate" が merge_group でも報告されること、merge_group 経路が
// 信頼 base（merge_group.base_sha）の検査器だけを使うこと、pull_request_target 経路が不変であることを固定する。

const workflowPath = fileURLToPath(new URL('../.github/workflows/task-list-pr-gate.yml', import.meta.url));
const workflow = readFileSync(workflowPath, 'utf8').replace(/\r\n/g, '\n');

function topLevelBlock(key) {
  const lines = workflow.split('\n');
  const start = lines.findIndex((line) => line === key + ':');
  assert.notEqual(start, -1, key + ': ブロックが無い');
  const body = [];
  for (const line of lines.slice(start + 1)) {
    if (/^\S/.test(line)) break;
    body.push(line);
  }
  return body.join('\n');
}

function steps() {
  const jobs = topLevelBlock('jobs');
  const stepsAt = jobs.indexOf('\n    steps:\n');
  assert.notEqual(stepsAt, -1, 'steps が無い');
  return jobs.slice(stepsAt).split(/\n(?=      - name: )/).slice(1).map((block) => ({
    block,
    name: block.match(/^      - name: (.+)$/m)[1].trim(),
    if: block.match(/^        if: (.+)$/m)?.[1].trim() ?? null,
    uses: block.match(/^        uses: (.+)$/m)?.[1].trim() ?? null,
    ref: block.match(/^          ref: (.+)$/m)?.[1].trim() ?? null,
    path: block.match(/^          path: (.+)$/m)?.[1].trim() ?? null,
  }));
}

const isMergeGroupOnly = (step) => /^\$\{\{\s*github\.event_name == 'merge_group'\s*\}\}$|^github\.event_name == 'merge_group'$/.test(step.if ?? '');
const isNonMergeGroupOnly = (step) => /^\$\{\{\s*github\.event_name != 'merge_group'\s*\}\}$|^github\.event_name != 'merge_group'$/.test(step.if ?? '');

test('AC-1/2: on: に merge_group（checks_requested）がある', () => {
  const on = topLevelBlock('on');
  assert.match(on, /^  merge_group:\n    types: \[checks_requested\]$/m);
});

test('AC-10: pull_request_target と workflow_dispatch の起動条件・権限は従来どおり', () => {
  const on = topLevelBlock('on');
  assert.match(on, /^  pull_request_target:\n    branches: \[main\]\n    types: \[opened, synchronize, reopened, ready_for_review, edited\]$/m);
  assert.match(on, /^  workflow_dispatch:$/m);
  assert.equal(topLevelBlock('permissions'), '  contents: read\n  actions: read\n  checks: write\n  pull-requests: read\n');
});

test('AC-1/2: ジョブは1つで、名前は必須チェック名 "Task-List PR Gate"（skip された同名ジョブを作らない）', () => {
  const jobs = topLevelBlock('jobs');
  const jobKeys = jobs.split('\n').filter((line) => /^  [A-Za-z0-9_-]+:$/.test(line));
  assert.equal(jobKeys.length, 1, '同名チェックが skipped で二重に報告されないよう、ジョブは1つに保つ: ' + jobKeys.join(','));
  assert.match(jobs, /^    name: Task-List PR Gate$/m);
  assert.doesNotMatch(jobs, /^    if:/m, 'ジョブ単位の if で skip すると必須チェックが skipped で報告される');
});

test('AC-10: 既存の pull_request_target 経路の step は merge_group で走らず、checkout は従来どおり base.sha', () => {
  const all = steps();
  const baseCheckout = all.find((step) => step.name === 'Checkout trusted base');
  assert.ok(baseCheckout, '既存の Checkout trusted base が無い');
  assert.ok(isNonMergeGroupOnly(baseCheckout), 'Checkout trusted base の if: ' + baseCheckout.if);
  assert.match(baseCheckout.ref, /^\$\{\{ github\.event\.pull_request\.base\.sha \|\| github\.sha \}\}$/);
  assert.match(baseCheckout.block, /persist-credentials: false/);
  const gate = all.find((step) => step.name === 'Validate registration and completion evidence');
  assert.ok(gate, '既存の Validate registration and completion evidence が無い');
  assert.ok(isNonMergeGroupOnly(gate), 'Validate registration and completion evidence の if: ' + gate.if);
  assert.match(gate.block, /const pull = context\.payload\.pull_request;\n\s+if \(!pull\) throw new Error\('pull_request payload is missing'\);/);
  for (const step of all) assert.ok(isMergeGroupOnly(step) || isNonMergeGroupOnly(step), 'event_name で分岐していない step: ' + step.name);
});

test('AC-12: merge_group 経路は検査器を base_sha から、台帳だけを head_sha から取得する', () => {
  const mergeGroupSteps = steps().filter(isMergeGroupOnly);
  assert.ok(mergeGroupSteps.length >= 3, 'merge_group 経路の step が足りない');
  const checkouts = mergeGroupSteps.filter((step) => /^actions\/checkout@/.test(step.uses ?? ''));
  const rootCheckouts = checkouts.filter((step) => step.path === null);
  assert.equal(rootCheckouts.length, 1, 'ワークスペース直下へ checkout するのは信頼 base の1回だけ');
  assert.match(rootCheckouts[0].ref, /^\$\{\{ github\.event\.merge_group\.base_sha \}\}$/);
  const headCheckouts = checkouts.filter((step) => /merge_group\.head_sha/.test(step.ref ?? ''));
  assert.equal(headCheckouts.length, 1, 'head_sha の取得は1回だけ');
  const head = headCheckouts[0];
  assert.notEqual(head.path, null, 'head_sha をワークスペース直下へ checkout してはならない');
  assert.match(head.block, /sparse-checkout: \|\n\s+docs\/task-list\.md\n/);
  assert.match(head.block, /sparse-checkout-cone-mode: false/);
  for (const step of checkouts) assert.match(step.block, /persist-credentials: false/);
  assert.ok(checkouts.indexOf(rootCheckouts[0]) < checkouts.indexOf(head), 'base を先に checkout してから head を別ディレクトリへ取得する');
  const run = mergeGroupSteps.find((step) => /node scripts\/task-list-structure-check\.mjs/.test(step.block));
  assert.ok(run, 'merge_group 経路で検査器を実行していない');
  assert.ok(run.block.includes(head.path + '/docs/task-list.md'), '検査対象は head から取得した台帳であること');
  assert.doesNotMatch(run.block, new RegExp('node \\S*' + head.path.replace(/[.*+?^$(){}|[\]\\]/g, '\\$&') + '/scripts'), 'head 側の検査器を実行してはならない');
  assert.doesNotMatch(mergeGroupSteps.map((step) => step.block).join('\n'), /github-script|context\.payload\.pull_request/, 'merge_group 経路で PR 本文検査を行わない');
});
