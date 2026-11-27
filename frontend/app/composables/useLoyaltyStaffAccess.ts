/**
 * チームの営業導線（ポイントカード運用）を出せるか。実スコープ権限（スコープ ADMIN、
 * または TEAM_LOYALTY_OPERATE を持つ DEPUTY_ADMIN）だけで判定する。
 * 運営ロールは判定に使わない（運営専用アカウントはテナントに所属しない。戦役 Z-A PR5）。
 */
export function useLoyaltyStaffAccess(
  roleName: Ref<string | null>,
  isScopeAdmin: Ref<boolean>,
  can: (permission: string) => boolean,
) {
  const allowed = computed(
    () =>
      isScopeAdmin.value ||
      (roleName.value === 'DEPUTY_ADMIN' && can('TEAM_LOYALTY_OPERATE')),
  )
  return { allowed }
}
