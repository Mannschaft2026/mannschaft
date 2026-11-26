import { describe, expect, it } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import ParkingApplicationForm from './ParkingApplicationForm.vue'

mockNuxtImport('useI18n', () => () => ({
  t: (key: string) => key,
  te: () => false,
}))

const stubs = {
  Dialog: { template: '<div><slot /><slot name="footer" /></div>' },
  Select: {
    props: ['modelValue', 'options'],
    emits: ['update:modelValue'],
    template:
      '<button type="button" :data-testid="$attrs[\'data-testid\']" @click="$emit(\'update:modelValue\', options[0].value)">select</button>',
  },
  InputText: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :data-testid="$attrs[\'data-testid\']" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)">',
  },
  Textarea: { props: ['modelValue'], template: '<textarea />' },
  Button: {
    props: ['label', 'disabled'],
    emits: ['click'],
    template: '<button :disabled="disabled" :data-testid="$attrs[\'data-testid\']" @click="$emit(\'click\')">{{ label }}</button>',
  },
}

const spaces = [{ id: 1, spaceNumber: 'A-001', spaceType: 'INDOOR' }]
const vehicles = [{ id: 5, plateNumber: '品川 500 あ 1234' }]

function mountForm(props: Record<string, unknown> = {}) {
  return mountSuspended(ParkingApplicationForm, {
    props: {
      visible: true,
      spaces,
      vehicles,
      vehiclesLoaded: true,
      vehiclesError: null,
      submitting: false,
      ...props,
    },
    global: { stubs },
  })
}

describe('ParkingApplicationForm', () => {
  it('区画と車両を選ぶまで送信できず、選ぶと submit を発火する', async () => {
    const wrapper = await mountForm()
    const submit = wrapper.find('[data-testid="parking-apply-submit"]')
    expect(submit.attributes('disabled')).toBeDefined()
    // 車両は1台なら自動選択済み。区画を選ぶ
    await wrapper.find('[data-testid="parking-apply-space"]').trigger('click')
    expect(wrapper.find('[data-testid="parking-apply-submit"]').attributes('disabled')).toBeUndefined()
    await wrapper.find('[data-testid="parking-apply-submit"]').trigger('click')
    expect(wrapper.emitted('submit')?.[0]?.[0]).toEqual({ spaceId: 1, vehicleId: 5 })
  })

  it('申請できる区画が無いときは案内を出す', async () => {
    const wrapper = await mountForm({ spaces: [] })
    expect(wrapper.find('[data-testid="parking-no-spaces"]').exists()).toBe(true)
  })

  it('車両未登録なら登録への導線を出し、登録を発火する', async () => {
    const wrapper = await mountForm({ vehicles: [] })
    expect(wrapper.find('[data-testid="parking-no-vehicle"]').exists()).toBe(true)
    await wrapper.find('[data-testid="parking-register-vehicle-open"]').trigger('click')
    await wrapper.find('[data-testid="parking-vehicle-plate"]').setValue('品川 500 あ 1')
    await wrapper.find('[data-testid="parking-register-vehicle-save"]').trigger('click')
    expect(wrapper.emitted('registerVehicle')?.[0]?.[0]).toEqual({
      vehicleType: 'CAR',
      plateNumber: '品川 500 あ 1',
    })
  })

  it('車両取得に失敗したら選択肢ではなくエラーを出す', async () => {
    const wrapper = await mountForm({ vehicles: [], vehiclesLoaded: false, vehiclesError: new Error('x') })
    expect(wrapper.find('[data-testid="parking-vehicles-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="parking-no-vehicle"]').exists()).toBe(false)
  })
})
