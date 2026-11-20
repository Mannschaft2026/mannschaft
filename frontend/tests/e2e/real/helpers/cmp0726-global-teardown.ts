import { existsSync, readFileSync, rmSync, writeFileSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { apiAs, readAssets, writeAssets, type AssetRecord } from './n1p1-world'

/**
 * CMP-261116-0726 実機E2E の台帳と後始末。
 *
 * - 台帳は本 spec 専用（cmp0726-assets.json）。n1p1 の台帳（n1p1-assets.json）とは混ぜない。
 *   spec の beforeAll が、前提作成で n1p1-world が n1p1 の台帳へ追記した分を専用台帳へ移す（moveNewAssetsToOwnLedger）。
 *   そのため本 teardown が n1p1 など他 spec の記録を削除したり、他 spec の teardown が本 spec の記録を拾ったりしない。
 * - 後始末は記録した組織・チームの DELETE のみ。利用者の再ログインによる復活や再有効化（SQL）は行わない。
 *   所有者が退会済み・削除済みでログインできない記録は、再有効化せず失敗として報告する。
 * - 実行後は成否にかかわらず台帳と世界ファイルを必ず消す（古い記録を次回の実行が拾わない）。
 *   削除できなかった記録は例外メッセージに残すので、手で確かめて後始末すること。
 * - 前提データを残して再実行したいときだけ N1P1_KEEP_WORLD=1（台帳も世界ファイルも残す）。
 */
const AUTH_DIR = path.resolve('tests', 'e2e', '.auth')
export const OWN_LEDGER = path.join(AUTH_DIR, 'cmp0726-assets.json')
export const WORLD_FILE = path.join(AUTH_DIR, 'cmp0726-world.json')

function readOwn(): AssetRecord[] {
  return existsSync(OWN_LEDGER) ? (JSON.parse(readFileSync(OWN_LEDGER, 'utf8')) as AssetRecord[]) : []
}

/** 前提作成の前に取った n1p1 台帳の写し（before）から増えた記録を、専用台帳へ移して n1p1 台帳を元に戻す。 */
export function moveNewAssetsToOwnLedger(before: AssetRecord[]): void {
  const all = readAssets()
  const added = all.slice(before.length)
  writeAssets(before)
  mkdirSync(AUTH_DIR, { recursive: true })
  writeFileSync(OWN_LEDGER, JSON.stringify([...readOwn(), ...added], null, 2))
}

export default async function globalTeardown(): Promise<void> {
  if (process.env.N1P1_KEEP_WORLD === '1') return
  const failures: string[] = []
  for (const record of [...readOwn()].reverse()) {
    try {
      const result = await apiAs(record.owner, 'DELETE', record.path)
      console.log(`[cmp0726] cleanup ${record.label} -> ${result.status}`)
      if (result.status !== 204 && result.status !== 404) failures.push(`${record.label} (${record.path}) -> ${result.status}`)
    } catch (error) {
      failures.push(`${record.label} (${record.path}) -> ${String(error)}`)
    }
  }
  rmSync(OWN_LEDGER, { force: true })
  rmSync(WORLD_FILE, { force: true })
  if (failures.length) throw new Error(`後始末に失敗した前提データが ${failures.length} 件ある（台帳は削除済み。手で確認すること）: ${failures.join(' / ')}`)
}
