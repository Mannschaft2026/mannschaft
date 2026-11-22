import { describe, it, expect } from 'vitest'
import { buildCommitteeDistributeRequest, type CommitteeDistributionForm } from '~/utils/committeeDistribution'

/**
 * CMP-260930-0231: 配信リクエストの組み立てと入力検証。
 * 確認期限が過去の場合は BE の DEADLINE_IN_PAST に頼らず、送信前に利用者へ分かる文言で拒否する。
 */
const NOW = new Date('2026-11-22T10:00:00').getTime()

function form(overrides: Partial<CommitteeDistributionForm> = {}): CommitteeDistributionForm {
  return {
    customTitle: '連絡',
    customBody: '',
    customHeadline: '',
    targetScope: 'COMMITTEE_ONLY',
    announcementEnabled: true,
    confirmationMode: 'NONE',
    confirmationDeadlineAt: null,
    ...overrides,
  }
}

describe('buildCommitteeDistributeRequest', () => {
  it('UT-001: 件名が空なら required_missing', () => {
    expect(buildCommitteeDistributeRequest(form({ customTitle: '  ' }), NOW))
      .toEqual({ ok: false, errorKey: 'committee.distributions.required_missing' })
  })

  it('UT-002: 確認必須で期限が過去なら deadline_past で拒否する', () => {
    const past = new Date(NOW - 60 * 60 * 1000)
    expect(buildCommitteeDistributeRequest(form({ confirmationMode: 'REQUIRED', confirmationDeadlineAt: past }), NOW))
      .toEqual({ ok: false, errorKey: 'committee.distributions.deadline_past' })
  })

  it('UT-003: 確認必須で期限が未入力なら required_missing', () => {
    expect(buildCommitteeDistributeRequest(form({ confirmationMode: 'REQUIRED' }), NOW))
      .toEqual({ ok: false, errorKey: 'committee.distributions.required_missing' })
  })

  it('UT-004: 確認必須で未来の期限なら CUSTOM_MESSAGE リクエストを返す', () => {
    const future = new Date('2026-11-23T09:30:00')
    const res = buildCommitteeDistributeRequest(form({ confirmationMode: 'REQUIRED', confirmationDeadlineAt: future }), NOW)
    expect(res).toMatchObject({
      ok: true,
      body: { contentType: 'CUSTOM_MESSAGE', contentId: null, customTitle: '連絡', customBody: null, confirmationDeadlineAt: '2026-11-23T09:30:00' },
    })
  })

  it('UT-005: 確認不要なら過去の期限が残っていても検証せず期限を送る必要はない', () => {
    const res = buildCommitteeDistributeRequest(form({ confirmationMode: 'NONE' }), NOW)
    expect(res).toMatchObject({ ok: true, body: { confirmationDeadlineAt: null } })
  })
})
