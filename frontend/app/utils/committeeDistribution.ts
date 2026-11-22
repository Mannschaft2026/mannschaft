import dayjs from 'dayjs'
import type {
  CommitteeConfirmationMode,
  CommitteeDistributeRequest,
  CommitteeDistributionScope,
} from '~/types/committee'

/** 配信ダイアログの入力値。 */
export interface CommitteeDistributionForm {
  customTitle: string
  customBody: string
  customHeadline: string
  targetScope: CommitteeDistributionScope
  announcementEnabled: boolean
  confirmationMode: CommitteeConfirmationMode
  confirmationDeadlineAt: Date | null
}

export type CommitteeDistributionBuildResult =
  | { ok: true; body: CommitteeDistributeRequest }
  | { ok: false; errorKey: string }

/**
 * 配信ダイアログの入力から配信リクエストを組み立てる。入力不備は i18n キーで返す。
 *
 * 画面からの配信は CUSTOM_MESSAGE のみ。他の種別は対象IDを画面内で選べず、
 * BE も contentId の実在を検証しないため、各コンテンツ詳細からの伝達
 * （設計書 F04.10 の CommitteeDistributionModal）で実装する。
 */
export function buildCommitteeDistributeRequest(
  form: CommitteeDistributionForm,
  now: number = Date.now(),
): CommitteeDistributionBuildResult {
  if (!form.customTitle.trim()) return { ok: false, errorKey: 'committee.distributions.required_missing' }
  if (form.confirmationMode === 'REQUIRED') {
    if (!form.confirmationDeadlineAt) return { ok: false, errorKey: 'committee.distributions.required_missing' }
    if (form.confirmationDeadlineAt.getTime() <= now) {
      return { ok: false, errorKey: 'committee.distributions.deadline_past' }
    }
  }
  return {
    ok: true,
    body: {
      contentType: 'CUSTOM_MESSAGE',
      contentId: null,
      customTitle: form.customTitle.trim(),
      customBody: form.customBody.trim() || null,
      targetScope: form.targetScope,
      announcementEnabled: form.announcementEnabled,
      confirmationMode: form.confirmationMode,
      confirmationDeadlineAt: form.confirmationDeadlineAt
        ? dayjs(form.confirmationDeadlineAt).format('YYYY-MM-DDTHH:mm:ss')
        : null,
      customHeadline: form.customHeadline.trim() || null,
    },
  }
}
