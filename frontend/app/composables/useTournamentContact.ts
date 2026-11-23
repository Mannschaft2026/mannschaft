import type {
  ParticipantContactSpaces,
  ParticipantContactSpacesResponse,
  TournamentContactSpace,
  TournamentContactSpaceListResponse,
  UpdateContactSpaceVisibilityRequest,
} from '~/types/tournament'

/**
 * 大会連絡スペース（掲示板・チャット）に関するAPI composable。
 *
 * - `fetchContactSpaces` — 大会レベルの連絡スペース一覧取得
 * - `fetchDivisionContactSpaces` — ディビジョンレベルの連絡スペース一覧取得
 * - `toggleVisibility` — 主催者用の公開設定変更
 * - `fetchParticipantContactSpaces` — 参加チーム文脈の連絡スペース一覧取得（参加者画面用）
 *
 * APIパス:
 * - GET  /api/v1/tournaments/{tId}/contact-spaces
 * - GET  /api/v1/tournaments/{tId}/divisions/{divId}/contact-spaces
 * - PATCH /api/v1/tournaments/{tId}/contact-spaces/{spaceId}/visibility
 * - PATCH /api/v1/tournaments/{tId}/divisions/{divId}/contact-spaces/{spaceId}/visibility
 * - GET  /api/v1/teams/{teamSlug}/tournaments/{tId}/contact-spaces（CMP-261118-0900）
 */
export function useTournamentContact() {
  const api = useApi()

  /**
   * 大会レベルの連絡スペース一覧を取得する。
   */
  async function fetchContactSpaces(tournamentId: number): Promise<TournamentContactSpace[]> {
    const res = await api<TournamentContactSpaceListResponse>(
      `/api/v1/tournaments/${tournamentId}/contact-spaces`,
    )
    return res.data
  }

  /**
   * ディビジョンレベルの連絡スペース一覧を取得する。
   */
  async function fetchDivisionContactSpaces(
    tournamentId: number,
    divisionId: number,
  ): Promise<TournamentContactSpace[]> {
    const res = await api<TournamentContactSpaceListResponse>(
      `/api/v1/tournaments/${tournamentId}/divisions/${divisionId}/contact-spaces`,
    )
    return res.data
  }

  /**
   * 大会レベルの連絡スペースの公開設定を変更する（主催者のみ）。
   */
  async function toggleVisibility(
    tournamentId: number,
    spaceId: string,
    isPublic: boolean,
  ): Promise<void> {
    const body: UpdateContactSpaceVisibilityRequest = { isPublic }
    await api(
      `/api/v1/tournaments/${tournamentId}/contact-spaces/${spaceId}/visibility`,
      { method: 'PATCH', body },
    )
  }

  /**
   * ディビジョンレベルの連絡スペースの公開設定を変更する（主催者のみ）。
   */
  async function toggleDivisionVisibility(
    tournamentId: number,
    divisionId: number,
    spaceId: string,
    isPublic: boolean,
  ): Promise<void> {
    const body: UpdateContactSpaceVisibilityRequest = { isPublic }
    await api(
      `/api/v1/tournaments/${tournamentId}/divisions/${divisionId}/contact-spaces/${spaceId}/visibility`,
      { method: 'PATCH', body },
    )
  }

  /**
   * 参加チーム文脈の連絡スペース一覧を取得する（設計書 01_communication.md §10.3）。
   *
   * 大会全体と自チームが参加中の部門のスペースを、閲覧水準（VIEW / POST）つきで 1 回で返す。
   * 部門名も応答に含まれるため、参加者画面は部門一覧 API を呼ばない。
   * 非所属・非参加・大会不在などはすべて 404 TOUR_029（理由は区別されない）。
   */
  async function fetchParticipantContactSpaces(
    teamSlug: string,
    tournamentId: number,
  ): Promise<ParticipantContactSpaces> {
    const res = await api<ParticipantContactSpacesResponse>(
      `/api/v1/teams/${encodeURIComponent(teamSlug)}/tournaments/${tournamentId}/contact-spaces`,
    )
    return res.data
  }

  return {
    fetchContactSpaces,
    fetchParticipantContactSpaces,
    fetchDivisionContactSpaces,
    toggleVisibility,
    toggleDivisionVisibility,
  }
}
