import type {
  ConfirmableNotificationDraftDetail,
  ConfirmableNotificationDraftSaveRequest,
  ConfirmableNotificationDraftSummary,
} from '~/types/confirmable'
import { CONFIRMABLE_DRAFT_LIMIT } from '~/types/confirmable'

/** 入力が止まってから自動保存するまでの待ち時間（ms） */
export const CONFIRMABLE_DRAFT_AUTOSAVE_MS = 3000

const ERROR_NOT_FOUND = 'CONFIRMABLE_NOTIFICATION_DRAFT_NOT_FOUND'
const ERROR_LIMIT = 'CONFIRMABLE_NOTIFICATION_DRAFT_LIMIT_EXCEEDED'
const ERROR_CONFLICT = 'CONFIRMABLE_NOTIFICATION_DRAFT_VERSION_CONFLICT'

interface ApiErrorShape {
  status?: number
  statusCode?: number
  data?: { error?: { code?: string } }
}

function errorCode(error: unknown): string | undefined {
  return (error as ApiErrorShape)?.data?.error?.code
}

function errorStatus(error: unknown): number | undefined {
  const e = error as ApiErrorShape
  return e?.statusCode ?? e?.status
}

export interface UseConfirmableDraftOptions {
  scopeType: () => 'TEAM' | 'ORGANIZATION'
  scopeId: () => string
  /** フォームの現在値を下書きの保存形で返す（キー順は常に同じにすること。内容が変わったかの判定に使う） */
  snapshot: () => ConfirmableNotificationDraftSaveRequest
  /** 下書きの内容をフォームへ反映する。null は空のフォーム（新規状態） */
  apply: (detail: ConfirmableNotificationDraftDetail | null) => void
}

/**
 * 確認通知の下書き（DB 保存・複数件・上限10件。CMP-261126-1558）。
 *
 * - 開いた直後は新規状態。最初の入力から 3 秒で POST、以後は同じ id へ PUT（baseVersion は応答の version）。
 * - 保存は 1 本の列に並べて直列に実行する（POST の応答待ちに入力が続いても二重に作らない）。
 * - 別の下書きを開く・新しく書く前には、保存待ちの内容を先に保存する。
 * - 409 VERSION_CONFLICT は自動保存を止めて利用者に選ばせる（黙って上書きしない）。
 */
export function useConfirmableDraft(options: UseConfirmableDraftOptions) {
  const api = useConfirmableNotificationApi()
  const { handleApiError } = useErrorHandler()
  const confirm = useConfirm()
  const { t } = useI18n()

  const drafts = ref<ConfirmableNotificationDraftSummary[]>([])
  const currentId = ref<string | null>(null)
  const version = ref(0)
  const conflict = ref(false)

  /** 一覧が上限に達していて、新しい下書きを作れない（開いている下書きの更新は可能） */
  const limitReached = computed(() => currentId.value === null && drafts.value.length >= CONFIRMABLE_DRAFT_LIMIT)

  const signature = computed(() => JSON.stringify(options.snapshot()))
  /** サーバーに保存済み（または空の新規）の内容。空文字は「保存済みと一致しない」の印 */
  let baseline = signature.value
  /** 空のフォームの内容。これと同じなら失って困る入力は無い */
  let blankSignature = baseline
  let timer: ReturnType<typeof setTimeout> | null = null
  let chain: Promise<void> = Promise.resolve()
  let generation = 0
  /** 送信の開始から結果が出るまでは自動保存しない（送って消える下書きへ PUT/POST しない） */
  let suspended = false
  /** API を呼ぶ先のスコープ。送信先が変わったときは、前のスコープへ保存を済ませてから切り替える */
  let scopeType = options.scopeType()
  let scopeId = options.scopeId()

  /** 保存できていない（失うと困る）入力があるか */
  function hasUnsavedChanges(): boolean {
    const sig = signature.value
    return sig !== baseline && sig !== blankSignature
  }

  function clearTimer() {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  function enqueue(task: () => Promise<void>): Promise<void> {
    chain = chain.then(task)
    return chain
  }

  function schedule() {
    clearTimer()
    timer = setTimeout(() => {
      timer = null
      void enqueue(() => runSave(false))
    }, CONFIRMABLE_DRAFT_AUTOSAVE_MS)
  }

  async function reloadList() {
    const gen = generation
    try {
      const response = await api.listDrafts(scopeType, scopeId)
      if (gen === generation) drafts.value = response.data
    } catch (error) {
      handleApiError(error, 'confirmable draft list')
    }
  }

  /** フォームを新規状態にして、紐付けと競合表示を外す */
  function resetToBlank() {
    clearTimer()
    options.apply(null)
    currentId.value = null
    version.value = 0
    conflict.value = false
    baseline = signature.value
    blankSignature = baseline
  }

  /**
   * 開いていた下書きが他の画面で消えていた。入力中の内容は消さず、紐付けだけ外す
   * （baseline を空にして、次の入力で POST し直す）。
   */
  async function detachGone() {
    clearTimer()
    currentId.value = null
    version.value = 0
    conflict.value = false
    baseline = ''
    await reloadList()
  }

  async function runSave(force: boolean) {
    if (suspended) return
    if (conflict.value && !force) return
    const sig = signature.value
    if (!force && sig === baseline) return
    const payload = options.snapshot()
    const gen = generation
    const draftId = currentId.value
    try {
      if (draftId) {
        const response = await api.updateDraft(scopeType, scopeId, draftId, { ...payload, baseVersion: version.value })
        if (gen !== generation) return
        version.value = response.data.version ?? version.value
        baseline = sig
        conflict.value = false
      } else {
        if (drafts.value.length >= CONFIRMABLE_DRAFT_LIMIT) return
        const response = await api.createDraft(scopeType, scopeId, payload)
        if (gen !== generation) return
        currentId.value = response.data.id ?? null
        version.value = response.data.version ?? 0
        baseline = sig
      }
      await reloadList()
    } catch (error) {
      if (gen !== generation) return
      const status = errorStatus(error)
      const code = errorCode(error)
      if (draftId && (status === 404 || code === ERROR_NOT_FOUND)) {
        await detachGone()
      } else if (draftId && (code === ERROR_CONFLICT || (status === 409 && code !== ERROR_LIMIT))) {
        clearTimer()
        conflict.value = true
      } else if (!draftId && code === ERROR_LIMIT) {
        await reloadList()
      } else {
        handleApiError(error, 'confirmable draft save')
      }
    }
  }

  watch(signature, (sig) => {
    if (conflict.value || suspended) return
    if (sig === baseline) {
      clearTimer()
      return
    }
    schedule()
  })

  /** 保存待ちの内容を先に保存してから、実行中の保存の終わりを待つ（切り替え前に使う） */
  async function flushPending() {
    clearTimer()
    await chain
    if (!suspended && !conflict.value && signature.value !== baseline) await enqueue(() => runSave(false))
  }

  /**
   * 切り替えの前に保存を済ませる。上限・5xx・競合などで保存できていない入力が残るときは、
   * 黙って捨てずに確認を出し、承認されたときだけ proceed する（拒否なら何もしない）。
   */
  async function switchAfterSave(proceed: () => Promise<void> | void) {
    await flushPending()
    if (!hasUnsavedChanges()) {
      await proceed()
      return
    }
    confirm.require({
      header: t('confirmable.draft.unsaved_header'),
      message: t('confirmable.draft.unsaved_confirm'),
      icon: 'pi pi-exclamation-triangle',
      rejectLabel: t('button.cancel'),
      acceptLabel: t('confirmable.draft.discard_and_switch'),
      acceptClass: 'p-button-danger',
      accept: () => proceed(),
    })
  }

  function newDraft() {
    return switchAfterSave(resetToBlank)
  }

  function openDraft(draftId: string) {
    return switchAfterSave(() => loadDraft(draftId))
  }

  async function loadDraft(draftId: string) {
    const gen = generation
    try {
      const response = await api.getDraft(scopeType, scopeId, draftId)
      if (gen !== generation) return
      options.apply(response.data)
      currentId.value = response.data.id ?? draftId
      version.value = response.data.version ?? 0
      conflict.value = false
      baseline = signature.value
    } catch (error) {
      if (errorStatus(error) === 404 || errorCode(error) === ERROR_NOT_FOUND) {
        resetToBlank()
        await reloadList()
      } else {
        handleApiError(error, 'confirmable draft open')
      }
    }
  }

  async function deleteDraft(draftId: string) {
    const wasCurrent = currentId.value === draftId
    if (wasCurrent) clearTimer()
    await chain
    try {
      await api.deleteDraft(scopeType, scopeId, draftId)
    } catch (error) {
      if (!(errorStatus(error) === 404 || errorCode(error) === ERROR_NOT_FOUND)) {
        handleApiError(error, 'confirmable draft delete')
        return
      }
    }
    if (wasCurrent && currentId.value === draftId) resetToBlank()
    await reloadList()
    // 上限で保存できずにいた入力は、空きができたら保存し直す
    resumeIfUnsaved()
  }

  function resumeIfUnsaved() {
    if (!suspended && !conflict.value && timer === null && signature.value !== baseline) schedule()
  }

  function requestDelete(draftId: string) {
    confirm.require({
      header: t('confirmable.draft.delete_header'),
      message: t('confirmable.draft.delete_confirm'),
      icon: 'pi pi-exclamation-triangle',
      rejectLabel: t('button.cancel'),
      acceptLabel: t('button.delete'),
      acceptClass: 'p-button-danger',
      accept: () => deleteDraft(draftId),
    })
  }

  /** 競合: 他の画面の最新を読み込み、フォームを置き換える */
  async function reloadLatest() {
    const draftId = currentId.value
    if (!draftId) return
    await chain
    try {
      const response = await api.getDraft(scopeType, scopeId, draftId)
      options.apply(response.data)
      version.value = response.data.version ?? 0
      conflict.value = false
      baseline = signature.value
    } catch (error) {
      if (errorStatus(error) === 404 || errorCode(error) === ERROR_NOT_FOUND) await detachGone()
      else handleApiError(error, 'confirmable draft reload')
    }
  }

  /** 競合: 最新の version を取り直したうえで、手元の内容で上書き保存する */
  async function overwriteWithMine() {
    const draftId = currentId.value
    if (!draftId) return
    await chain
    try {
      const response = await api.getDraft(scopeType, scopeId, draftId)
      version.value = response.data.version ?? 0
    } catch (error) {
      if (errorStatus(error) === 404 || errorCode(error) === ERROR_NOT_FOUND) await detachGone()
      else handleApiError(error, 'confirmable draft overwrite')
      return
    }
    await enqueue(() => runSave(true))
  }

  /** 送信の直前: 自動保存の待ちを取り消し、実行中の保存が終わるのを待つ（draftId を確定させる） */
  async function settleForSend(): Promise<string | undefined> {
    clearTimer()
    await chain
    suspended = true
    return currentId.value ?? undefined
  }

  /** 送信成功: BE が下書きを消しているので、新規状態に戻して一覧を取り直す */
  async function afterSent() {
    suspended = false
    resetToBlank()
    await reloadList()
  }

  /** 送信失敗: 取り消していた自動保存の待ちを、未保存の変更があれば再開する */
  function resumeAfterFailedSend() {
    suspended = false
    resumeIfUnsaved()
  }

  function init() {
    return reloadList()
  }

  /** 送信先が変わったら、別スコープの下書きとの紐付けを外して一覧を取り直す */
  async function resetScope() {
    // 前のスコープで保存待ちの内容は、前のスコープへ保存し終えてから切り替える
    await flushPending()
    generation += 1
    scopeType = options.scopeType()
    scopeId = options.scopeId()
    suspended = false
    resetToBlank()
    drafts.value = []
    await reloadList()
  }

  // 画面を離れるときも、保存待ちの内容は取りこぼさず保存する
  onBeforeUnmount(() => { void flushPending() })

  return {
    drafts,
    currentId,
    conflict,
    limitReached,
    init,
    resetScope,
    newDraft,
    openDraft,
    requestDelete,
    reloadLatest,
    overwriteWithMine,
    settleForSend,
    afterSent,
    resumeAfterFailedSend,
  }
}
