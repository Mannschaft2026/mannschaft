// @vitest-environment node
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

/**
 * CMP-261117-2159 AC-10: 未ログイン導線（forgot-password / verify-email）と回数券トーストの追加キーが
 * 6 言語すべてに在り、ja 以外に日本語が残らず、プレースホルダが揃っていること。
 */
const localesDir = dirname(fileURLToPath(import.meta.url))
const LOCALES = ['ja', 'en', 'de', 'zh', 'ko', 'es'] as const
const JAPANESE = /[぀-ヿ]/

const KEYS: Record<string, string[]> = {
  auth: [
    'auth.password_reset.request_description',
    'auth.password_reset.request_submit',
    'auth.password_reset.request_done',
    'auth.verify_email.verifying',
    'auth.verify_email.success_title',
    'auth.verify_email.success_description',
    'auth.verify_email.to_login',
    'auth.verify_email.failed_title',
    'auth.verify_email.failed_description',
    'auth.verify_email.failed_toast_title',
    'auth.verify_email.resend_button',
    'auth.verify_email.resend_success',
    'auth.verify_email.resend_failed_title',
    'auth.verify_email.resend_failed_message',
    'auth.verify_email.success_toast',
    'auth.verify_email.sent_title',
    'auth.verify_email.sent_description',
    'auth.verify_email.sent_description_to',
    'auth.verify_email.back_to_login',
  ],
  payment: ['payment.ticket.create_success'],
}

function load(locale: string, file: string): Record<string, unknown> {
  return JSON.parse(readFileSync(resolve(localesDir, locale, `${file}.json`), 'utf8').replace(/^﻿/, ''))
}
function pick(doc: Record<string, unknown>, key: string): unknown {
  return key.split('.').reduce<unknown>((o, k) => (o as Record<string, unknown> | undefined)?.[k], doc)
}

describe('CMP-261117-2159 AC-10: 追加キーが6言語に在る', () => {
  for (const [file, keys] of Object.entries(KEYS)) {
    for (const locale of LOCALES) {
      it(`${locale}/${file}.json の追加キーが揃い、翻訳が入っている`, () => {
        const doc = load(locale, file)
        for (const key of keys) {
          const value = pick(doc, key)
          expect(typeof value, `${locale} ${key}`).toBe('string')
          expect((value as string).trim(), `${locale} ${key}`).not.toBe('')
          if (locale !== 'ja') expect(value as string, `${locale} ${key}`).not.toMatch(JAPANESE)
          const ja = pick(load('ja', file), key) as string
          expect((value as string).includes('{email}'), `${locale} ${key} placeholder`).toBe(ja.includes('{email}'))
        }
      })
    }
  }
})
