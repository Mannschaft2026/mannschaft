import type { components } from './generated'

export type FeatureDisplayPolicy = components['schemas']['FeatureDisplayPolicyItem']
export type FeatureDisplayRole =
  | 'ADMIN'
  | 'DEPUTY_ADMIN'
  | 'MEMBER'
  | 'SUPPORTER'
  | 'PUBLIC'
  | 'GUEST'
  | null
  | undefined
