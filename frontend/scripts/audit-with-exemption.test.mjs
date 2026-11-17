import assert from 'node:assert/strict'
import { test } from 'node:test'
import { checkAudit } from './audit-with-exemption.mjs'

const URL = 'https://github.com/advisories/GHSA-vfj7-8cjw-p6xm'
const advisory = (severity = 'high', url = URL) => ({
  name: 'braces',
  dependency: 'braces',
  severity,
  url,
  range: '<=3.0.3',
})
const entry = (name, via, severity = 'high') => ({
  name,
  severity,
  isDirect: false,
  via,
  nodes: [`node_modules/${name}`],
})
function report(vulnerabilities = {}) {
  const counts = {
    info: 0,
    low: 0,
    moderate: 0,
    high: 0,
    critical: 0,
    total: 0,
  }
  for (const item of Object.values(vulnerabilities)) {
    counts[item.severity]++
    counts.total++
  }
  return {
    auditReportVersion: 2,
    vulnerabilities,
    metadata: { vulnerabilities: counts },
  }
}
const highReport = () => report({ braces: entry('braces', [advisory()]) })
const check = (
  value,
  status = value?.metadata?.vulnerabilities.high + value?.metadata?.vulnerabilities.critical > 0
    ? 1
    : 0,
) => checkAudit(JSON.stringify(value), status)

test('指摘0件と閾値未満の指摘は通る', () => {
  check(report(), 0)
  check(report({ small: entry('small', [advisory('moderate')], 'moderate') }))
})
for (const severity of ['high', 'critical']) {
  test(`${severity} advisory は個別除外なしで拒否する`, () => {
    const value = report({ braces: entry('braces', [advisory(severity)], severity) })
    assert.throws(() => check(value), /許可されない脆弱性/)
  })
}
test('間接参照が到達するhighは低severity報告でも拒否する', () => {
  const value = report({
    braces: entry('braces', [advisory()]),
    micromatch: entry('micromatch', ['braces'], 'moderate'),
  })
  assert.throws(() => check(value), /許可されない脆弱性|深刻度が不一致/)
})
test('直接advisoryの深刻度を過小報告すると拒否する', () => {
  const value = report({ braces: entry('braces', [advisory()], 'moderate') })
  assert.throws(() => check(value), /深刻度が不一致/)
})

test('不正JSON・構造欠落・error・npm異常終了は拒否する', () => {
  assert.throws(() => checkAudit('{', 1))
  for (const value of [
    {},
    null,
    [],
    { ...highReport(), error: { code: 'ENETUNREACH' } },
    { ...highReport(), vulnerabilities: [] },
    { ...highReport(), auditReportVersion: 1 },
  ]) {
    assert.throws(() => check(value))
  }
  for (const status of [null, 2, 127]) assert.throws(() => check(highReport(), status), /取得失敗/)
  assert.throws(() => check(highReport(), 0), /不一致/)
  assert.throws(() => check(report(), 1), /不一致/)
})
test('参照欠落・末端のない循環・空via・unknown severityを拒否する', () => {
  for (const value of [
    report({ unknown: entry('unknown', ['missing']) }),
    report({ a: entry('a', ['b']), b: entry('b', ['a']) }),
    report({ unknown: entry('unknown', []) }),
    report({ unknown: entry('unknown', [advisory()], 'new-severity') }),
    report({ unknown: entry('unknown', [null]) }),
  ]) {
    assert.throws(() => check(value))
  }
})
test('レポート集計と依存情報の不一致を拒否する', () => {
  const value = highReport()
  value.metadata.vulnerabilities.high = 0
  assert.throws(() => check(value), /不一致/)
})

const PICOMATCH_URL = 'https://github.com/advisories/GHSA-fc5q-39xc-vjpf'
const picomatchReport = () =>
  report({
    picomatch: entry('picomatch', [{ ...advisory('high', PICOMATCH_URL), name: 'picomatch' }]),
    tinyglobby: entry('tinyglobby', ['picomatch']),
  })
test('期限内は除外GHSA（picomatch）だけで high になっている entry を通す', () => {
  checkAudit(JSON.stringify(picomatchReport()), 1, Date.parse('2026-11-18T00:00:00Z'))
})
test('除外の期限切れ後は picomatch も拒否する', () => {
  assert.throws(
    () => checkAudit(JSON.stringify(picomatchReport()), 1, Date.parse('2026-12-02T00:00:00Z')),
    /許可されない脆弱性/,
  )
})
test('除外GHSAに到達しても別の high advisory が混ざれば拒否する', () => {
  const value = report({
    picomatch: entry('picomatch', [{ ...advisory('high', PICOMATCH_URL), name: 'picomatch' }]),
    braces: entry('braces', [advisory()]),
    nuxt: entry('nuxt', ['picomatch', 'braces']),
  })
  assert.throws(
    () => checkAudit(JSON.stringify(value), 1, Date.parse('2026-11-18T00:00:00Z')),
    /許可されない脆弱性/,
  )
})

test('high entry が除外GHSAに到達せず moderate advisory だけを参照する場合は期限内・期限後とも拒否する', () => {
  const value = report({ small: entry('small', [advisory('moderate')], 'high') })
  for (const at of ['2026-11-18T00:00:00Z', '2026-12-02T00:00:00Z']) {
    assert.throws(() => checkAudit(JSON.stringify(value), 1, Date.parse(at)), /許可されない脆弱性|深刻度/)
  }
})
