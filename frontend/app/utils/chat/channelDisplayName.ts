import type { ChatChannelResponse } from '~/types/chat'

type ChannelNameSource = Pick<ChatChannelResponse, 'dmPartner' | 'meta' | 'derivedName'>

/** 空白のみ・null・undefined を「名前なし」として扱い、使える名前だけ返す */
function usableName(name: string | null | undefined): string | null {
  const trimmed = name?.trim()
  return trimmed ? trimmed : null
}

/**
 * チャンネルの表示名を決める。
 * 優先順: 相手名(dmPartner) → 明示名(meta.name) → 導出名(derivedName) → 未命名文言。
 * 空文字・空白のみの名前は飛ばす（`??` では空文字が優先されてしまうため）。
 *
 * @param unnamedLabel i18n の未命名文言（`chat.unnamedChannel`）
 */
export function resolveChannelDisplayName(ch: ChannelNameSource, unnamedLabel: string): string {
  return (
    usableName(ch.dmPartner?.displayName) ??
    usableName(ch.meta?.name) ??
    usableName(ch.derivedName) ??
    unnamedLabel
  )
}
