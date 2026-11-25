// CMP-261107-1833 PR3（再招待依頼の受け手側）: 6 言語の文言と導線の配置（U1・R4・N3・N4・k・j）。試練。
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const APP = resolve(__dirname, '../../../app')
const LOCALES = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const JA_CHARS = /[\p{Script=Hiragana}\p{Script=Katakana}]/u

function json(locale: string, file: string): Record<string, unknown> {
  return JSON.parse(readFileSync(resolve(APP, 'locales', locale, file + '.json'), 'utf8'))
}
function pick(obj: Record<string, unknown>, path: string): unknown {
  return path.split('.').reduce<unknown>((o, k) => (o && typeof o === 'object' ? (o as Record<string, unknown>)[k] : undefined), obj)
}
function source(rel: string) {
  return readFileSync(resolve(APP, rel), 'utf8')
}

const REISSUE_KEYS = ['title', 'description', 'empty', 'requested_at', 'role', 'reissue', 'decline', 'decline_confirm_title',
  'decline_confirm_message', 'reissued', 'already_member', 'declined', 'already_handled', 'requester_unavailable',
  'activation_failed', 'forbidden', 'load_error', 'retry', 'load_more', 'reissue_context', 'decline_context']

describe('U1: 6 言語の文言', () => {
  for (const locale of LOCALES) {
    it(`IRL-${locale}: common の inviteReissue.* がそろい、ja 以外に日本語（かな）が残らない`, () => {
      const common = json(locale, 'common')
      for (const key of REISSUE_KEYS) {
        const value = pick(common, 'inviteReissue.' + key)
        expect(typeof value === 'string' && value.length > 0, `${locale} inviteReissue.${key}`).toBe(true)
        if (locale !== 'ja') expect(String(value), `${locale} inviteReissue.${key}`).not.toMatch(JA_CHARS)
      }
      expect(String(pick(common, 'inviteReissue.reissued'))).toContain('{name}')
    })

    it(`IRL-${locale}-admin: adminConsole.lens.approvals.domain.INVITE_REISSUE がある`, () => {
      const value = pick(json(locale, 'admin_console'), 'adminConsole.lens.approvals.domain.INVITE_REISSUE')
      expect(typeof value === 'string' && value.length > 0).toBe(true)
      if (locale !== 'ja') expect(String(value)).not.toMatch(JA_CHARS)
    })

    it(`IRL-${locale}-auth: auth.pending_invite.title_reissue_declined / reissue_declined_desc がある`, () => {
      const auth = json(locale, 'auth')
      for (const key of ['title_reissue_declined', 'reissue_declined_desc']) {
        const value = pick(auth, 'auth.pending_invite.' + key)
        expect(typeof value === 'string' && value.length > 0, `${locale} ${key}`).toBe(true)
        if (locale !== 'ja') expect(String(value), `${locale} ${key}`).not.toMatch(JA_CHARS)
      }
    })
  }

  it('IRL-ja-01: ja の依頼済みの文言は「新しい招待が出たら通知とこの画面でお知らせします」に言い直す', () => {
    expect(String(pick(json('ja', 'auth'), 'auth.pending_invite.reissue_requested')))
      .toContain('新しい招待が出たら通知とこの画面でお知らせします')
  })

  it('IRL-ja-02: ja の pending_desc に余分な空白が無い（差し込み {name}・{role} の前後に空白を置かない）', () => {
    const value = String(pick(json('ja', 'auth'), 'auth.pending_invite.pending_desc'))
    expect(value).not.toMatch(/\} | \{/)
    expect(value).toContain('{name}')
    expect(value).toContain('{role}')
  })
})

describe('導線の配置（j・R4・N3）', () => {
  it('IRL-10: teams と organizations の invites.vue の最上段（InviteTokenList より前）に InviteReissueRequestPanel を置き、'
    + 'canManageInviteReissue（ADMIN、または INVITE_MEMBERS を持つ DEPUTY）で出し分ける', () => {
    for (const rel of ['pages/teams/[slug]/invites.vue', 'pages/organizations/[slug]/invites.vue']) {
      const page = source(rel)
      const panel = page.indexOf('<InviteReissueRequestPanel')
      expect(panel, rel).toBeGreaterThanOrEqual(0)
      expect(panel, rel).toBeLessThan(page.indexOf('<InviteTokenList'))
      expect(page, rel).toContain('canManageInviteReissue')
    }
  })

  it('IRL-11: components/invite/InviteReissueRequestPanel.vue と composables/useInviteReissueRequests.ts がある', () => {
    expect(existsSync(resolve(APP, 'components/invite/InviteReissueRequestPanel.vue'))).toBe(true)
    expect(existsSync(resolve(APP, 'composables/useInviteReissueRequests.ts'))).toBe(true)
  })

  it('IRL-12 N3/N4: 承認待ちのドメイン型（types と usePersonalAdminActionRequired の union）に INVITE_REISSUE を含める', () => {
    expect(source('types/admin-action-required.ts')).toMatch(/AdminActionDomain\s*=[^\n]*'INVITE_REISSUE'/)
    expect(source('composables/usePersonalAdminActionRequired.ts')).toMatch(/domain:\s*[^\n]*'INVITE_REISSUE'/)
  })
})
