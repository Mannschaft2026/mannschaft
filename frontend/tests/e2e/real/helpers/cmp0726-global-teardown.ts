import { rmSync } from 'node:fs'
import path from 'node:path'
import n1p1Teardown from './n1p1-global-teardown'

/**
 * CMP-261116-0726 実機E2E の後始末。記録した組織・チームを API で削除したあと（n1p1-global-teardown と共通）、
 * 前提データの再利用用に spec が書いた世界ファイルを消す（削除済みのデータを次回の実行が拾わないため）。
 * 前提データを残して再実行したいときだけ N1P1_KEEP_WORLD=1（世界ファイルも残す）。
 */
export default async function globalTeardown(): Promise<void> {
  if (process.env.N1P1_KEEP_WORLD === '1') return
  await n1p1Teardown()
  rmSync(path.resolve('tests', 'e2e', '.auth', 'cmp0726-world.json'), { force: true })
}
