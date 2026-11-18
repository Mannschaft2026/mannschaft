import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ActivityFieldInputs from '~/components/activity/ActivityFieldInputs.vue'
import type { ActivityTemplateField } from '~/types/activity'

function numberField(scale?: number): ActivityTemplateField {
  return {
    id: 0, fieldKey: 'weight', fieldLabel: '体重', fieldType: 'NUMBER', isRequired: false,
    optionsJson: null, placeholder: null, unit: null, isAggregatable: false, sortOrder: 0, scale,
  }
}

async function typeInto(scale: number | undefined, text: string): Promise<string> {
  const wrapper = await mountSuspended(ActivityFieldInputs, {
    props: { fields: [numberField(scale)], modelValue: {} },
  })
  const input = wrapper.find('input#activity-field-weight')
  await input.setValue(text)
  await input.trigger('blur')
  return (input.element as HTMLInputElement).value
}

describe('ActivityFieldInputs: NUMBER の scale', () => {
  it('scale 2 で 60.25 の小数 2 桁が保持される', async () => {
    expect(await typeInto(2, '60.25')).toBe('60.25')
  })

  it('scale 未指定の既存利用は従来どおり（小数桁を勝手に増やさない）', async () => {
    const v = await typeInto(undefined, '30')
    expect(v).toBe('30')
  })
})
