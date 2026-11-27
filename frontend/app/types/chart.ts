import type { components } from '~/types/generated'

export type ChartStatus = 'DRAFT' | 'FINALIZED'

export interface Chart {
  id: number
  teamId: number
  clientUserId: number | null
  clientName: string
  staffUserId: number
  staffName: string
  visitDate: string
  status: ChartStatus
  chiefComplaint: string | null
  notes: string | null
  nextVisitRecommendation: string | null
  isPinned: boolean
  sections: ChartSectionConfig
  photos: ChartPhoto[]
  version: number
  createdAt: string
  updatedAt: string
}

export interface ChartSectionConfig {
  bodyChart: boolean
  colorRecipe: boolean
  beforeAfter: boolean
  questionnaire: boolean
  consent: boolean
}

/**
 * カルテ写真（BE の `ChartPhotoResponse`・生成型に寄せる）。
 *
 * 以前は手書きの `photoUrl` を読んでいたが、BE は `signedUrl` を返すため、代理中でなくても写真が出ず
 * 「写真は非表示」の案内が出ていた（第2回検分で発見）。`signedUrl` は代理（サポート閲覧）中は BE が返さない（null）。
 */
export type ChartPhoto = Omit<components['schemas']['ChartPhotoResponse'], 'id' | 'photoType' | 'signedUrl'> & {
  id: number
  photoType: 'BEFORE' | 'AFTER' | 'GENERAL'
  signedUrl?: string | null
}

export interface ChartTemplate {
  id: number
  teamId: number
  name: string
  sections: ChartSectionConfig
  questionnaireJson: QuestionnaireItem[] | null
  createdAt: string
}

export interface QuestionnaireItem {
  question: string
  type: 'TEXT' | 'SELECT' | 'CHECKBOX'
  options?: string[]
  required: boolean
}

export interface CreateChartRequest {
  clientUserId?: number
  clientName: string
  visitDate: string
  chiefComplaint?: string
  notes?: string
  nextVisitRecommendation?: string
  sections: ChartSectionConfig
  templateId?: number
}

export interface ChartListParams {
  page?: number
  size?: number
  staffId?: number
  clientName?: string
  dateFrom?: string
  dateTo?: string
}

export interface ChartFormula {
  id: number
  chartId: number
  name: string
  description: string | null
  ingredients: string | null
  instructions: string | null
  sortOrder: number
  createdAt: string
  updatedAt: string
}

export interface ChartIntakeForm {
  id: number
  chartId: number
  formData: Record<string, unknown>
  submittedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface ChartBodyMark {
  markType: string
  x: number
  y: number
  label: string | null
}

export interface ChartCustomField {
  id: number
  teamId: number
  fieldName: string
  fieldType: string
  options: string[] | null
  required: boolean
  sortOrder: number
  createdAt: string
}

export interface ChartRecordTemplate {
  id: number
  teamId: number
  name: string
  templateData: Record<string, unknown>
  createdAt: string
  updatedAt: string
}

export interface ChartSectionSettings {
  sections: Array<{
    key: string
    label: string
    enabled: boolean
    sortOrder: number
  }>
}

export interface ChartCustomerProgress {
  fieldId: number
  fieldName: string
  dataPoints: Array<{
    visitDate: string
    value: string | number | null
  }>
}
