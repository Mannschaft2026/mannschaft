import { expect } from '@playwright/test'
import type { Page } from '@playwright/test'

/**
 * パスキー登録・削除前の再認証（CMP-261021-1338）の E2E 共通手順。
 * 登録・削除は用途 PASSKEY_MANAGE の再認証（5分以内・同一セッション・1回限り）が無いと 403 AUTH_087。
 * CDP 仮想認証器は hasUserVerification=true / isUserVerified=true で UV 必須の assertion を満たす。
 */

export interface ReauthOptions {
  /** BE のオリジン（例: http://localhost:8080）。 */
  apiBase: string
  /** E2E ユーザーの現在のパスワード（パスワード手段の再認証に使う。2FA 無効の前提）。 */
  password: string
}

/**
 * 用途 PASSKEY_MANAGE の再認証を API で行う（フラグは 5 分・同一セッション・1 回限り。登録 complete / 削除で消費される）。
 * パスキー保有者はパスキー（UV 必須）、パスワードのみの利用者は /reauthenticate-factor。FE オリジンのページ上で呼ぶこと。
 */
export async function reauthenticateForPasskeyManage(page: Page, opts: ReauthOptions): Promise<void> {
  const { apiBase, password } = opts
  const methodRes = await page.request.get(`${apiBase}/api/v1/auth/webauthn/reauthenticate-method`)
  expect(methodRes.ok(), `reauthenticate-method 失敗: ${methodRes.status()}`).toBeTruthy()
  const method = (await methodRes.json()).data?.method as string

  if (method === 'PASSWORD') {
    const res = await page.request.post(`${apiBase}/api/v1/auth/webauthn/reauthenticate-factor`, {
      data: { currentPassword: password },
    })
    expect(res.ok(), `reauthenticate-factor 失敗: ${res.status()} ${await res.text()}`).toBeTruthy()
    return
  }
  if (method !== 'PASSKEY') {
    throw new Error(`E2E ユーザーの再認証手段 ${method} は未対応（2FA 無効・パスワードありのユーザーを使うこと）`)
  }

  const beginRes = await page.request.post(`${apiBase}/api/v1/auth/webauthn/reauthenticate-begin`, {
    data: { purpose: 'PASSKEY_MANAGE' },
  })
  expect(beginRes.ok(), `reauthenticate-begin 失敗: ${beginRes.status()} ${await beginRes.text()}`).toBeTruthy()
  const begin = (await beginRes.json()).data as { challenge: string; rpId: string; allowCredentials: string[]; timeout: number }

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
      const cred = (await navigator.credentials.get({
        publicKey: {
          challenge: b64urlToBuf(opts.challenge),
          rpId: opts.rpId,
          timeout: opts.timeout || 300000,
          userVerification: 'required',
          allowCredentials: opts.allowCredentials.map((id) => ({ type: 'public-key' as const, id: b64urlToBuf(id) })),
        },
      })) as PublicKeyCredential
      const resp = cred.response as AuthenticatorAssertionResponse
      return {
        credentialId: bufToB64url(cred.rawId),
        authenticatorData: bufToB64url(resp.authenticatorData),
        clientDataJson: bufToB64url(resp.clientDataJSON),
        signature: bufToB64url(resp.signature),
        // rpIdHash(32) + flags(1) + signCount(4, big-endian)
        signCount: new DataView(resp.authenticatorData).getUint32(33, false),
      }
    },
    begin,
  )
  const completeRes = await page.request.post(`${apiBase}/api/v1/auth/webauthn/reauthenticate-complete`, { data: assertion })
  expect(completeRes.ok(), `reauthenticate-complete 失敗: ${completeRes.status()} ${await completeRes.text()}`).toBeTruthy()
}

/**
 * 登録済みの資格情報をすべて削除する（削除ごとに再認証が要る）。仮想認証器が生きているうちに呼ぶこと
 * （認証器を外した後は、残ったパスキーでの再認証ができず削除できない）。
 */
export async function deleteAllCredentials(page: Page, opts: ReauthOptions): Promise<void> {
  const { apiBase } = opts
  const credsRes = await page.request.get(`${apiBase}/api/v1/auth/webauthn/credentials`)
  expect(credsRes.ok(), `credentials 取得失敗: ${credsRes.status()}`).toBeTruthy()
  const creds: { id: number }[] = ((await credsRes.json()).data) ?? []
  for (const cred of creds) {
    await reauthenticateForPasskeyManage(page, opts)
    const del = await page.request.delete(`${apiBase}/api/v1/auth/webauthn/credentials/${cred.id}`)
    expect(del.ok(), `credentials 削除失敗: ${del.status()}`).toBeTruthy()
  }
}
