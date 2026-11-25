/**
 * WebAuthn（パスキー）実機テスト。
 *
 * 前提:
 *   - BE (http://localhost:8080) と FE (http://localhost:3000) が起動中であること
 *   - BE application-local.yml に以下を設定すること:
 *       mannschaft:
 *         webauthn:
 *           rp-id: localhost
 *           origin: http://localhost:3000
 *
 * BE レスポンス形式:
 *   WebAuthnRegisterBeginResponse: { challenge, rpId, rpName, userId, userDisplayName }
 *   WebAuthnLoginBeginResponse:    { challenge, rpId, allowCredentials: string[], timeout }
 *
 * パスキーの登録・削除は、事前に用途 PASSKEY_MANAGE の再認証（5分以内・同一セッション・1回限り）が必要
 * （無いと 403 AUTH_087。CMP-261021-1338）。再認証手段は GET /reauthenticate-method で決まる:
 *   パスキー保有→パスキー（UV 必須）／パスワードのみ→POST /reauthenticate-factor。
 * 本スペックの E2E ユーザーは 2FA 無効・パスワードあり（手段 PASSWORD）を前提とする。
 * 再認証・登録・ログインの assertion/attestation はすべて userVerification='required'（CDP 仮想認証器は
 * hasUserVerification=true / isUserVerified=true で UV を満たす）。
 *
 * BE リクエスト形式:
 *   WebAuthnRegisterCompleteRequest: { credentialId, attestationObject, clientDataJson, publicKey, deviceName? }
 *   WebAuthnLoginCompleteRequest:    { credentialId, authenticatorData, clientDataJson, signature, signCount, userHandle? }
 */

import { test, expect } from '@playwright/test'
import type { Page, CDPSession } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { deleteAllCredentials, reauthenticateForPasskeyManage } from '../helpers/passkey-reauth'

const USER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const USER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const REAUTH = { apiBase: API_BASE, password: USER_PASSWORD }

// ----------------------------------------------------------------------------------
// 仮想 WebAuthn 認証器
// ----------------------------------------------------------------------------------

async function setupVirtualAuthenticator(page: Page): Promise<{ client: CDPSession }> {
  const client = await page.context().newCDPSession(page)
  await client.send('WebAuthn.enable', { enableUI: false })
  await client.send('WebAuthn.addVirtualAuthenticator', {
    options: {
      protocol: 'ctap2',
      transport: 'internal',
      hasResidentKey: true,
      hasUserVerification: true,
      isUserVerified: true,
    },
  })
  return { client }
}

/**
 * FE が NUXT_API_PROXY を持たない場合でも UI テストが動作するよう、
 * WebAuthn API 呼び出しを playwright の page.route で BE に中継する。
 */
async function setupApiRoute(page: Page): Promise<void> {
  const feOrigin = new URL(page.url()).origin
  await page.route('**/api/v1/auth/webauthn/**', async (route) => {
    const req = route.request()
    const targetUrl = req.url().replace(/^https?:\/\/[^/]+/, API_BASE)
    const response = await route.fetch({
      url: targetUrl,
      headers: {
        ...req.headers(),
        origin: feOrigin,
        'access-control-allow-origin': feOrigin,
      },
    })
    const resHeaders = { ...response.headers() }
    resHeaders['access-control-allow-origin'] = feOrigin
    resHeaders['access-control-allow-credentials'] = 'true'
    await route.fulfill({
      status: response.status(),
      headers: resHeaders,
      body: await response.body(),
    })
  })
}

// ----------------------------------------------------------------------------------
// WEBAUTHN テスト
// ----------------------------------------------------------------------------------

test.describe('WEBAUTHN: パスキー登録・ログインフロー', () => {
  test.describe.configure({ mode: 'serial' })

  test.beforeEach(async ({ page }) => {
    // 毎テスト前に API ログインで再認証（serial テスト間のトークンローテーション対策）
    // page.goto('/') が token rotation を起こすため、次テストの storageState が失効する可能性がある
    await page.request.post(`${API_BASE}/api/v1/auth/login`, {
      data: { email: USER_EMAIL, password: USER_PASSWORD },
    })

    // テスト干渉防止: 資格情報が残っていないこと。削除には再認証（と、残ったパスキーを保持する仮想認証器）が要るため、
    // 各テストが自分で作った資格情報を finally 内で deleteAllCredentials する。前回の異常終了で残骸があれば、
    // 認証器を失ったパスキーは再認証できないので、テストユーザーの WebAuthn 資格情報を DB から削除してから再実行すること。
    const credsRes = await page.request.get(`${API_BASE}/api/v1/auth/webauthn/credentials`)
    if (credsRes.ok()) {
      const creds: { id: number }[] = ((await credsRes.json()).data) ?? []
      expect(creds, '前回の資格情報が残っていないこと（残骸は DB から削除して再実行）').toHaveLength(0)
    }
  })

  test('WEBAUTHN-001: パスキーを登録できる（API フロー）', async ({ page }) => {
    const { client } = await setupVirtualAuthenticator(page)
    try {
      // FE に移動して clientDataJSON.origin = FE オリジンを確立
      await page.goto('/')

      // 0. 登録前の再認証（用途 PASSKEY_MANAGE）。無いと register/begin は 403 AUTH_087
      await reauthenticateForPasskeyManage(page, REAUTH)

      // 1. 登録開始 → チャレンジ取得
      const beginRes = await page.request.post(`${API_BASE}/api/v1/auth/webauthn/register/begin`)
      expect(beginRes.ok(), `register/begin 失敗: ${beginRes.status()}`).toBeTruthy()
      const beginData = (await beginRes.json()).data as {
        challenge: string
        rpId: string
        rpName: string
        userId: number
        userDisplayName: string
      }

      // 2. navigator.credentials.create() を実行（CDP 仮想認証器が自動応答）
      const credential = await page.evaluate(
        async (opts: { challenge: string; rpId: string; rpName: string; userId: number; displayName: string }) => {
          function b64urlToBuf(b64url: string): ArrayBuffer {
            const b64 = b64url.replace(/-/g, '+').replace(/_/g, '/')
            const binary = atob(b64)
            const bytes = new Uint8Array(binary.length)
            for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
            return bytes.buffer
          }
          function bufToB64url(buffer: ArrayBuffer): string {
            const bytes = new Uint8Array(buffer)
            let binary = ''
            for (let i = 0; i < bytes.byteLength; i++) binary += String.fromCharCode(bytes[i]!)
            return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '')
          }
          function numberToArrayBuffer(n: number): ArrayBuffer {
            const ab = new ArrayBuffer(8)
            const buf = new Uint8Array(ab)
            let val = n
            for (let i = 0; i < 8; i++) { buf[i] = val & 0xff; val = Math.floor(val / 256) }
            return ab
          }

          const publicKey: PublicKeyCredentialCreationOptions = {
            challenge: b64urlToBuf(opts.challenge),
            rp: { id: opts.rpId, name: opts.rpName },
            user: {
              id: numberToArrayBuffer(opts.userId),
              name: opts.displayName,
              displayName: opts.displayName,
            },
            pubKeyCredParams: [
              { alg: -7, type: 'public-key' },
              { alg: -257, type: 'public-key' },
            ],
            authenticatorSelection: { userVerification: 'required', residentKey: 'preferred' },
            timeout: 60000,
            attestation: 'none',
          }
          const cred = (await navigator.credentials.create({ publicKey })) as PublicKeyCredential
          const resp = cred.response as AuthenticatorAttestationResponse
          return {
            credentialId: bufToB64url(cred.rawId),
            attestationObject: bufToB64url(resp.attestationObject),
            clientDataJson: bufToB64url(resp.clientDataJSON),
            publicKey: bufToB64url(resp.getPublicKey ? (resp.getPublicKey() ?? new ArrayBuffer(0)) : new ArrayBuffer(0)),
          }
        },
        { challenge: beginData.challenge, rpId: beginData.rpId, rpName: beginData.rpName, userId: beginData.userId, displayName: beginData.userDisplayName },
      )

      // 3. 登録完了
      const completeRes = await page.request.post(`${API_BASE}/api/v1/auth/webauthn/register/complete`, {
        data: credential,
      })
      expect(completeRes.ok(), `register/complete 失敗: ${completeRes.status()} ${await completeRes.text()}`).toBeTruthy()

      // 4. BE に資格情報が登録されたことを確認
      const listRes = await page.request.get(`${API_BASE}/api/v1/auth/webauthn/credentials`)
      expect(listRes.ok()).toBeTruthy()
      expect(((await listRes.json()).data as unknown[]).length, '資格情報が1件以上').toBeGreaterThanOrEqual(1)
    } finally {
      // 認証器が生きているうちに後始末（削除にも再認証が要る）
      await deleteAllCredentials(page, REAUTH)
      await client.detach()
    }
  })

  test('WEBAUTHN-002: パスキーでログインできる（API フロー）', async ({ page }) => {
    const { client } = await setupVirtualAuthenticator(page)
    try {
      // === 資格情報登録（WEBAUTHN-001 と同じ手順） ===
      await page.goto('/')
      await reauthenticateForPasskeyManage(page, REAUTH)
      const beginRegRes = await page.request.post(`${API_BASE}/api/v1/auth/webauthn/register/begin`)
      expect(beginRegRes.ok(), `register/begin 失敗: ${beginRegRes.status()} ${await beginRegRes.text()}`).toBeTruthy()
      const bd = (await beginRegRes.json()).data as {
        challenge: string; rpId: string; rpName: string; userId: number; userDisplayName: string
      }

      const regCredential = await page.evaluate(
        async (opts: { challenge: string; rpId: string; rpName: string; userId: number; displayName: string }) => {
          function b64urlToBuf(b64url: string): ArrayBuffer {
            const b64 = b64url.replace(/-/g, '+').replace(/_/g, '/')
            const binary = atob(b64)
            const bytes = new Uint8Array(binary.length)
            for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
            return bytes.buffer
          }
          function bufToB64url(buffer: ArrayBuffer): string {
            const bytes = new Uint8Array(buffer)
            let binary = ''
            for (let i = 0; i < bytes.byteLength; i++) binary += String.fromCharCode(bytes[i]!)
            return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '')
          }
          function numberToArrayBuffer(n: number): ArrayBuffer {
            const ab = new ArrayBuffer(8)
            const buf = new Uint8Array(ab)
            let val = n
            for (let i = 0; i < 8; i++) { buf[i] = val & 0xff; val = Math.floor(val / 256) }
            return ab
          }
          const publicKey: PublicKeyCredentialCreationOptions = {
            challenge: b64urlToBuf(opts.challenge),
            rp: { id: opts.rpId, name: opts.rpName },
            user: { id: numberToArrayBuffer(opts.userId), name: opts.displayName, displayName: opts.displayName },
            pubKeyCredParams: [{ alg: -7, type: 'public-key' }, { alg: -257, type: 'public-key' }],
            authenticatorSelection: { userVerification: 'required', residentKey: 'preferred' },
            timeout: 60000,
            attestation: 'none',
          }
          const cred = (await navigator.credentials.create({ publicKey })) as PublicKeyCredential
          const resp = cred.response as AuthenticatorAttestationResponse
          return {
            credentialId: bufToB64url(cred.rawId),
            attestationObject: bufToB64url(resp.attestationObject),
            clientDataJson: bufToB64url(resp.clientDataJSON),
            publicKey: bufToB64url(resp.getPublicKey ? (resp.getPublicKey() ?? new ArrayBuffer(0)) : new ArrayBuffer(0)),
          }
        },
        { challenge: bd.challenge, rpId: bd.rpId, rpName: bd.rpName, userId: bd.userId, displayName: bd.userDisplayName },
      )

      const regCompleteRes = await page.request.post(`${API_BASE}/api/v1/auth/webauthn/register/complete`, {
        data: regCredential,
      })
      expect(regCompleteRes.ok(), `register/complete 失敗: ${regCompleteRes.status()}`).toBeTruthy()

      // === ログアウト ===
      await page.request.post(`${API_BASE}/api/v1/auth/logout`)

      // === パスキーでログイン ===
      // 1. login/begin → チャレンジと allowCredentials 取得
      const beginLoginRes = await page.request.post(
        `${API_BASE}/api/v1/auth/webauthn/login/begin?email=${encodeURIComponent(USER_EMAIL)}`,
      )
      expect(beginLoginRes.ok(), `login/begin 失敗: ${beginLoginRes.status()}`).toBeTruthy()
      const ld = (await beginLoginRes.json()).data as {
        challenge: string; rpId: string; allowCredentials: string[]; timeout: number
      }

      // 2. navigator.credentials.get() を実行（CDP 仮想認証器が自動応答）
      const assertion = await page.evaluate(
        async (opts: { challenge: string; rpId: string; allowCredentials: string[]; timeout: number }) => {
          function b64urlToBuf(b64url: string): ArrayBuffer {
            const b64 = b64url.replace(/-/g, '+').replace(/_/g, '/')
            const binary = atob(b64)
            const bytes = new Uint8Array(binary.length)
            for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
            return bytes.buffer
          }
          function bufToB64url(buffer: ArrayBuffer): string {
            const bytes = new Uint8Array(buffer)
            let binary = ''
            for (let i = 0; i < bytes.byteLength; i++) binary += String.fromCharCode(bytes[i]!)
            return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '')
          }
          function extractSignCount(authenticatorData: ArrayBuffer): number {
            // rpIdHash(32) + flags(1) + signCount(4, big-endian)
            return new DataView(authenticatorData).getUint32(33, false)
          }

          const publicKey: PublicKeyCredentialRequestOptions = {
            challenge: b64urlToBuf(opts.challenge),
            rpId: opts.rpId,
            timeout: opts.timeout || 300000,
            userVerification: 'required',
            allowCredentials: opts.allowCredentials.map((id) => ({
              type: 'public-key' as const,
              id: b64urlToBuf(id),
            })),
          }
          const cred = (await navigator.credentials.get({ publicKey })) as PublicKeyCredential
          const resp = cred.response as AuthenticatorAssertionResponse
          return {
            credentialId: bufToB64url(cred.rawId),
            authenticatorData: bufToB64url(resp.authenticatorData),
            clientDataJson: bufToB64url(resp.clientDataJSON),
            signature: bufToB64url(resp.signature),
            signCount: extractSignCount(resp.authenticatorData),
            userHandle: resp.userHandle ? bufToB64url(resp.userHandle) : null,
          }
        },
        { challenge: ld.challenge, rpId: ld.rpId, allowCredentials: ld.allowCredentials, timeout: ld.timeout },
      )

      // 3. login/complete → accessToken 取得
      const completeLoginRes = await page.request.post(`${API_BASE}/api/v1/auth/webauthn/login/complete`, {
        data: assertion,
      })
      expect(completeLoginRes.ok(), `login/complete 失敗: ${completeLoginRes.status()} ${await completeLoginRes.text()}`).toBeTruthy()
      const loginBody = await completeLoginRes.json()
      expect(loginBody.data?.accessToken, 'accessToken が発行されること').toBeTruthy()
    } finally {
      // パスキーログインで得た新しいセッションで、認証器が生きているうちに後始末
      await deleteAllCredentials(page, REAUTH)
      await client.detach()
    }
  })

  test('WEBAUTHN-003: /settings/security の UI ボタンからパスキーを登録できる（UI フロー）', async ({ page }) => {
    // CDP 仮想認証器はページ遷移前にセットアップする
    const { client } = await setupVirtualAuthenticator(page)

    try {
      // /settings/security に移動
      await page.goto('/settings/security')
      await waitForHydration(page)

      // API ブリッジ設定（FE に NUXT_API_PROXY 未設定の場合の保険）
      await setupApiRoute(page)

      // 「パスキーを登録」ボタンを待つ（WebAuthn サポート検出は onMounted）
      const registerBtn = page.getByRole('button', { name: 'パスキーを登録' })
      await expect(registerBtn, 'パスキーを登録ボタンが表示されること').toBeVisible({ timeout: 15_000 })

      // ボタンクリック → ダイアログ開く
      await registerBtn.click()
      const dialog = page.getByRole('dialog', { name: 'パスキーを登録' })
      await expect(dialog, 'ダイアログが開くこと').toBeVisible({ timeout: 5_000 })

      // 「登録する」クリック → 本人確認ダイアログ（パスワード手段）→ CDP 仮想認証器が credentials.create() を自動処理
      await dialog.getByRole('button', { name: '登録する' }).click()
      const reauthDialog = page.getByRole('dialog', { name: '本人確認', exact: true })
      await expect(reauthDialog, '登録前に本人確認ダイアログが開くこと').toBeVisible({ timeout: 5_000 })
      await reauthDialog.locator('#passkeyReauthPassword').fill(USER_PASSWORD)
      await reauthDialog.getByRole('button', { name: '本人確認する' }).click()

      // 成功トーストを確認
      const successToast = page.locator('[role="alert"][data-p="success"]').first()
      await expect(successToast, '成功トーストが表示されること').toBeVisible({ timeout: 15_000 })

      // 資格情報が一覧に追加されたことを確認
      await expect(page.locator('.pi.pi-key').first(), '資格情報エントリが表示されること').toBeVisible({
        timeout: 5_000,
      })

      // BE でも確認
      const listRes = await page.request.get(`${API_BASE}/api/v1/auth/webauthn/credentials`)
      expect(listRes.ok()).toBeTruthy()
      expect(((await listRes.json()).data as unknown[]).length, '資格情報が BE に登録されていること').toBeGreaterThanOrEqual(1)
    } finally {
      await deleteAllCredentials(page, REAUTH)
      await client.detach()
    }
  })

  test('WEBAUTHN-004: 再認証なしの register/begin は 403 AUTH_087 で拒否される', async ({ page }) => {
    await page.goto('/')
    const res = await page.request.post(`${API_BASE}/api/v1/auth/webauthn/register/begin`)
    expect(res.status(), '再認証なしの登録開始は拒否されること').toBe(403)
    expect((await res.json()).error?.code).toBe('AUTH_087')
  })
})
