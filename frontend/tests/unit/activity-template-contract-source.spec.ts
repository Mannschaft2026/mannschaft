import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/**
 * 活動テンプレート画面の FE 契約（AC-74〜76 / F06.4.1 先行修正）をソースで固定する。
 * マウントを伴わないので Nuxt 環境の起動を要さず、既定の timeout で実行できる。
 */
const here = dirname(fileURLToPath(import.meta.url))
const app = resolve(here, '../../app')
const read = (p: string) => readFileSync(resolve(app, p), 'utf-8').replace(/\r\n/g, '\n')

describe('活動一覧ヘッダーのテンプレートリンク（AC-76）', () => {
  const src = read('pages/teams/[slug]/activities.vue')

  it('ADMIN / DEPUTY（isAdminOrDeputy）のときだけ #actions を出す', () => {
    expect(src).toContain('isAdminOrDeputy')
    expect(src).toMatch(/<template v-if="isAdminOrDeputy" #actions>/)
  })

  it('slug の URL（/teams/{slug}/activity-templates）へ遷移する', () => {
    expect(src).toContain('/teams/${teamSlug}/activity-templates')
  })
})

describe('テンプレート編集フォームの fieldKey 契約（AC-74・75）', () => {
  const src = read('components/activity/ActivityTemplateForm.vue')

  it('保存済み項目の fieldKey・unit・placeholder を保持して送る', () => {
    expect(src).toContain('fieldKey: f.fieldKey')
    expect(src).toContain('unit: f.unit')
    expect(src).toContain('placeholder: f.placeholder')
  })

  it('BE の送信名（fieldLabel・isRequired・optionsJson）で送る', () => {
    expect(src).toContain('fieldLabel: f.label')
    expect(src).toContain('isRequired: f.required')
    expect(src).toContain('optionsJson:')
  })

  it('新規 key は f_ + 16進12桁で、既存・入力中のキーと衝突したら引き直す', () => {
    expect(src).toContain("'f_' +")
    expect(src).toContain('new Uint8Array(6)')
    expect(src).toContain('taken.has(key)')
  })

  it('削除は removedFieldKeys で明示する', () => {
    expect(src).toContain('removedFieldKeys')
  })

  it('照合漏れの案内と、確認付きの再読込を出す', () => {
    expect(src).toContain('staleError')
    expect(src).toContain('confirm.require')
    expect(src).toContain('badKeyRows')
  })
})
