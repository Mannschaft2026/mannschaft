/**
 * シフト機能の出し分け判定を 1 か所にまとめる（CMP-261125-2114）。
 *
 * <h2>BE の確定仕様（ShiftManageAccessGate）</h2>
 * <ul>
 *   <li><b>管理操作</b>（作成・更新・削除・公開・割当・承認・自動割当・時給設定・勤務制約・ポジション・設定変更）は
 *       チーム ADMIN、または MANAGE_SHIFTS 権限を持つ DEPUTY_ADMIN だけに許す。
 *       DEPUTY_ADMIN だというだけでは許さない。SYSTEM_ADMIN も特別扱いしない（チームロールが無ければ拒否）。</li>
 *   <li><b>閲覧</b>（未公開シフト表・割当の非マスク・他人の交代/変更依頼・希望一覧・PDF 管理者出力 等）は、管理者に加えて、
 *       チームのシフト設定の切替 {@code deputyShiftViewEnabled} が ON のとき MANAGE_SHIFTS 無しの DEPUTY_ADMIN にも許す。
 *       他メンバーの時給の閲覧は {@code deputyHourlyRateViewEnabled}。切替は閲覧だけで、操作は常に MANAGE_SHIFTS が必要。</li>
 * </ul>
 *
 * <p>ここで行うのは「BE が必ず弾く操作を画面に出さない」ことだけで、認可の代替ではない（BE のガードは外さない）。
 * 権限・設定の取得に失敗したときは権限なしに倒れる（見せて 403 で弾かれるより安全側）が、失敗は呼び出し元へ返す。</p>
 */

import { computed, onMounted, ref, toValue, watch, type MaybeRefOrGetter } from 'vue'

/** BE の ShiftManageAccessGate#MANAGE_SHIFTS と同一。 */
export const MANAGE_SHIFTS_PERMISSION = 'MANAGE_SHIFTS'

export interface ShiftPermissionInput {
  /** useRoleAccess の roleName（未取得・取得失敗は null）。 */
  roleName: string | null
  /** 対象チームの ADMIN 割当があるか（SYSTEM_ADMIN は含まない）。 */
  isScopeAdmin: boolean
  /** /me/permissions の permissions（ロール既定＋権限グループ）。 */
  permissions: readonly string[]
  /** チームのシフト設定: MANAGE_SHIFTS 無しの副管理者にシフト閲覧を許すか。 */
  deputyShiftViewEnabled: boolean
  /** チームのシフト設定: MANAGE_SHIFTS 無しの副管理者に他メンバーの時給閲覧を許すか。 */
  deputyHourlyRateViewEnabled: boolean
}

export interface ShiftPermissionResult {
  /** 管理操作（ボタン・編集画面）を出してよいか。 */
  canManageShift: boolean
  /** 管理者の形でのシフト閲覧（未公開・希望一覧・他人の依頼 等）を出してよいか。管理者を含む。 */
  canViewShift: boolean
  /** 他メンバーの時給を閲覧してよいか。管理者を含む。 */
  canViewHourlyRate: boolean
  /** MANAGE_SHIFTS を持たない副管理者か（操作できない理由の案内を出す対象）。 */
  isDeputyWithoutManage: boolean
}

/** 判定の純関数（真理値表の単体テスト対象）。 */
export function resolveShiftPermission(input: ShiftPermissionInput): ShiftPermissionResult {
  const canManageShift = input.isScopeAdmin || input.permissions.includes(MANAGE_SHIFTS_PERMISSION)
  const isDeputy = input.roleName === 'DEPUTY_ADMIN'
  return {
    canManageShift,
    canViewShift: canManageShift || (isDeputy && input.deputyShiftViewEnabled),
    canViewHourlyRate: canManageShift || (isDeputy && input.deputyHourlyRateViewEnabled),
    isDeputyWithoutManage: !canManageShift && isDeputy,
  }
}

/**
 * チームのシフト権限を取得して判定する composable。
 *
 * @param teamSlug 対象チームの slug（未確定は null / 空。スコープ（チーム）が切り替わったら取り直す）
 * @param options.autoLoad マウント時に権限を自動取得するか（既定 true）。呼び出し側が loadPermissions() を
 *                         自分で呼ぶ画面では false にして、権限取得を二重に撃たない。
 */
export function useShiftPermission(
  teamSlug: MaybeRefOrGetter<string | null | undefined>,
  options: { autoLoad?: boolean } = {},
) {
  const slugRef = computed(() => toValue(teamSlug) ?? '')
  const access = useRoleAccess('team', slugRef)
  const { getShiftSettings } = useTeamShiftSettingsApi()
  const { handleApiError } = useErrorHandler()

  const deputyShiftViewEnabled = ref(false)
  const deputyHourlyRateViewEnabled = ref(false)
  /** 閲覧切替の取得に失敗したか（失敗時は OFF 扱いに倒す）。 */
  const settingsLoadFailed = ref(false)
  let settingsSeq = 0
  let settingsKey: string | null = null
  let settingsPromise: Promise<void> | null = null

  const resolved = computed(() => resolveShiftPermission({
    roleName: access.roleName.value,
    isScopeAdmin: access.isScopeAdmin.value,
    permissions: access.permissions.value,
    deputyShiftViewEnabled: deputyShiftViewEnabled.value,
    deputyHourlyRateViewEnabled: deputyHourlyRateViewEnabled.value,
  }))

  /** 切替の値に依存しない（切替の取得結果で再評価が回らないよう、判定材料だけから作る）。 */
  const needsSettings = computed(() => access.roleName.value === 'DEPUTY_ADMIN'
    && !access.isScopeAdmin.value
    && !access.permissions.value.includes(MANAGE_SHIFTS_PERMISSION))

  async function fetchSettings(slug: string, seq: number): Promise<void> {
    try {
      const res = await getShiftSettings(slug)
      if (seq !== settingsSeq) return
      deputyShiftViewEnabled.value = res.deputyShiftViewEnabled === true
      deputyHourlyRateViewEnabled.value = res.deputyHourlyRateViewEnabled === true
    }
    catch (error) {
      if (seq !== settingsSeq) return
      // 失敗は OFF 扱い（安全側）に倒すが、握りつぶさず利用者へ通知し、呼び出し元にも失敗フラグを返す。
      settingsLoadFailed.value = true
      handleApiError(error)
    }
  }

  function resetSettings() {
    ++settingsSeq
    settingsKey = null
    settingsPromise = null
    deputyShiftViewEnabled.value = false
    deputyHourlyRateViewEnabled.value = false
    settingsLoadFailed.value = false
  }

  /**
   * 閲覧切替は MANAGE_SHIFTS 無しの副管理者の判定にだけ効くので、その人のときだけ取得する
   * （管理者・一般メンバーに無駄な要求を撃たない）。同じチームで取得済み・取得中なら再取得しない。
   */
  function loadSettingsIfNeeded(): Promise<void> {
    const slug = slugRef.value
    if (!slug || !needsSettings.value) {
      resetSettings()
      return Promise.resolve()
    }
    if (settingsKey === slug && settingsPromise) return settingsPromise
    resetSettings()
    settingsKey = slug
    settingsPromise = fetchSettings(slug, settingsSeq)
    return settingsPromise
  }

  // 権限が確定・変化したら（チーム切替や自動取得を含む）、必要なときだけ閲覧切替を取得する。
  watch(
    () => [access.roleResolved.value, needsSettings.value, slugRef.value] as const,
    ([roleResolved]) => {
      if (roleResolved) void loadSettingsIfNeeded()
      else resetSettings()
    },
    { immediate: true },
  )

  /**
   * 権限と（副管理者のときだけ）閲覧切替を取得する。権限取得の成否をそのまま返す
   * （取得失敗を「権限なし」と区別するため。useRoleAccess#loadPermissions と同じ）。
   */
  async function loadPermissions() {
    const result = await access.loadPermissions()
    if (result.ok) await loadSettingsIfNeeded()
    return result
  }

  onMounted(() => {
    if (options.autoLoad === false) return
    if (slugRef.value && !access.roleResolved.value && !access.loading.value) void access.loadPermissions()
  })

  return {
    canManageShift: computed(() => resolved.value.canManageShift),
    canViewShift: computed(() => resolved.value.canViewShift),
    canViewHourlyRate: computed(() => resolved.value.canViewHourlyRate),
    isDeputyWithoutManage: computed(() => resolved.value.isDeputyWithoutManage),
    /** 対象チームの ADMIN 割当があるか（権限変更の告知を出す対象）。SYSTEM_ADMIN は含まない。 */
    isScopeAdmin: access.isScopeAdmin,
    /** 会員として書き込める（希望提出・変更依頼）か。ADMIN / DEPUTY_ADMIN / MEMBER。 */
    isMember: access.isMember,
    roleName: access.roleName,
    permissions: access.permissions,
    roleResolved: access.roleResolved,
    roleLoadFailed: access.roleLoadFailed,
    deputyShiftViewEnabled,
    deputyHourlyRateViewEnabled,
    settingsLoadFailed,
    loadPermissions,
  }
}
