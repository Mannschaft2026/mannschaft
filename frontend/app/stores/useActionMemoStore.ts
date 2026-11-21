import dayjs from 'dayjs'
import { defineStore } from 'pinia'
import type {
  ActionMemo,
  ActionMemoInputExtras,
  ActionMemoSettings,
  ActionMemoTag,
  AvailableOrg,
  AvailableTeam,
  CreateActionMemoPayload,
  CreateTagPayload,
  Mood,
  MoodStatsResponse,
  OrgVisibility,
  PublishDailyPayload,
  PublishDailyResponse,
  PublishDailyToTeamPayload,
  PublishToTeamPayload,
  UpdateActionMemoPayload,
  UpdateTagPayload,
  WeeklySummary,
} from '~/types/actionMemo'

/**
 * F02.5 行動メモ Pinia ストア。
 *
 * <p>UX 上の核となる原則は次の通り（設計書 §1, §11）:</p>
 * <ul>
 *   <li>楽観的 UI: 送信前にローカルに追加し、API 失敗時のみロールバック</li>
 *   <li>下書き自動保存: 入力中の content を {@code localStorage} に保存し、送信成功でクリア</li>
 *   <li>mood は設定 OFF のユーザーに対しては server 側で silent に NULL 化される</li>
 * </ul>
 */

interface ActionMemoStoreState {
  /** 表示中のメモ一覧（通常は当日分）。新しいものが配列の先頭 */
  memos: ActionMemo[]
  settings: ActionMemoSettings
  /** Phase 4: ユーザー所有のタグ一覧 */
  tags: ActionMemoTag[]
  /** Phase 4: 気分集計データ */
  moodStats: MoodStatsResponse | null
  loading: boolean
  /** 直近のエラー（429 / 400 等）。i18n キーまたはメッセージ */
  error: string | null
  /**
   * lastError は機械可読な分類用。
   * - "RATE_LIMIT" / "DAILY_LIMIT" / "FUTURE_DATE" / "TODO_NOT_FOUND" / "UNKNOWN" のいずれか
   */
  lastError: ActionMemoErrorCode | null
  /** オフラインキュー件数（UI バナー表示用）。online イベントや手動 flush で変動 */
  offlineQueueCount: number
  /** 現在オフラインかどうか（{@code navigator.onLine === false}）。SSR 時は false */
  isOffline: boolean
  /** 直近の publishToTeam が既投稿（ACTION_MEMO_ALREADY_POSTED）で失敗したか。公開のみ項目の後始末の判定用 */
  alreadyPostedFailure: boolean
  /** 直近の publishToTeam 失敗の HTTP ステータス（無ければ null）。公開のみ項目の恒久失敗判定用 */
  publishFailureStatus: number | null
  /** Phase 3: 週次まとめ一覧 */
  weeklySummaries: WeeklySummary[]
  /** Phase 3: 週次まとめ一覧の読み込み中フラグ */
  weeklyLoading: boolean
  /** Phase 3: 週次まとめ一覧のエラー（i18n キー） */
  weeklyError: string | null
  /** Phase 3: 週次まとめの現在ページ（0始まり） */
  weeklyPage: number
  /** Phase 3: 週次まとめの総ページ数 */
  weeklyTotalPages: number
  /** Phase 3: チーム投稿先候補一覧 */
  availableTeams: AvailableTeam[]
  /** Phase 5-2: 組織スコープ投稿先候補一覧 */
  availableOrgs: AvailableOrg[]
  /** Phase 4-α: 次回作成時に付与する組織スコープ（折りたたみパネルで設定） */
  pendingOrgScope: { organizationId: number | null; orgVisibility: OrgVisibility | null }
}

export type ActionMemoErrorCode =
  | 'RATE_LIMIT'
  | 'DAILY_LIMIT'
  | 'FUTURE_DATE'
  | 'TODO_NOT_FOUND'
  | 'PUBLISH_FAILED'
  | 'UNKNOWN'

const DRAFT_KEY_PREFIX = 'action-memo-draft-'

function draftKey(userId: number | string): string {
  return `${DRAFT_KEY_PREFIX}${userId}`
}

/**
 * 下書きの読み取り。新形式は {v:1, content, extras} の JSON、旧形式は本文だけの文字列。
 * JSON として読めても形式が合わなければ（本文がたまたま JSON 風でも）全体を本文として扱う。
 */
interface DraftPendingPublish {
  memoId: number
  teamId: number
}

function readDraft(userId: number | string): {
  content: string
  extras: ActionMemoInputExtras | null
  pendingPublish: DraftPendingPublish | null
} {
  if (typeof window === 'undefined') return { content: '', extras: null, pendingPublish: null }
  let raw = ''
  try {
    raw = localStorage.getItem(draftKey(userId)) ?? ''
  } catch {
    // Storage 無効環境は下書き無しとして扱う
  }
  {
    if (raw.startsWith('{')) {
      try {
        const parsed = JSON.parse(raw) as { v?: unknown; content?: unknown; extras?: unknown; pendingPublish?: unknown }
        if (parsed && parsed.v === 1 && typeof parsed.content === 'string') {
          const extras = parsed.extras && typeof parsed.extras === 'object' ? (parsed.extras as ActionMemoInputExtras) : null
          const pp = parsed.pendingPublish as Partial<DraftPendingPublish> | undefined
          const pendingPublish =
            pp && typeof pp.memoId === 'number' && typeof pp.teamId === 'number'
              ? { memoId: pp.memoId, teamId: pp.teamId }
              : null
          return { content: parsed.content, extras, pendingPublish }
        }
      } catch {
        // JSON ではない本文
      }
    }
    return { content: raw, extras: null, pendingPublish: null }
  }
}

/** 楽観的 UI の一時 ID。負値で本物の ID と被らないようにする */
let _tempIdCounter = -1
function nextTempId(): number {
  return _tempIdCounter--
}

/**
 * 投稿日時。サーバー側で書き換えられるためクライアント計算は最小限。
 * 楽観的 UI 用にローカル ISO 文字列を生成。
 */
function nowIso(): string {
  return new Date().toISOString().replace('Z', '').slice(0, 19)
}

/**
 * ユーザーのタイムゾーン設定に基づく今日の日付（YYYY-MM-DD）。
 */
function today(): string {
  const authStore = useAuthStore()
  return dayjs().tz(authStore.user?.timezone ?? 'Asia/Tokyo').format('YYYY-MM-DD')
}

export const useActionMemoStore = defineStore('actionMemo', {
  state: (): ActionMemoStoreState => ({
    memos: [],
    settings: {
      moodEnabled: false,
      defaultPostTeamId: null,
      defaultCategory: 'OTHER',
      reminderEnabled: false,
      reminderTime: null,
    },
    tags: [],
    moodStats: null,
    loading: false,
    error: null,
    lastError: null,
    offlineQueueCount: 0,
    isOffline: false,
    alreadyPostedFailure: false,
    publishFailureStatus: null,
    weeklySummaries: [],
    weeklyLoading: false,
    weeklyError: null,
    weeklyPage: 0,
    weeklyTotalPages: 0,
    availableTeams: [],
    availableOrgs: [],
    pendingOrgScope: { organizationId: null, orgVisibility: null },
  }),

  getters: {
    /** 当日のメモのみ抽出（日付で絞る簡易ヘルパー） */
    currentDayMemos:
      (state) =>
      (date: string): ActionMemo[] =>
        state.memos.filter((m) => m.memoDate === date),

    isMoodEnabled: (state): boolean => state.settings.moodEnabled,
  },

  actions: {
    /**
     * 楽観的 UI でメモを作成する。
     *
     * <ol>
     *   <li>一時 ID で memos の先頭に追加</li>
     *   <li>オンライン時: API 呼び出し → 成功時に本物の ID で置換 / 失敗時はロールバック</li>
     *   <li>オフライン時 ({@code navigator.onLine === false}): IndexedDB にキューイングし、
     *       楽観的 UI のまま維持。online イベントで自動 flush を試みる</li>
     * </ol>
     *
     * <p>オフライン時でも {@link ActionMemo#id} は負数の仮 ID が設定され、同期完了時に
     * {@link useOfflineQueue#flushQueue} 経由で本物の ID に置き換わる。</p>
     */
    async createMemo(
      payload: CreateActionMemoPayload,
      options: { publishTeamId?: number } = {},
    ): Promise<ActionMemo | null> {
      const tempId = nextTempId()
      const optimistic: ActionMemo = {
        id: tempId,
        memoDate: payload.memoDate ?? today(),
        content: payload.content,
        // mood_enabled = false の場合、サーバー側で silent NULL 化されるため
        // ローカルでも同様に NULL にしておく
        mood: this.settings.moodEnabled ? (payload.mood ?? null) : null,
        relatedTodoId: payload.relatedTodoId ?? null,
        timelinePostId: null,
        teamTimelinePostId: null,
        tags: [],
        createdAt: nowIso(),
        // Phase 3 フィールド
        category: payload.category ?? 'OTHER',
        durationMinutes: payload.durationMinutes ?? null,
        progressRate: payload.progressRate ?? null,
        completesTodo: payload.completesTodo ?? false,
        postedTeamId: null,
        organizationId: payload.organizationId ?? this.pendingOrgScope.organizationId,
        orgVisibility: payload.orgVisibility ?? this.pendingOrgScope.orgVisibility,
      }
      this.memos.unshift(optimistic)
      this.error = null
      this.lastError = null

      // オフラインキュー判定: navigator.onLine が false ならキューに積む
      if (this._isOffline()) {
        try {
          const queue = useOfflineQueue()
          // publishTeamId はオフライン中は実 ID が無いため、キューに保持して flush 後に publish-to-team する
          await queue.enqueue(payload, tempId, options.publishTeamId)
          this.offlineQueueCount = await queue.count()
          this.isOffline = true
          // 下書きはクリアする（キューに積んだ時点で「ユーザーの入力」は成立）
          this.clearDraft(this._currentUserIdOrAnon())
          // 仮 ID のまま楽観的 UI を返す
          return optimistic
        } catch (error) {
          // キューイングにも失敗したらロールバック
          const idx = this.memos.findIndex((m) => m.id === tempId)
          if (idx >= 0) this.memos.splice(idx, 1)
          this._handleError(error)
          return null
        }
      }

      try {
        const api = useActionMemoApi()
        // Phase 4-α: pendingOrgScope をペイロードにマージ
        const enrichedPayload: CreateActionMemoPayload = {
          ...payload,
          organizationId: payload.organizationId ?? this.pendingOrgScope.organizationId ?? undefined,
          orgVisibility: payload.orgVisibility ?? this.pendingOrgScope.orgVisibility ?? undefined,
        }
        const created = await api.createMemo(enrichedPayload)
        // 一時 ID を本物に置き換え
        const idx = this.memos.findIndex((m) => m.id === tempId)
        if (idx >= 0) {
          this.memos.splice(idx, 1, created)
        } else {
          this.memos.unshift(created)
        }
        // 送信成功 → 下書きクリア
        this.clearDraft(this._currentUserIdOrAnon())
        return created
      } catch (error) {
        // ロールバック
        const idx = this.memos.findIndex((m) => m.id === tempId)
        if (idx >= 0) this.memos.splice(idx, 1)
        this._handleError(error)
        return null
      }
    },

    /**
     * オフラインキューを順次送信する。
     *
     * <p>成功した項目ごとに仮 ID を本物の ID に置き換える。{@code navigator.onLine} が
     * {@code true} の場合にのみ呼ばれる想定（online イベントや手動同期ボタン）。</p>
     *
     * @returns 成功件数
     */
    async flushOfflineQueue(): Promise<number> {
      const queue = useOfflineQueue()
      const api = useActionMemoApi()
      const kept: { v: { error: string | null; lastError: ActionMemoErrorCode | null } | null } = { v: null }
      const results = await queue.flushQueue(async (payload, queued) => {
        try {
          // 公開のみ項目: 作成済みメモは再作成せず publish-to-team だけ再試行する
          if (queued?.publishOnlyMemoId != null && queued.publishTeamId != null) {
            let ok = await this.publishToTeam(queued.publishOnlyMemoId, { teamId: queued.publishTeamId })
            if (!ok && this.alreadyPostedFailure) {
              // 既投稿（ACTION_MEMO_ALREADY_POSTED）: 再試行しても永久に成功しないため、実態を確認して項目を片付ける
              const memo = await api.getMemo(queued.publishOnlyMemoId)
              ok = true // 項目は削除する（止まり続けない）。別チーム投稿済みならエラーだけ残す
              if (memo.postedTeamId !== queued.publishTeamId) {
                kept.v = { error: 'action_memo.error.publish_failed', lastError: 'PUBLISH_FAILED' }
              } else {
                this.error = null
                this.lastError = null
              }
            }
            // 後続項目の publishToTeam が error を消すため、失敗した時点で退避する
            else if (!ok) {
              kept.v = { error: this.error, lastError: this.lastError }
              // 403/404/410 は再試行しても成功しない恒久失敗（メモ削除済み・チーム脱退など）。
              // 項目を片付けて後続を処理させる（失敗は kept のエラーとして利用者に見せる）。通信断・5xx は残して停止
              if ([403, 404, 410].includes(this.publishFailureStatus ?? 0)) ok = true
            }
            return ok ? { id: queued.publishOnlyMemoId } : null
          }
          const created = await api.createMemo(payload)
          // オフライン中に選ばれた公開要求は、実 ID が得られた今ここで実行する。
          // 作成は成立済みのため created を返して重複作成を避け、公開失敗は「公開のみ」項目として残す
          if (queued?.publishTeamId != null) {
            const ok = await this.publishToTeam(created.id, { teamId: queued.publishTeamId })
            if (!ok) {
              kept.v = { error: this.error, lastError: this.lastError }
              try {
                await queue.enqueuePublishOnly(created.id, queued.publishTeamId)
              } catch {
                // 公開のみ項目を永続化できなくても作成は成立済み。再作成を避けるため完了扱いとし、
                // 公開失敗は error として残す（kept で最後に復元）
              }
            }
          }
          return created
        } catch (error) {
          this._handleError(error)
          return null
        }
      })
      // 仮 ID を本物の ID に置換するため、一度だけ fetchMemos で再取得する
      // （flushQueue の返す createdId だけでは、並列送信された別デバイスのメモや
      //  同日の並び順を正しく反映できないため）
      // fetchMemosForDate が error を消すため、公開失敗のエラーは退避して復元する
      if (results.length > 0) {
        const targetDate = results[0]?.queued.payload.memoDate ?? today()
        await this.fetchMemosForDate(targetDate)
      }
      if (kept.v) {
        this.error = kept.v.error
        this.lastError = kept.v.lastError
      }
      this.offlineQueueCount = await queue.count()
      this.isOffline = this._isOffline()
      return results.length
    },

    /**
     * オフラインキュー件数を再計算する（UI バナー更新用）。
     */
    async refreshOfflineQueueCount(): Promise<void> {
      const queue = useOfflineQueue()
      this.offlineQueueCount = await queue.count()
      this.isOffline = this._isOffline()
    },

    /**
     * 指定日のメモ一覧を取得する。
     */
    async fetchMemosForDate(date: string): Promise<void> {
      this.loading = true
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        const res = await api.fetchMemos({ date, limit: 200 })
        this.memos = res.data
      } catch (error) {
        this._handleError(error)
      } finally {
        this.loading = false
      }
    },

    /**
     * メモを部分更新する。
     */
    async updateMemo(id: number, payload: UpdateActionMemoPayload): Promise<ActionMemo | null> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        const updated = await api.updateMemo(id, payload)
        const idx = this.memos.findIndex((m) => m.id === id)
        if (idx >= 0) this.memos.splice(idx, 1, updated)
        return updated
      } catch (error) {
        this._handleError(error)
        return null
      }
    },

    /**
     * メモを論理削除する。楽観的に list から取り除く。
     */
    async deleteMemo(id: number): Promise<boolean> {
      const previous = this.memos.slice()
      this.memos = this.memos.filter((m) => m.id !== id)
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        await api.deleteMemo(id)
        return true
      } catch (error) {
        // ロールバック
        this.memos = previous
        this._handleError(error)
        return false
      }
    },

    /**
     * 設定を取得する。レコード未作成のユーザーはデフォルト値が返る。
     */
    async fetchSettings(): Promise<void> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        this.settings = await api.getSettings()
      } catch (error) {
        this._handleError(error)
      }
    },

    /**
     * 設定を更新する（UPSERT）。
     */
    async updateSettings(patch: Partial<ActionMemoSettings>): Promise<void> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        this.settings = await api.updateSettings(patch)
      } catch (error) {
        this._handleError(error)
      }
    },

    /**
     * 終業時まとめ投稿を実行する。
     *
     * <p>設計書 §5.4 の「今日を締める」儀式。成功時は対象日のメモ一覧を再取得し、
     * {@code timelinePostId} を反映する。失敗時は呼び出し側（closing.vue）で
     * トースト表示できるようエラーを再 throw する。</p>
     *
     * @param payload {@code memoDate} / {@code extraComment} 任意
     */
    async publishDaily(payload: PublishDailyPayload = {}): Promise<PublishDailyResponse> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        const response = await api.publishDaily(payload)
        // 各メモの timeline_post_id を反映するため対象日を再取得する
        const targetDate = payload.memoDate ?? response.memoDate ?? today()
        await this.fetchMemosForDate(targetDate)
        return response
      } catch (error) {
        this._handleError(error)
        throw error
      }
    },

    // === Tag CRUD (Phase 4) ===

    /**
     * 自分のタグ一覧を取得する（論理削除済みは含まない）。
     */
    async fetchTags(): Promise<void> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        this.tags = await api.getTags()
      } catch (error) {
        this._handleError(error)
      }
    },

    /**
     * タグを作成する。
     */
    async createTag(payload: CreateTagPayload): Promise<ActionMemoTag | null> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        const created = await api.createTag(payload)
        this.tags.push(created)
        return created
      } catch (error) {
        this._handleError(error)
        return null
      }
    },

    /**
     * タグを更新する（名前・色）。
     */
    async updateTag(tagId: number, payload: UpdateTagPayload): Promise<ActionMemoTag | null> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        const updated = await api.updateTag(tagId, payload)
        const idx = this.tags.findIndex((t) => t.id === tagId)
        if (idx >= 0) this.tags.splice(idx, 1, updated)
        return updated
      } catch (error) {
        this._handleError(error)
        return null
      }
    },

    /**
     * タグを論理削除する。復活機能なし（§11 #9）。
     */
    async deleteTag(tagId: number): Promise<boolean> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        await api.deleteTag(tagId)
        this.tags = this.tags.filter((t) => t.id !== tagId)
        return true
      } catch (error) {
        this._handleError(error)
        return false
      }
    },

    /**
     * メモにタグを追加する（複数可）。
     */
    async addTagsToMemo(memoId: number, tagIds: number[]): Promise<boolean> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        await api.addTagsToMemo(memoId, tagIds)
        return true
      } catch (error) {
        this._handleError(error)
        return false
      }
    },

    /**
     * メモからタグを除去する。
     */
    async removeTagFromMemo(memoId: number, tagId: number): Promise<boolean> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        await api.removeTagFromMemo(memoId, tagId)
        return true
      } catch (error) {
        this._handleError(error)
        return false
      }
    },

    // === Mood stats (Phase 4) ===

    /**
     * 期間内の気分分布を取得する。
     */
    async fetchMoodStats(from: string, to: string): Promise<void> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        this.moodStats = await api.getMoodStats({ from, to })
      } catch (error) {
        this._handleError(error)
      }
    },

    // === Weekly Summary (Phase 3) ===

    /**
     * 週次まとめ一覧を取得する。
     *
     * <p>F06.1 BlogPost API を流用し、タイトルプレフィックス「週次ふりかえり: 」で
     * クライアント側フィルタする（設計書 §11 #11）。</p>
     *
     * @param page ページ番号（0始まり）。省略時は 0
     */
    async fetchWeeklySummaries(page = 0): Promise<void> {
      this.weeklyLoading = true
      this.weeklyError = null
      try {
        const api = useActionMemoApi()
        const res = await api.fetchWeeklySummaries({ page, size: 20 })
        if (page === 0) {
          this.weeklySummaries = res.data
        } else {
          this.weeklySummaries = [...this.weeklySummaries, ...res.data]
        }
        this.weeklyPage = res.page
        this.weeklyTotalPages = res.totalPages
      } catch {
        this.weeklyError = 'action_memo.weekly.error'
      } finally {
        this.weeklyLoading = false
      }
    },

    // === Phase 3: Available Teams ===

    /**
     * チーム投稿先候補を取得する。
     * {@code GET /api/v1/action-memos/available-teams}
     */
    async fetchAvailableTeams(): Promise<boolean> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        this.availableTeams = await api.fetchAvailableTeams()
        return true
      } catch (error) {
        this._handleError(error)
        return false
      }
    },

    // === Phase 5-2: Available Orgs ===

    /**
     * 組織スコープ投稿先候補を取得する。
     * {@code GET /api/v1/action-memos/available-orgs}
     */
    async fetchAvailableOrgs(): Promise<void> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        this.availableOrgs = await api.fetchAvailableOrgs()
      } catch (error) {
        this._handleError(error)
      }
    },

    // === Phase 3: Publish to team ===

    /**
     * 個別メモをチームタイムラインに投稿する。
     * {@code POST /api/v1/action-memos/{memoId}/publish-to-team}
     *
     * <p>成功後: memos 配列内の該当メモの {@code postedTeamId} を楽観的に更新する。</p>
     */
    async publishToTeam(memoId: number, payload: PublishToTeamPayload): Promise<boolean> {
      this.error = null
      this.lastError = null
      this.alreadyPostedFailure = false
      this.publishFailureStatus = null
      try {
        const api = useActionMemoApi()
        await api.publishToTeam(memoId, payload)
        // 楽観的 UI: postedTeamId を更新
        const idx = this.memos.findIndex((m) => m.id === memoId)
        const existingMemo = idx >= 0 ? this.memos[idx] : undefined
        if (existingMemo && payload.teamId) {
          const updated = { ...existingMemo, postedTeamId: payload.teamId }
          this.memos.splice(idx, 1, updated)
        }
        return true
      } catch (error) {
        // 既投稿は flush 側で実態確認して片付けるため、判別できるよう印を残す
        // BE の ErrorResponse は { error: { code } }（ofetch では data と response._data の両方に載る）
        const err = error as {
          status?: number
          statusCode?: number
          response?: { status?: number; _data?: { error?: { code?: string } } }
          data?: { error?: { code?: string } }
        }
        const code = err?.data?.error?.code ?? err?.response?._data?.error?.code
        this.alreadyPostedFailure = code === 'ACTION_MEMO_016'
        this.publishFailureStatus = err?.status ?? err?.statusCode ?? err?.response?.status ?? null
        // 公開失敗はメモ保存の失敗ではない（メモは作成済み）。保存失敗文言を出さないよう専用の種別にする
        this.lastError = 'PUBLISH_FAILED'
        this.error = 'action_memo.error.publish_failed'
        return false
      }
    },

    /**
     * 今日の WORK メモを一括チーム投稿する。
     * {@code POST /api/v1/action-memos/publish-daily-to-team}
     */
    async publishDailyToTeam(payload: PublishDailyToTeamPayload = {}): Promise<boolean> {
      this.error = null
      this.lastError = null
      try {
        const api = useActionMemoApi()
        await api.publishDailyToTeam(payload)
        return true
      } catch (error) {
        this._handleError(error)
        return false
      }
    },

    // === Draft ===

    /** localStorage から下書き本文を復元（旧形式の文字列のみの下書きも本文として読む） */
    loadDraft(userId: number | string): string {
      return readDraft(userId).content
    },

    /** 下書きと一緒に保存した選択値（カテゴリ・投稿先・所要時間など）を復元。旧形式・未保存は null */
    loadDraftExtras(userId: number | string): ActionMemoInputExtras | null {
      return readDraft(userId).extras
    },

    /** 作成済みだが公開に失敗したメモ（画面を離れて戻っても重複作成せず公開だけ再試行するため下書きに持つ）。無ければ null */
    loadDraftPendingPublish(userId: number | string): DraftPendingPublish | null {
      return readDraft(userId).pendingPublish
    },

    /** localStorage に下書きを保存（extras があれば選択値も、pendingPublish があれば公開待ちも一緒に保存する） */
    saveDraft(
      userId: number | string,
      content: string,
      extras?: ActionMemoInputExtras,
      pendingPublish?: DraftPendingPublish | null,
    ): void {
      if (typeof window === 'undefined') return
      try {
        if (content && content.length > 0) {
          localStorage.setItem(
            draftKey(userId),
            extras ? JSON.stringify({ v: 1, content, extras, pendingPublish: pendingPublish ?? undefined }) : content,
          )
        } else {
          localStorage.removeItem(draftKey(userId))
        }
      } catch {
        // Storage quota or disabled — silently ignore
      }
    },

    /** 下書きをクリア */
    clearDraft(userId: number | string): void {
      if (typeof window === 'undefined') return
      try {
        localStorage.removeItem(draftKey(userId))
      } catch {
        // ignore
      }
    },

    // === 内部ユーティリティ ===

    _currentUserIdOrAnon(): number | string {
      try {
        const auth = useAuthStore()
        return auth.user?.id ?? 'anon'
      } catch {
        return 'anon'
      }
    },

    /**
     * {@code navigator.onLine === false} の判定。SSR 時は false（オンライン扱い）。
     */
    _isOffline(): boolean {
      if (typeof navigator === 'undefined') return false
      return navigator.onLine === false
    },

    /**
     * fetch 系エラーを {@link ActionMemoErrorCode} に分類して state に書き出す。
     * 429 は {@link ActionMemoRateLimitError} 由来、それ以外は HTTP status / メッセージで判定。
     */
    _handleError(error: unknown): void {
      const err = error as {
        status?: number
        response?: { status?: number; _data?: { message?: string; code?: string } }
        message?: string
      }
      const status = err?.status ?? err?.response?.status
      const message = err?.response?._data?.message ?? err?.message ?? 'unknown'

      if (status === 429) {
        this.lastError = 'RATE_LIMIT'
        this.error = 'action_memo.error.rate_limit'
        return
      }
      if (status === 400) {
        if (/200/.test(message) || /daily/i.test(message) || /上限/.test(message)) {
          this.lastError = 'DAILY_LIMIT'
          this.error = 'action_memo.error.daily_limit'
          return
        }
        if (/future/i.test(message) || /未来/.test(message)) {
          this.lastError = 'FUTURE_DATE'
          this.error = 'action_memo.error.future_date'
          return
        }
      }
      if (status === 404) {
        this.lastError = 'TODO_NOT_FOUND'
        this.error = 'action_memo.error.todo_not_found'
        return
      }
      this.lastError = 'UNKNOWN'
      this.error = 'action_memo.error.save_failed'
    },

    // === Phase 4-α: org scope ===

    /**
     * 次回の createMemo に付与する組織スコープを設定する。
     * index.vue の org selector パネルから呼ばれる。
     */
    setPendingOrgScope(organizationId: number | null, orgVisibility: OrgVisibility | null): void {
      this.pendingOrgScope = { organizationId, orgVisibility }
    },
  },
})

// === エクスポート（テスト用にユーティリティを公開） ===
export const __actionMemoInternals = {
  draftKey,
  today,
}

/** 型再エクスポート（呼び出し側の便宜のため） */
export type { ActionMemo, ActionMemoSettings, Mood }
