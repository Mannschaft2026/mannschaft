// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mockApi = vi.fn()
vi.mock('~/composables/useApi', () => ({ useApi: () => mockApi }))
const { useEffortRecordApi } = await import('~/composables/useEffortRecordApi')

describe('useEffortRecordApi', () => {
  beforeEach(() => {
    mockApi.mockReset()
    mockApi.mockResolvedValue({ data: {} })
  })

  it('作成は Idempotency-Key を付けて POST し、null 値もそのまま送る', async () => {
    const body = {
      recordedOn: '2026-11-15', recordTimezone: 'Asia/Tokyo', definitionUuid: 'u', definitionRevision: 1,
      values: [{ fieldKey: 'duration', value: null }],
    }
    await useEffortRecordApi().create(body, 'key-1')
    expect(mockApi).toHaveBeenCalledWith('/api/v1/effort-records/me', { method: 'POST', body, headers: { 'Idempotency-Key': 'key-1' } })
  })

  it('削除は version を query、Idempotency-Key を header で送る', async () => {
    await useEffortRecordApi().remove('id-1', 4, 'key-2')
    expect(mockApi).toHaveBeenCalledWith('/api/v1/effort-records/me/id-1', { method: 'DELETE', query: { version: 4 }, headers: { 'Idempotency-Key': 'key-2' } })
  })

  it('集計は snake_case の query へ変換する', async () => {
    await useEffortRecordApi().stats({ dateFrom: '2026-11-01', dateTo: '2026-11-30', definitionUuid: 'u', fieldKey: 'duration', chart: 'PIE', grain: 'DAY' })
    expect(mockApi).toHaveBeenCalledWith('/api/v1/effort-records/me/stats', {
      query: { date_from: '2026-11-01', date_to: '2026-11-30', definition_uuid: 'u', field_key: 'duration', chart: 'PIE', grain: 'DAY' },
    })
  })

  it('更新は version を本文に含めて PUT する', async () => {
    const body = { version: 3, recordedOn: '2026-11-15', values: [] }
    await useEffortRecordApi().update('id-1', body)
    expect(mockApi).toHaveBeenCalledWith('/api/v1/effort-records/me/id-1', { method: 'PUT', body })
  })
})
