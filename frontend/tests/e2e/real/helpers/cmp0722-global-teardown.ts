import { rmSync } from 'node:fs'
import path from 'node:path'
import { apiAs, readAssets } from './n1p1-world'

/**
 * CMP-261116-0722 全体検索 実機E2E の台帳と後始末。
 *
 * - 台帳は本 spec 専用（cmp0722-assets.json）。他 spec の台帳とは混ぜない。
 * - 記録した前提データ（投稿・チャンネル・スレッド・記事・支払い項目・フォルダ・ファイル・活動記録・テンプレート・
 *   チーム・組織）を記録の逆順に DELETE する。204/200/404 以外は失敗として報告する（握りつぶさない）。
 * - 実行後は成否にかかわらず台帳と世界ファイルを消す。前提データを残して再実行したいときだけ N1P1_KEEP_WORLD=1。
 * - 利用者（cmp0722-users.txt に記録）は削除 API が無いため残す（他の実機E2E と同じ扱い。報告で ID を示す）。
 */
const AUTH_DIR = path.resolve('tests', 'e2e', '.auth')
export const OWN_LEDGER = path.join(AUTH_DIR, 'cmp0722-assets.json')
export const WORLD_FILE = path.join(AUTH_DIR, 'cmp0722-world.json')

export default async function globalTeardown(): Promise<void> {
  if (process.env.N1P1_KEEP_WORLD === '1') return
  const failures: string[] = []
  for (const record of [...readAssets(OWN_LEDGER)].reverse()) {
    try {
      const result = await apiAs(record.owner, 'DELETE', record.path)
      console.log('[cmp0722] cleanup ' + record.label + ' -> ' + result.status)
      if (![200, 204, 404].includes(result.status)) failures.push(record.label + ' (' + record.path + ') -> ' + result.status + ' ' + JSON.stringify(result.body).slice(0, 200))
    } catch (error) {
      failures.push(record.label + ' (' + record.path + ') -> ' + String(error))
    }
  }
  rmSync(OWN_LEDGER, { force: true })
  rmSync(WORLD_FILE, { force: true })
  rmSync(path.join(AUTH_DIR, 'cmp0722-admin-hits.json'), { force: true })
  if (failures.length) throw new Error('後始末に失敗した前提データが ' + failures.length + ' 件ある（台帳は削除済み。手で確認すること）: ' + failures.join(' / '))
}
