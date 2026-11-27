import type { SignageToken, SignageTokenIssued, IssueSignageTokenRequest } from '~/types/signage'

export interface ReissueResult {
  /** 新しく発行したトークンの平文。発行に成功した時点で必ず得られる。 */
  plaintext: string
  /** 旧トークンの無効化に失敗したか（新規は発行済みで、旧が有効なまま残っている）。 */
  revokeFailed: boolean
}

/** 期限が過去の場合は引き継がない（過去日時で発行すると即失効になるため）。 */
export function carryOverExpiry(expiredAt: string | null, now: Date = new Date()): string | null {
  if (!expiredAt) return null
  return new Date(expiredAt).getTime() > now.getTime() ? expiredAt : null
}

/**
 * 再発行: 同じ端末名で新規発行し、旧トークンを無効化する。
 * 発行に失敗したら例外を投げる（旧トークンには触れない）。発行に成功したら、旧の無効化に失敗しても
 * 新しい平文は返す（呼び出し側は平文を見せたうえで、無効化失敗を明示する）。
 */
export async function reissueSignageToken(
  token: SignageToken,
  issue: (body: IssueSignageTokenRequest) => Promise<{ data: SignageTokenIssued }>,
  revoke: (tokenId: number) => Promise<unknown>,
  now: Date = new Date(),
): Promise<ReissueResult> {
  const res = await issue({
    name: token.name,
    allowedIps: token.allowedIps,
    expiredAt: carryOverExpiry(token.expiredAt, now),
  })
  try {
    await revoke(token.id)
    return { plaintext: res.data.token, revokeFailed: false }
  }
  catch {
    return { plaintext: res.data.token, revokeFailed: true }
  }
}
