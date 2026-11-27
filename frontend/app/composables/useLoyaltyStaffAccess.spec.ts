// @vitest-environment node
import { describe, expect, it, vi } from 'vitest'
import { computed, ref } from 'vue'
import { useLoyaltyStaffAccess } from './useLoyaltyStaffAccess'
vi.stubGlobal('computed', computed)
describe('チーム営業導線の権限（AC05/06/32）', () => {
  it('無委任DEPUTYに導線を表示しない', () => {
    const access = useLoyaltyStaffAccess(ref('DEPUTY_ADMIN'), ref(false), () => false)
    expect(access.allowed.value).toBe(false)
  })
  it('旧応答 roleName=SYSTEM_ADMIN でも実スコープ権限が無ければ許可しない（運営ロールの救済分岐を持たない）', () => {
    const access = useLoyaltyStaffAccess(ref('SYSTEM_ADMIN'), ref(false), () => true)
    expect(access.allowed.value).toBe(false)
  })
  it('スコープ ADMIN と、委任権限を持つ DEPUTY_ADMIN は許可する', () => {
    expect(useLoyaltyStaffAccess(ref('ADMIN'), ref(true), () => false).allowed.value).toBe(true)
    expect(useLoyaltyStaffAccess(ref('DEPUTY_ADMIN'), ref(false), (p) => p === 'TEAM_LOYALTY_OPERATE').allowed.value).toBe(true)
  })
  it('ロールが切り替わると判定も追従する（古い判定を残さない）', () => {
    const role = ref<string | null>('DEPUTY_ADMIN')
    const access = useLoyaltyStaffAccess(role, ref(false), () => true)
    expect(access.allowed.value).toBe(true)
    role.value = null
    expect(access.allowed.value).toBe(false)
  })
})
