import { offlineDb } from '~/composables/useOfflineDb'
import type { CreateActionMemoPayload, OfflineQueuedMemo } from '~/types/actionMemo'

/**
 * F02.5 行動メモのオフラインキュー composable。
 *
 * F11.1 PWA 対応により Dexie.js (useOfflineDb) + Background Sync に移行。
 * 既存の公開 API（enqueue / getAll / remove / count / flushQueue / hasQueuedItems / clearAll）
 * は互換性を維持し、内部実装のみを Dexie ベースに差し替えている。
 *
 * 旧実装: IndexedDB 直叩き + インメモリフォールバック
 * 新実装: Dexie.js (mannschaft-offline DB の offlineQueue テーブル) を使用。
 *          行動メモ固有のペイロードは汎用キューのフォーマットに変換して保存する。
 */

/** インメモリフォールバック（Dexie が使えない環境用） */
interface InMemoryFallback {
  items: OfflineQueuedMemo[]
  nextId: number
}

const _inMemory: InMemoryFallback = { items: [], nextId: 1 }
let _dexieAvailable: boolean | null = null

async function isDexieAvailable(): Promise<boolean> {
  if (_dexieAvailable !== null) return _dexieAvailable
  try {
    // Dexie の接続テスト
    await offlineDb.offlineQueue.count()
    _dexieAvailable = true
  } catch {
    _dexieAvailable = false
  }
  return _dexieAvailable
}

function nowIso(): string {
  return new Date().toISOString().replace('Z', '').slice(0, 19)
}

/**
 * 行動メモペイロードから汎用キュー用の clientId を生成する。
 */
function generateClientId(tempId: number): string {
  return `action-memo-${tempId}-${Date.now()}`
}

export function useOfflineQueue() {
  /**
   * 項目を 1 回の書き込みで積む（識別子を含めて保存し、書き込み後の更新による中間状態を作らない）。
   * Dexie 書き込みに失敗したらインメモリに退避する。
   */
  async function addItem(
    payload: CreateActionMemoPayload,
    tempId: number,
    publishTeamId?: number,
    publishOnlyMemoId?: number,
  ): Promise<OfflineQueuedMemo> {
    const enqueuedAt = nowIso()

    if (await isDexieAvailable()) {
      try {
        const id = await offlineDb.offlineQueue.add({
          clientId: generateClientId(tempId),
          method: 'POST',
          path: '/api/v1/action-memos',
          body: {
            ...payload,
            _tempId: tempId,
            ...(publishTeamId != null ? { _publishTeamId: publishTeamId } : {}),
            ...(publishOnlyMemoId != null ? { _publishOnlyMemoId: publishOnlyMemoId } : {}),
          } as unknown as Record<string, unknown>,
          version: null,
          status: 'PENDING',
          retryCount: 0,
          errorMessage: null,
          createdAt: enqueuedAt,
          syncedAt: null,
        })
        return { queueId: id as number, tempId, payload, enqueuedAt, publishTeamId, publishOnlyMemoId }
      } catch {
        // Dexie 失敗時はフォールバック
      }
    }

    // インメモリフォールバック
    const fallback: OfflineQueuedMemo = {
      queueId: _inMemory.nextId++,
      tempId,
      payload,
      enqueuedAt,
      publishTeamId,
      publishOnlyMemoId,
    }
    _inMemory.items.push(fallback)
    return fallback
  }

  /**
   * キューにペイロードを積む。
   */
  function enqueue(
    payload: CreateActionMemoPayload,
    tempId: number,
    publishTeamId?: number,
  ): Promise<OfflineQueuedMemo> {
    return addItem(payload, tempId, publishTeamId)
  }

  /**
   * 作成済みメモの「公開のみ」項目を積む（オフライン同期で作成成功・公開失敗したとき用）。
   * メモは再作成せず、次回 flush で publish-to-team だけ再試行する。
   */
  function enqueuePublishOnly(memoId: number, teamId: number): Promise<OfflineQueuedMemo> {
    return addItem({ content: '' }, 0, teamId, memoId)
  }

  /**
   * 作成済みになった項目を、実メモ ID つきの「公開のみ」項目へ 1 回の書き込みで置き換える（同じ行の body 更新）。
   * 作成成功〜公開応答待ちの間に再読み込みされても、同じメモが再作成されないようにするため。
   * 置き換えに失敗したら例外を投げる（呼び出し側は作成要求を再実行しない方針）。
   */
  async function convertToPublishOnly(queueId: number, memoId: number, teamId: number): Promise<void> {
    let done = false
    if (await isDexieAvailable()) {
      try {
        const n = await offlineDb.offlineQueue.update(queueId, {
          body: { _tempId: 0, _publishTeamId: teamId, _publishOnlyMemoId: memoId } as unknown as Record<string, unknown>,
        })
        done = n > 0
      } catch {
        // Dexie 失敗時はフォールバック
      }
    }
    const mem = _inMemory.items.find((i) => i.queueId === queueId)
    if (mem) {
      mem.payload = { content: '' }
      mem.publishTeamId = teamId
      mem.publishOnlyMemoId = memoId
      done = true
    }
    if (!done) throw new Error('queue item could not be converted to publish-only')
  }

  /**
   * キュー内の全項目を enqueuedAt 昇順で取得する。
   */
  async function getAll(): Promise<OfflineQueuedMemo[]> {
    if (await isDexieAvailable()) {
      try {
        const items = await offlineDb.offlineQueue
          .where('status')
          .anyOf(['PENDING', 'FAILED'])
          .sortBy('createdAt')

        return items
          .filter((item) => item.path === '/api/v1/action-memos' && item.method === 'POST')
          .map((item) => ({
            queueId: item.id,
            tempId: (item.body as Record<string, unknown>)?._tempId as number ?? 0,
            payload: extractPayload(item.body),
            enqueuedAt: item.createdAt,
            publishTeamId: extractPublishTeamId(item.body),
            publishOnlyMemoId: extractNumber(item.body, '_publishOnlyMemoId'),
          }))
      } catch {
        // Dexie 失敗時はフォールバック
      }
    }
    return [..._inMemory.items].sort((a, b) => (a.enqueuedAt < b.enqueuedAt ? -1 : 1))
  }

  /**
   * 指定 ID の項目を削除する。
   */
  async function remove(queueId: number): Promise<void> {
    if (await isDexieAvailable()) {
      try {
        await offlineDb.offlineQueue.delete(queueId)
      } catch {
        // Dexie 失敗時はフォールバック
      }
    }
    _inMemory.items = _inMemory.items.filter((i) => i.queueId !== queueId)
  }

  /**
   * キューが空かどうか（UI バナー表示判定用）。
   */
  async function hasQueuedItems(): Promise<boolean> {
    const items = await getAll()
    return items.length > 0
  }

  /**
   * キュー件数（UI 表示用）。
   */
  async function count(): Promise<number> {
    const items = await getAll()
    return items.length
  }

  /**
   * キュー内の項目を順次送信する。
   *
   * 1 件ずつ try しながら送信し、成功した項目だけキューから消す。途中で失敗した
   * 項目は次回の flush で再試行される。
   *
   * @param sender 送信関数。useActionMemoApi#createMemo を渡す想定
   * @returns 成功した項目の OfflineQueuedMemo（tempId を含む）配列
   */
  async function flushQueue(
    sender: (
      payload: CreateActionMemoPayload,
      queued: OfflineQueuedMemo,
    ) => Promise<{ id: number; keepQueued?: boolean } | null>,
  ): Promise<Array<{ queued: OfflineQueuedMemo; createdId: number }>> {
    const items = await getAll()
    const results: Array<{ queued: OfflineQueuedMemo; createdId: number }> = []
    for (const item of items) {
      try {
        const created = await sender(item.payload, item)
        if (created && typeof created.id === 'number') {
          results.push({ queued: item, createdId: created.id })
          // keepQueued: 公開のみ項目へ置き換え済みで、公開が未完（通信断など）のため項目を残す
          if (typeof item.queueId === 'number' && !created.keepQueued) {
            await remove(item.queueId)
          }
        } else {
          // null = 送信失敗。次回に持ち越し
          break
        }
      } catch {
        // 429 / ネットワーク切断などは次回リトライに回す
        break
      }
    }
    return results
  }

  /**
   * テスト用: 全キューを削除する。本番コードからは通常呼ばない。
   */
  async function clearAll(): Promise<void> {
    if (await isDexieAvailable()) {
      try {
        await offlineDb.offlineQueue.clear()
      } catch {
        // Dexie 失敗時はフォールバック
      }
    }
    _inMemory.items = []
    _inMemory.nextId = 1
  }

  return {
    enqueue,
    enqueuePublishOnly,
    convertToPublishOnly,
    getAll,
    remove,
    hasQueuedItems,
    count,
    flushQueue,
    clearAll,
  }
}

function extractNumber(body: Record<string, unknown>, key: string): number | undefined {
  const v = body?.[key]
  return typeof v === 'number' ? v : undefined
}

function extractPublishTeamId(body: Record<string, unknown>): number | undefined {
  return extractNumber(body, '_publishTeamId')
}

/**
 * 汎用 body から CreateActionMemoPayload を抽出するヘルパー。
 */
function extractPayload(body: Record<string, unknown>): CreateActionMemoPayload {
  const cleaned = { ...body }
  delete cleaned._tempId
  delete cleaned._publishTeamId
  delete cleaned._publishOnlyMemoId
  return cleaned as unknown as CreateActionMemoPayload
}
