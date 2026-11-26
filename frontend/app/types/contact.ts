export interface ContactUser {
  id: number
  fullName: string
  contactHandle: string | null
  avatarUrl: string | null
}

export interface ContactResponse {
  folderItemId: number
  folderId: number | null
  user: ContactUser
  customName: string | null
  isPinned: boolean
  privateNote: string | null
  addedAt: string
}

export interface ContactRequestResponse {
  id: number
  requester: ContactUser
  target: ContactUser
  status: 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED'
  message: string | null
  sourceType: string
  createdAt: string
}

export interface ContactRequestBlockResponse {
  id: number
  blockedUser: ContactUser
  createdAt: string
}

/** 一覧用。平文のトークン・招待URL・QR の URL は含まない（発行応答だけが返す）。 */
export interface ContactInviteTokenResponse {
  id: number
  label: string | null
  maxUses: number | null
  usedCount: number
  expiresAt: string | null
  createdAt: string
}

/** 発行応答。平文のトークンと招待URLはこの1回だけ返る。 */
export interface ContactInviteIssuedResponse extends ContactInviteTokenResponse {
  token: string
  inviteUrl: string
}

export interface ContactInvitePreviewResponse {
  isValid: boolean
  issuer: { fullName: string; contactHandle: string | null }
  expiresAt: string | null
}

export interface ContactPrivacySettings {
  handleSearchable: boolean
  contactApprovalRequired: boolean
  dmReceiveFrom: 'ANYONE' | 'TEAM_MEMBERS_ONLY' | 'CONTACTS_ONLY'
  onlineVisibility: 'NOBODY' | 'CONTACTS_ONLY' | 'EVERYONE'
}

export interface HandleSearchResult {
  /** false のとき見つからない（BE は 200 で found:false と null 群を返す） */
  found: boolean
  userId: number
  fullName: string
  contactHandle: string
  avatarUrl: string | null
  isContact: boolean
  hasPendingRequest: boolean
  contactApprovalRequired: boolean
}

export interface HandleInfo {
  contactHandle: string | null
  handleSearchable: boolean
  contactApprovalRequired: boolean
  onlineVisibility: 'NOBODY' | 'CONTACTS_ONLY' | 'EVERYONE'
}

export interface ContactableMember {
  userId: number
  fullName: string
  contactHandle: string | null
  avatarUrl: string | null
  isContact: boolean
  hasPendingRequest: boolean
}

export interface SendContactRequestBody {
  targetUserId: number
  message?: string
  sourceType: 'HANDLE_SEARCH' | 'TEAM_SEARCH' | 'ORG_SEARCH' | 'INVITE_URL'
}

export interface CreateInviteTokenBody {
  label?: string
  maxUses?: number | null
  expiresIn?: '1d' | '7d' | '30d' | 'unlimited' | null
}
