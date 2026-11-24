import { describe, expect, it } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import ParkingApplicationList from './ParkingApplicationList.vue'

mockNuxtImport('useI18n', () => () => ({
  t: (key: string) => key,
  te: () => true,
}))
mockNuxtImport('useDatetime', () => () => ({ formatDateTime: (v: string) => v }))

const stubs = {
  Paginator: true,
  Button: {
    props: ['label'],
    emits: ['click'],
    template: '<button @click="$emit(\'click\')">{{ label }}</button>',
  },
}

const base = {
  total: 4,
  page: 0,
  rows: 10,
  spaceNumbers: { 1: 'A-001' },
  vehicleLabels: { 5: '品川 500 あ 1234' },
  currentUserId: 100,
}

const apps = [
  { id: 1, spaceId: 1, vehicleId: 5, userId: 100, status: 'PENDING', createdAt: '2026-11-01' },
  { id: 2, spaceId: 1, vehicleId: 5, userId: 100, status: 'LOTTERY_PENDING', createdAt: '2026-11-01' },
  { id: 3, spaceId: 1, vehicleId: 5, userId: 100, status: 'APPROVED', createdAt: '2026-11-01' },
  { id: 4, spaceId: 1, vehicleId: 5, userId: 200, status: 'PENDING', createdAt: '2026-11-01' },
]

describe('ParkingApplicationList', () => {
  it('状態を表示し、取消は自分の PENDING / LOTTERY_PENDING にだけ出す', async () => {
    const wrapper = await mountSuspended(ParkingApplicationList, {
      props: { ...base, applications: apps },
      global: { stubs },
    })
    const rows = wrapper.findAll('[data-testid="parking-application"]')
    expect(rows).toHaveLength(4)
    expect(rows[0]!.text()).toContain('A-001')
    expect(rows[0]!.text()).toContain('parking.applications.status.PENDING')
    const cancellable = rows.map((r) => r.find('[data-testid="parking-application-cancel"]').exists())
    expect(cancellable).toEqual([true, true, false, false])
  })

  it('取消ボタンで cancel を発火する', async () => {
    const wrapper = await mountSuspended(ParkingApplicationList, {
      props: { ...base, applications: apps },
      global: { stubs },
    })
    await wrapper.find('[data-testid="parking-application-cancel"]').trigger('click')
    expect(wrapper.emitted('cancel')?.[0]?.[0]).toMatchObject({ id: 1 })
  })

  it('区画・車両が引けないときは ID 表示にフォールバックする', async () => {
    const wrapper = await mountSuspended(ParkingApplicationList, {
      props: {
        ...base,
        spaceNumbers: {},
        vehicleLabels: {},
        applications: [apps[0]!],
      },
      global: { stubs },
    })
    expect(wrapper.text()).toContain('#1')
    expect(wrapper.text()).toContain('#5')
  })
})
