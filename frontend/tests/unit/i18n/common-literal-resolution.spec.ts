// @vitest-environment node
import { readdirSync, readFileSync } from 'node:fs'
import { dirname, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { deepCopy } from '@intlify/shared'
import { parse as parseSfc } from '@vue/compiler-sfc'
import { NodeTypes, parse as parseTemplate, type TemplateChildNode } from '@vue/compiler-dom'
import ts from 'typescript'
import { createI18n } from 'vue-i18n'
import { beforeAll, describe, expect, it } from 'vitest'
import { loadLocaleFile, parseRegisteredLocaleFiles, readTextStripBom } from './localeResidue'

/** literal t()/ $t() の common.* だけを検査する。変数・補間を含む動的キーは対象外。 */
interface Reference {
  key: string
  line: number
  arguments: string[]
}

type Messages = Record<string, unknown>
const languages = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
type Language = (typeof languages)[number]
const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')
const app = resolve(frontend, 'app')

function isProductSource(filename: string): boolean {
  return /\.(vue|ts)$/.test(filename) && !/\.(spec|test)\.ts$/.test(filename)
}

function references(source: string, filename: string, commonOnly = true): Reference[] {
  const found: Reference[] = []
  function scan(expression: string, startLine: number): void {
    const parsed = ts.createSourceFile(filename + '.ts', expression, ts.ScriptTarget.Latest, true)
    function visit(node: ts.Node): void {
      if (ts.isCallExpression(node) && ts.isIdentifier(node.expression)
        && ['t', '$t'].includes(node.expression.text)) {
        const key = node.arguments[0]
        if (key && (ts.isStringLiteral(key) || ts.isNoSubstitutionTemplateLiteral(key))
          && (!commonOnly || key.text.startsWith('common.'))) {
          found.push({
            key: key.text,
            line: startLine + parsed.getLineAndCharacterOfPosition(node.getStart(parsed)).line,
            arguments: node.arguments.slice(1).map(argument => argument.getText(parsed)),
          })
        }
      }
      ts.forEachChild(node, visit)
    }
    visit(parsed)
  }
  if (!filename.endsWith('.vue')) {
    scan(source, 1)
    return found
  }
  const { descriptor, errors } = parseSfc(source, { filename })
  if (errors.length) throw new Error(`${filename}: SFC解析失敗: ${errors.join(', ')}`)
  for (const script of [descriptor.script, descriptor.scriptSetup]) {
    if (script) scan(script.content, script.loc.start.line)
  }
  if (descriptor.template) {
    const template = descriptor.template
    const tree = parseTemplate(template.content)
    function visit(node: TemplateChildNode): void {
      if (node.type === NodeTypes.INTERPOLATION) {
        scan(node.content.loc.source, template.loc.start.line + node.content.loc.start.line - 1)
      }
      if (node.type === NodeTypes.ELEMENT) {
        for (const prop of node.props) {
          if (prop.type === NodeTypes.DIRECTIVE && prop.exp) {
            scan(prop.exp.loc.source, template.loc.start.line + prop.exp.loc.start.line - 1)
          }
        }
        node.children.forEach(visit)
      }
    }
    tree.children.forEach(visit)
  }
  return found
}

async function registeredMessages(
  registered: Map<string, string[]>,
  read: (file: string) => Promise<unknown>,
): Promise<Map<string, Messages>> {
  const merged = new Map<string, Messages>()
  for (const language of languages) {
    const files = registered.get(language)
    if (!files?.length) throw new Error(`${language}: 登録ファイルが0件`)
    const messages: Messages = {}
    for (const file of files) {
      const loaded = await read(file)
      if (!loaded || typeof loaded !== 'object' || Array.isArray(loaded)) {
        throw new Error(`${file}: メッセージがオブジェクトでない`)
      }
      // @nuxtjs/i18n 10.4.0 runtime/shared/messages.js と同じ登録順のdeepCopy。
      deepCopy(loaded as Messages, messages)
    }
    merged.set(language, messages)
  }
  return merged
}

function composer(language: Language, messages: Messages) {
  return createI18n({
    legacy: false,
    locale: language,
    fallbackLocale: false,
    missingWarn: false,
    fallbackWarn: false,
    // Nuxtの生成型と同じ6言語を登録し、対象言語以外は空辞書にする。
    messages: { ja: {}, en: {}, zh: {}, ko: {}, es: {}, de: {}, [language]: messages },
  })
}

function unresolved(keys: string[], language: Language, messages: Messages): string[] {
  const i18n = composer(language, messages)
  try {
    return keys.filter(key => !i18n.global.te(key, language))
  } finally {
    i18n.dispose()
  }
}

describe('literal common.* 番人の負例', () => {
  it('同居spec/testの意図的なfake keyは製品参照に数えない', () => {
    expect(['Page.vue', 'usePage.ts', 'Page.spec.ts', 'Page.test.ts']
      .filter(isProductSource)).toEqual(['Page.vue', 'usePage.ts'])
  })

  it('不存在キーを検出し、合法なネストキーを解決する', () => {
    expect(unresolved(['common.fake', 'common.cancel', 'common.scopeShell.title'], 'en', {
      common: { cancel: 'Cancel', scopeShell: { title: 'Scope' } },
    })).toEqual(['common.fake'])
  })

  it('jaに存在してもen自身に無いキーを失敗にする', () => {
    const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: false,
      messages: { ja: { common: { onlyJa: '日本語のみ' } }, en: {}, zh: {}, ko: {}, es: {}, de: {} } })
    try {
      expect(i18n.global.te('common.onlyJa', 'en')).toBe(false)
    } finally {
      i18n.dispose()
    }
  })

  it('未登録JSONは読まず、後順上書きと名前空間なしを実vue-i18nで解決する', async () => {
    const first = { common: { title: 'First', nested: { keep: 'Kept' } }, flat: 'Flat' }
    const last = { common: { title: 'Last', nested: { added: 'Added' } } }
    const unregistered = { common: { fake: '未登録' } }
    const files = new Map<string, unknown>([['first.json', first], ['last.json', last], ['unused.json', unregistered]])
    const registered = new Map(languages.map(language => [language, ['first.json', 'last.json']]))
    const loaded = await registeredMessages(registered, async file => files.get(file))
    const i18n = composer('en', loaded.get('en')!)
    try {
      expect([
        i18n.global.t('common.title'), i18n.global.t('common.nested.keep'),
        i18n.global.t('common.nested.added'), i18n.global.t('flat'),
        i18n.global.te('first.flat'), i18n.global.te('common.fake'),
      ]).toEqual(['Last', 'Kept', 'Added', 'Flat', false, false])
    } finally {
      i18n.dispose()
    }
  })

  it('TSのコメント・文字列の見本・動的キーを除き実呼出だけを拾う', () => {
    const source = "// t('common.comment')\nconst example = \"t('common.example')\"; t('common.cancel'); $t(`common.scopeShell.title`); t(key); t(`common.${name}`)"
    expect(references(source, 'fixture.ts').map(reference => reference.key))
      .toEqual(['common.cancel', 'common.scopeShell.title'])
  })

  it('Vueのコメント・属性の見本を除き補間とdirective・scriptの実呼出を拾う', () => {
    const source = `<script setup lang="ts">t('common.save')</script><template><!-- $t('common.comment') --><div data-example="t('common.example')" :title="$t('common.cancel')">{{ t('common.scopeShell.title') }}</div></template>`
    expect(references(source, 'fixture.vue').map(reference => reference.key))
      .toEqual(['common.save', 'common.cancel', 'common.scopeShell.title'])
  })
})

describe('CMP-260919-1139 登録ロケールと製品のliteral common.*', () => {
  let dictionaries: Map<string, Messages>
  const calls: Array<Reference & { file: string }> = []

  beforeAll(async () => {
    const registered = parseRegisteredLocaleFiles(readTextStripBom(resolve(frontend, 'nuxt.config.ts')))
    dictionaries = await registeredMessages(registered, file => loadLocaleFile(resolve(app, 'locales', file)))
    function scan(directory: string): void {
      for (const entry of readdirSync(directory, { withFileTypes: true })) {
        const path = resolve(directory, entry.name)
        if (entry.isDirectory()) scan(path)
        else if (isProductSource(entry.name)) {
          const source = readFileSync(path, 'utf8')
          if (!source.includes('common.')) continue
          for (const reference of references(source, path)) {
            calls.push({ ...reference, file: relative(frontend, path) })
          }
        }
      }
    }
    scan(app)
  }, 120_000)

  it.each(languages)('%s自身の登録辞書で全literal common.*が解決する（動的キー対象外）', language => {
    expect(calls.length, '空走査を合格にしない').toBeGreaterThan(0)
    const missing = new Set(unresolved([...new Set(calls.map(call => call.key))], language, dictionaries.get(language)!))
    const violations = calls.filter(call => missing.has(call.key)).map(call => `${call.file}:${call.line} ${call.key}`)
    expect(violations, `${language}: 未解決${missing.size}種\n${violations.join('\n')}`).toEqual([])
  })

  const counts = [0, 1, 2, 8]
  const expected: Record<string, string[]> = {
    ja: ['0件', '1件', '2件', '8件'],
    en: ['0 terms', '1 term', '2 terms', '8 terms'],
    zh: ['0条任期记录', '1条任期记录', '2条任期记录', '8条任期记录'],
    ko: ['임기 0개', '임기 1개', '임기 2개', '임기 8개'],
    es: ['0 mandatos', '1 mandato', '2 mandatos', '8 mandatos'],
    de: ['0 Amtszeiten', '1 Amtszeit', '2 Amtszeiten', '8 Amtszeiten'],
  }

  it.each(languages)('%sの任期件数0/1/2/多件を実vue-i18nで解決する', language => {
    const i18n = composer(language, dictionaries.get(language)!)
    try {
      expect(counts.map(count => i18n.global.t('repair_plan.handover.term.count', { count }, count)))
        .toEqual(expected[language])
    } finally {
      i18n.dispose()
    }
  })

  it('任期件数はcountと同じ値をplural引数へ渡し、接尾語連結を残さない', () => {
    const path = resolve(app, 'components/repair-plan/MemberTermManager.vue')
    const source = readFileSync(path, 'utf8')
    const countCalls = references(source, path, false).filter(call => call.key === 'repair_plan.handover.term.count')
    expect(countCalls).toHaveLength(1)
    const arguments_ = countCalls[0]!.arguments
    const named = ts.createSourceFile('count.ts', '(' + arguments_[0] + ')', ts.ScriptTarget.Latest, true)
    let countExpression: string | undefined
    function visit(node: ts.Node): void {
      if (ts.isPropertyAssignment(node) && node.name.getText(named) === 'count') {
        countExpression = node.initializer.getText(named)
      }
      ts.forEachChild(node, visit)
    }
    visit(named)
    expect(countExpression).toBeTruthy()
    expect(arguments_[1]?.trim()).toBe(countExpression)
    expect(references(source, path).map(call => call.key)).not.toContain('common.count_suffix')
  })

  it.each(languages)('%sで読み込み操作と申し送りパック生成失敗の新訳が解決する', language => {
    expect(unresolved(['tournament.roster.load_match', 'repair_plan.handover.builder.generate_failed'],
      language, dictionaries.get(language)!)).toEqual([])
  })
})
