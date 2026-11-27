import { execFileSync } from 'node:child_process'
import { rmSync } from 'node:fs'
import path from 'node:path'
import {
  RUN, ROLE_ID, apiAs, createActor, createTeam, joinAsMember, must, readAssets, sql,
  type Actor, type Scope,
} from './n1p1-world'

/**
 * CMP-261125-2114（シフト管理権限の MANAGE_SHIFTS 化）実機E2E の前提データ。
 * API を使うのは前提データの作成・後始末だけ。確認は spec 側で必ず画面を開いて行う。
 *
 * チーム A: admin(ADMIN) / depMng(DEPUTY・MANAGE_SHIFTS 付与済み) / depOff(DEPUTY・権限なし) / depGrant(DEPUTY・
 *           画面から権限を付与・剥奪する対象) / member(MEMBER)
 * チーム B（別テナント）: bAdmin(ADMIN) / bMember
 * sysAdmin: チームロールの無い SYSTEM_ADMIN（user_roles へ直接 SYSTEM_ADMIN を付与した）
 */
export const TAG = `R${RUN}`
export const LEDGER = path.resolve('tests', 'e2e', '.auth', 'cmp261125-2114-assets.json')

export interface World {
  admin: Actor
  depMng: Actor
  depOff: Actor
  depGrant: Actor
  member: Actor
  bAdmin: Actor
  bMember: Actor
  sysAdmin: Actor
  teamA: Scope
  teamB: Scope
  positionA: number
  scheduleA: number
  scheduleB: number
  slotA1: number
  groupMngId: number
  permissionId: number
  sysAdminRoleRowId: number
}

/**
 * 自動割当の機能フラグ（開発 DB の feature_flags。既定 OFF）。「自動割当 run 系は同チーム非管理者も 404」を実 BE で確かめるため、
 * 実行中だけ ON にし、afterAll で元の値へ戻す。フラグ値は Valkey にキャッシュされるため該当キーを消す（パターン削除はしない）。
 */
const AUTO_FLAG = 'FEATURE_SHIFT_AUTO_ASSIGN_ENABLED'
let autoFlagOriginal: string | null = null
function setAutoAssignFlag(value: string): void {
  sql(`UPDATE feature_flags SET is_enabled=${value} WHERE flag_key='${AUTO_FLAG}';`)
  for (const key of [`mannschaft:cache:featureFlags:${AUTO_FLAG}`, 'mannschaft:cache:featureFlagsPublicList:SimpleKey []']) {
    execFileSync('docker', ['exec', 'mannschaft-valkey', 'valkey-cli', 'DEL', key], { stdio: 'pipe', timeout: 60_000 })
  }
}

export const createdSchedules: Array<{ id: number; owner: Actor }> = []
export const createdPositions: Array<{ id: number; owner: Actor }> = []

export async function joinDeputy(admin: Actor, teamSlug: string, user: Actor): Promise<void> {
  await joinAsMember('teams', admin, teamSlug, user)
  await must(admin, 'PATCH', `/api/v1/teams/${teamSlug}/members/${user.id}/role`, { roleId: ROLE_ID.DEPUTY_ADMIN })
}

export async function buildWorld(): Promise<World> {
  autoFlagOriginal = sql(`SELECT is_enabled FROM feature_flags WHERE flag_key='${AUTO_FLAG}';`)
  if (autoFlagOriginal === '') throw new Error(`feature_flags に ${AUTO_FLAG} が無い`)
  setAutoAssignFlag('1')
  const admin = await createActor('c2114adm')
  const depMng = await createActor('c2114dmg')
  const depOff = await createActor('c2114dof')
  const depGrant = await createActor('c2114dgr')
  const member = await createActor('c2114mem')
  const bAdmin = await createActor('c2114badm')
  const bMember = await createActor('c2114bmem')
  const sysAdmin = await createActor('c2114sys')

  const teamA = await createTeam(admin, `2114チームA${TAG}`, 'PUBLIC', LEDGER)
  const teamB = await createTeam(bAdmin, `2114チームB${TAG}`, 'PUBLIC', LEDGER)
  for (const u of [depMng, depOff, depGrant]) await joinDeputy(admin, teamA.slug, u)
  await joinAsMember('teams', admin, teamA.slug, member)
  await joinAsMember('teams', bAdmin, teamB.slug, bMember)

  // MANAGE_SHIFTS 付き権限グループ（depMng 用。前提作成なので API で作る。画面からの付与・剥奪は別テストで depGrant に行う）
  const catalog = await must(admin, 'GET', `/api/v1/permissions?scopeType=TEAM&scopeId=${teamA.numericId}`)
  const permissionId = (catalog.data as Array<{ id: number; name: string }>).find((p) => p.name === 'MANAGE_SHIFTS')?.id
  if (!permissionId) throw new Error('権限カタログに MANAGE_SHIFTS が無い')
  const group = await must(admin, 'POST', `/api/v1/teams/${teamA.slug}/permission-groups`, {
    name: `前提シフト管理${TAG}`, targetRole: 'DEPUTY_ADMIN', permissionIds: [permissionId],
  })
  await must(admin, 'PUT', `/api/v1/teams/${teamA.slug}/members/${depMng.id}/permission-groups`, { groupIds: [group.data.id] })

  // チームロールの無い SYSTEM_ADMIN（SYSTEM_ADMIN ロールの付与だけは画面も API も無いので SQL）
  sql(`INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) VALUES (${sysAdmin.id}, 1, NULL, NULL, NOW(), NOW());`)
  const sysAdminRoleRowId = Number(sql(`SELECT id FROM user_roles WHERE user_id=${sysAdmin.id} AND role_id=1;`))

  const posA = await must(admin, 'POST', `/api/v1/shifts/positions?teamId=${teamA.numericId}`, { name: `受付${TAG}`, displayOrder: 1 })
  const schA = await must(admin, 'POST', `/api/v1/shifts/schedules?teamId=${teamA.numericId}`, { title: `2114シフトA${TAG}`, startDate: '2027-02-01', endDate: '2027-02-07' })
  const schB = await must(bAdmin, 'POST', `/api/v1/shifts/schedules?teamId=${teamB.numericId}`, { title: `2114シフトB${TAG}`, startDate: '2027-02-01', endDate: '2027-02-07' })
  const slotA1 = (await must(admin, 'POST', `/api/v1/shifts/schedules/${schA.data.id}/slots`, {
    slotDate: '2027-02-02', startTime: '09:00:00', endTime: '12:00:00', positionId: posA.data.id, requiredCount: 2, note: null,
  })).data.id as number
  createdSchedules.push({ id: schA.data.id, owner: admin }, { id: schB.data.id, owner: bAdmin })
  createdPositions.push({ id: posA.data.id, owner: admin })
  // 一般メンバーにも見える状態（調整中）まで進める。公開は予算連動の検査があるため使わない。
  await must(admin, 'POST', `/api/v1/shifts/schedules/${schA.data.id}/transition?status=COLLECTING`)
  await must(admin, 'POST', `/api/v1/shifts/schedules/${schA.data.id}/transition?status=ADJUSTING`)
  return {
    admin, depMng, depOff, depGrant, member, bAdmin, bMember, sysAdmin, teamA, teamB,
    positionA: posA.data.id, scheduleA: schA.data.id, scheduleB: schB.data.id, slotA1,
    groupMngId: group.data.id, permissionId, sysAdminRoleRowId,
  }
}

/** 作成物の後始末と残件確認。失敗は文字列で返す（握りつぶさない）。 */
export async function teardownWorld(): Promise<{ failures: string[]; teams: number; schedules: number; sysAdminRows: number }> {
  const failures: string[] = []
  for (const s of [...createdSchedules].reverse()) {
    const r = await apiAs(s.owner, 'DELETE', `/api/v1/shifts/schedules/${s.id}`)
    if (![200, 204, 404].includes(r.status)) failures.push(`schedule ${s.id} -> ${r.status}`)
  }
  for (const p of [...createdPositions].reverse()) {
    const r = await apiAs(p.owner, 'DELETE', `/api/v1/shifts/positions/${p.id}`)
    if (![200, 204, 404].includes(r.status)) failures.push(`position ${p.id} -> ${r.status}`)
  }
  for (const record of [...readAssets(LEDGER)].reverse()) {
    const r = await apiAs(record.owner, 'DELETE', record.path)
    if (![200, 204, 404].includes(r.status)) failures.push(`${record.label} -> ${r.status}`)
  }
  if (autoFlagOriginal !== null) setAutoAssignFlag(autoFlagOriginal)
  // beforeAll の途中で失敗して W が未確定でも、この実行の SYSTEM_ADMIN 行（メールに RUN を含む）を消す
  sql(`DELETE ur FROM user_roles ur JOIN users u ON u.id=ur.user_id WHERE ur.role_id=1 AND u.email='e2e-n1p1-c2114sys-${RUN}@test.mannschaft.local';`)
  rmSync(LEDGER, { force: true })
  const teams = Number(sql(`SELECT COUNT(*) FROM teams WHERE deleted_at IS NULL AND name LIKE '%${TAG}%';`))
  const schedules = Number(sql(`SELECT COUNT(*) FROM shift_schedules WHERE deleted_at IS NULL AND title LIKE '%${TAG}%';`))
  const sysAdminRows = Number(sql(`SELECT COUNT(*) FROM user_roles ur JOIN users u ON u.id=ur.user_id WHERE ur.role_id=1 AND u.email='e2e-n1p1-c2114sys-${RUN}@test.mannschaft.local';`))
  return { failures, teams, schedules, sysAdminRows }
}
