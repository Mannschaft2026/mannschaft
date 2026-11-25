export interface TeamShiftSettings {
  teamId: number
  reminder48hEnabled: boolean
  reminder24hEnabled: boolean
  reminder12hEnabled: boolean
  /** MANAGE_SHIFTS を持たない副管理者にシフト閲覧（未公開・希望一覧・他人の依頼 等）を許すか。BE は常に boolean を返す */
  deputyShiftViewEnabled: boolean
  /** MANAGE_SHIFTS を持たない副管理者に他メンバーの時給の閲覧を許すか。BE は常に boolean を返す */
  deputyHourlyRateViewEnabled: boolean
}

/** PATCH。省略した項目は変更なし。 */
export interface UpdateTeamShiftSettingsRequest {
  reminder48hEnabled?: boolean
  reminder24hEnabled?: boolean
  reminder12hEnabled?: boolean
  deputyShiftViewEnabled?: boolean
  deputyHourlyRateViewEnabled?: boolean
}
