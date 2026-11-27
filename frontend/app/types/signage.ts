export type SignageLayout = 'FULLSCREEN' | 'SPLIT_HORIZONTAL' | 'SPLIT_VERTICAL' | 'QUAD'
export type SignageTransition = 'NONE' | 'FADE' | 'SLIDE' | 'ZOOM'
export type SignageSlotType = 'IMAGE' | 'VIDEO' | 'URL' | 'ANNOUNCEMENT' | 'SCHEDULE' | 'WEATHER'

export interface SignageScreen {
  id: number
  scopeType: string
  scopeId: number
  name: string
  description: string | null
  layout: SignageLayout
  defaultSlideDuration: number
  transitionEffect: SignageTransition
  isActive: boolean
  createdAt: string
}

export interface SignageSlot {
  id: number
  screenId: number
  slotType: SignageSlotType
  contentSourceId: string | null
  durationSeconds: number
  displayCondition: string | null
  order: number
}

/** 一覧・参照用。サーバーにはハッシュしか無いため、平文トークンは含まない。 */
export interface SignageToken {
  id: number
  screenId: number
  name: string
  isActive: boolean
  allowedIps: string[]
  expiredAt: string | null
  createdAt: string
}

/** 発行応答。平文トークン（token）はこの応答でだけ返る。 */
export interface SignageTokenIssued extends SignageToken {
  token: string
}

export interface IssueSignageTokenRequest {
  name: string
  allowedIps?: string[]
  expiredAt?: string | null
}

export interface CreateSignageScreenRequest {
  scopeType: string
  scopeId: number
  name: string
  description?: string
  layout: SignageLayout
  defaultSlideDuration: number
  transitionEffect: SignageTransition
}

export interface UpdateSignageScreenRequest {
  name?: string
  description?: string
  layout?: SignageLayout
  defaultSlideDuration?: number
  transitionEffect?: SignageTransition
  isActive?: boolean
}

export interface AddSignageSlotRequest {
  slotType: SignageSlotType
  contentSourceId?: string
  durationSeconds: number
  displayCondition?: string
}
