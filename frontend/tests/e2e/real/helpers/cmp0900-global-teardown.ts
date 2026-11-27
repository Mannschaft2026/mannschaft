import { rmSync } from 'node:fs'
import path from 'node:path'
import { apiAs, readAssets, type AssetRecord } from './n1p1-world'

/**
 * CMP-261118-0900 実機E2E の台帳と後始末（cmp0726-global-teardown.ts と同じ流儀。台帳は本 spec 専用）。
 * 後始末は台帳（作成の逆順）の DELETE。組織の論理削除は大会を消さないため、大会も作成直後に台帳へ載せてあり、
 * 組織・チームより先に DELETE される（連絡スペースは大会削除で archive）。
 * 利用者は再有効化・削除しない（テスト専用ドメインのアドレスで残る）。
 * 前提データを残して再実行したいときだけ N1P1_KEEP_WORLD=1。
 */
const AUTH_DIR = path.resolve('tests', 'e2e', '.auth')
export const OWN_LEDGER = path.join(AUTH_DIR, 'cmp0900-assets.json')
export const WORLD_FILE = path.join(AUTH_DIR, 'cmp0900-world.json')

export default async function globalTeardown(): Promise<void> {
  if (process.env.N1P1_KEEP_WORLD === '1') return
  const failures: string[] = []
  const records: AssetRecord[] = readAssets(OWN_LEDGER)
  for (const record of [...records].reverse()) {
    try {
      const result = await apiAs(record.owner, 'DELETE', record.path)
      console.log(`[cmp0900] cleanup ${record.label} -> ${result.status}`)
      if (result.status !== 204 && result.status !== 404) failures.push(`${record.label} (${record.path}) -> ${result.status}`)
    } catch (error) {
      failures.push(`${record.label} (${record.path}) -> ${String(error)}`)
    }
  }
  rmSync(OWN_LEDGER, { force: true })
  rmSync(WORLD_FILE, { force: true })
  if (failures.length) throw new Error(`後始末に失敗した前提データが ${failures.length} 件ある（台帳は削除済み。手で確認すること）: ${failures.join(' / ')}`)
}
