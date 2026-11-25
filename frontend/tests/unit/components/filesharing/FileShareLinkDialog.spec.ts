import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '~/stores/useAuthStore'
import FileShareLinkDialog from '~/components/filesharing/FileShareLinkDialog.vue'
import type { SharedFile } from '~/types/filesharing'

/**
 * F05.5 (D) FileShareLinkDialog.vue ユニットテスト（Issue #2508 FEオフセット明示）。
 *
 * 背景: BE の CreateLinkRequest.expiresAt は LocalDateTime 宣言で、受信時オフセットを
 * 無視する非対称バグがある。以前は toLocalDateTime() で TZ オフセット無しの壁時計文字列を
 * 送っていたため、useDatetime().buildOffsetDateTimeStr へ差し替えた。
 *
 * 検証観点:
 *   FSL-001: onCreate は expiresAt にユーザーTZ（既定 Asia/Tokyo）のオフセット付き文字列を送る
 *   FSL-002: 非JST（America/Los_Angeles）ユーザーでもそのTZのオフセットが付く
 */

const mockGetFileLinks = vi.fn()
const mockCreateFileLink = vi.fn()
const mockDeleteFileLink = vi.fn()
vi.mock('~/composables/useFileSharingApi', () => ({
  useFileSharingApi: () => ({
    getFileLinks: mockGetFileLinks,
    createFileLink: mockCreateFileLink,
    deleteFileLink: mockDeleteFileLink,
  }),
}))

const mockShowSuccess = vi.fn()
const mockShowError = vi.fn()
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    showSuccess: mockShowSuccess,
    showError: mockShowError,
  }),
}))

function buildFile(overrides: Partial<SharedFile> = {}): SharedFile {
  return {
    id: 42,
    folderId: 1,
    fileName: 'test.pdf',
    originalFileName: 'test.pdf',
    fileSize: 1024,
    mimeType: 'application/pdf',
    description: null,
    uploadedBy: { id: 1, displayName: 'テストユーザー' },
    versionCount: 1,
    currentVersionId: 1,
    tags: [],
    downloadCount: 0,
    createdAt: '2026-05-01T00:00:00+09:00',
    updatedAt: '2026-05-01T00:00:00+09:00',
    ...overrides,
  }
}

/** 指定タイムゾーンで非同期処理を実行する（Node は process.env.TZ の実行時変更を反映する）。 */
async function withSystemTz<T>(tz: string, fn: () => Promise<T>): Promise<T> {
  const original = process.env.TZ
  process.env.TZ = tz
  try {
    return await fn()
  } finally {
    process.env.TZ = original
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  mockGetFileLinks.mockResolvedValue({ data: [] })
})

describe('FileShareLinkDialog.vue', () => {
  it('FSL-001: onCreate は expiresAt にユーザーTZ（既定 Asia/Tokyo）のオフセット付き文字列を送る', async () => {
    await withSystemTz('Asia/Tokyo', async () => {
      mockCreateFileLink.mockResolvedValueOnce({ data: {} })
      const wrapper = await mountSuspended(FileShareLinkDialog, {
        props: { file: buildFile(), visible: true },
      })
      const vm = wrapper.vm as unknown as {
        expiresAt: Date | null
        onCreate: () => Promise<void>
      }
      vm.expiresAt = new Date(2026, 5, 10, 15, 0, 0) // 2026-06-10 15:00 JST
      await wrapper.vm.$nextTick()
      await vm.onCreate()

      expect(mockCreateFileLink).toHaveBeenCalledTimes(1)
      const [fileIdArg, bodyArg] = mockCreateFileLink.mock.calls[0] as [number, Record<string, unknown>]
      expect(fileIdArg).toBe(42)
      expect(bodyArg.expiresAt).toBe('2026-06-10T15:00:00+09:00')
    })
  })

  it('FSL-002: 非JST（America/Los_Angeles）ユーザーでも expiresAt にそのTZのオフセットが付く', async () => {
    await withSystemTz('America/Los_Angeles', async () => {
      mockCreateFileLink.mockResolvedValueOnce({ data: {} })
      const wrapper = await mountSuspended(FileShareLinkDialog, {
        props: { file: buildFile(), visible: true },
      })
      // 注意: @pinia/nuxt はマウント時に独自の Pinia インスタンスを生成して
      // setActivePinia() し直すため、mountSuspended より前に useAuthStore().user を
      // セットしても、マウント後に上書きされて反映されない。必ずマウント後にセットする。
      useAuthStore().user = {
        id: 1,
        email: 'la-user@example.com',
        fullName: 'LA User',
        profileImageUrl: null,
        timezone: 'America/Los_Angeles',
      }
      const vm = wrapper.vm as unknown as {
        expiresAt: Date | null
        onCreate: () => Promise<void>
      }
      vm.expiresAt = new Date(2026, 5, 10, 15, 0, 0) // 2026-06-10 15:00 PDT
      await wrapper.vm.$nextTick()
      await vm.onCreate()

      const bodyArg = mockCreateFileLink.mock.calls[0]?.[1] as Record<string, unknown>
      expect(bodyArg.expiresAt).toBe('2026-06-10T15:00:00-07:00')
    })
  })

  it('FSL-003: 発行応答の平文は SecretIssuedDialog に1回だけ渡り、一覧の再取得には token が含まれない（CMP-260826-1457）', async () => {
    const plaintext = 'A'.repeat(43)
    mockCreateFileLink.mockResolvedValueOnce({ data: { id: 7, fileId: 42, token: plaintext } })
    mockGetFileLinks.mockResolvedValue({
      data: [{
        id: 7, fileId: 42, downloadAllowed: false, hasPassword: false, active: true,
        accessCount: 3, expiresAt: '2099-01-01T00:00:00+09:00', lastAccessedAt: null,
        createdAt: '2026-05-01T00:00:00+09:00',
      }],
    })
    const wrapper = await mountSuspended(FileShareLinkDialog, {
      props: { file: buildFile(), visible: true },
    })
    const vm = wrapper.vm as unknown as {
      expiresAt: Date | null
      onCreate: () => Promise<void>
      issuedUrl: string
      showIssuedDialog: boolean
      onIssuedVisibleChange: (open: boolean) => void
    }
    vm.expiresAt = new Date(2026, 5, 10, 15, 0, 0)
    await wrapper.vm.$nextTick()
    await vm.onCreate()

    expect(vm.showIssuedDialog).toBe(true)
    expect(vm.issuedUrl).toBe(`${window.location.origin}/shared/${plaintext}`)

    // 閉じたら平文はメモリから消える
    vm.onIssuedVisibleChange(false)
    expect(vm.issuedUrl).toBe('')

    // 一覧には URL・コピーを出さず、再発行・削除だけがある
    // 一覧のダイアログ本体（発行直後ダイアログとは別）には平文がどこにも出ない
    const listDialog = document.body.querySelector('[data-testid="file-share-link-dialog"]')
    expect(listDialog).not.toBeNull()
    expect(listDialog!.innerHTML).not.toContain(plaintext)
    expect(document.body.querySelector('[data-testid="share-link-reissue-7"]')).not.toBeNull()
  })

  it('FSL-004: 再発行は新リンクの発行に成功した後で旧リンクを削除する', async () => {
    mockCreateFileLink.mockResolvedValueOnce({ data: { id: 8, fileId: 42, token: 'B'.repeat(43) } })
    mockDeleteFileLink.mockResolvedValueOnce(undefined)
    const wrapper = await mountSuspended(FileShareLinkDialog, {
      props: { file: buildFile(), visible: true },
    })
    const vm = wrapper.vm as unknown as {
      expiresAt: Date | null
      onCreate: () => Promise<void>
      onReissue: (link: { id: number, downloadAllowed: boolean, expiresAt: string | null }) => void
    }
    vm.onReissue({ id: 7, downloadAllowed: true, expiresAt: null })
    vm.expiresAt = new Date(2026, 5, 10, 15, 0, 0)
    await wrapper.vm.$nextTick()
    await vm.onCreate()

    expect(mockCreateFileLink).toHaveBeenCalledTimes(1)
    expect((mockCreateFileLink.mock.calls[0]?.[1] as Record<string, unknown>).downloadAllowed).toBe(true)
    expect(mockDeleteFileLink).toHaveBeenCalledWith(42, 7)
    expect(mockCreateFileLink.mock.invocationCallOrder[0]).toBeLessThan(mockDeleteFileLink.mock.invocationCallOrder[0]!)
  })

  it('FSL-005: 新リンクの発行に失敗したときは旧リンクを削除しない', async () => {
    mockCreateFileLink.mockRejectedValueOnce(new Error('boom'))
    const wrapper = await mountSuspended(FileShareLinkDialog, {
      props: { file: buildFile(), visible: true },
    })
    const vm = wrapper.vm as unknown as {
      expiresAt: Date | null
      onCreate: () => Promise<void>
      onReissue: (link: { id: number, downloadAllowed: boolean, expiresAt: string | null }) => void
    }
    vm.onReissue({ id: 7, downloadAllowed: false, expiresAt: null })
    vm.expiresAt = new Date(2026, 5, 10, 15, 0, 0)
    await wrapper.vm.$nextTick()
    await vm.onCreate()

    expect(mockDeleteFileLink).not.toHaveBeenCalled()
  })
})
