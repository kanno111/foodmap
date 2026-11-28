import { createApp, nextTick, type App } from 'vue'
import { createPinia, disposePinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useLoginStore } from '@/modules/auth'
import { clearAccessToken } from '@/shared/api/authSession'
import { mockDialog } from '@/shared/test/mockDialog'
import { fetchAdminAnnouncements, publishAnnouncement } from '@/modules/announcement'
import { fetchAdminUsers, type AdminUser } from '../api/adminUsersApi'
import AdminUserWorkspace from './AdminUserWorkspace.vue'

vi.mock('../api/restaurantRequestsApi', () => ({
  fetchRequestPage: vi.fn().mockResolvedValue({ counts: { PENDING: 0 } }),
}))
vi.mock('../api/adminUsersApi', () => ({
  fetchAdminUsers: vi.fn(),
  setAdminUserMuted: vi.fn(),
}))
vi.mock('@/modules/announcement', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/modules/announcement')>()),
  fetchAdminAnnouncements: vi.fn(),
  publishAnnouncement: vi.fn(),
}))

function user(id: string, username: string): AdminUser {
  return {
    id,
    username,
    reviewCount: 0,
    approvedSubmissionCount: 0,
    invitedRegistrationCount: id === '11' ? 2 : undefined,
    rewardSummary:
      id === '11'
        ? {
            types: ['FIRST_REVIEW', 'REGISTRATION_INVITE', 'OTHER', 'FIRST_REVIEW', 'OTHER'],
            hasMore: true,
          }
        : { types: [], hasMore: false },
    hasAvatar: false,
    mutedAt: null,
    canModerate: true,
  }
}

describe('用户管理发送定向公告', () => {
  let app: App, host: HTMLDivElement, restore: () => void
  let pinia: ReturnType<typeof createPinia>

  beforeEach(() => {
    vi.clearAllMocks()
    restore = mockDialog()
    pinia = createPinia()
    setActivePinia(pinia)
    useLoginStore().acceptSession({
      accessToken: 'admin-token',
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id: 1, username: '管理员', roles: ['ROLE_ADMIN'] },
    })
    host = document.createElement('div')
    document.body.append(host)
    vi.mocked(fetchAdminUsers).mockImplementation(async (_keyword, page) => ({
      records: page === 1 ? [user('11', '小林'), user('22', '小夏')] : [user('33', '小舒')],
      total: 3,
      page,
      pageSize: 10,
      totalPages: 2,
    }))
    vi.mocked(publishAnnouncement).mockImplementation(async (input) => ({
      id: '55',
      title: input.title,
      content: input.content,
      audience: 'TARGETED',
      status: 'PUBLISHED',
      publishedBy: '1',
      publishedAt: '2026-11-27T00:00:00Z',
      withdrawnBy: null,
      withdrawnAt: null,
      replayed: false,
    }))
  })

  afterEach(() => {
    app?.unmount()
    host.remove()
    disposePinia(pinia)
    clearAccessToken()
    restore()
  })

  async function mountWorkspace() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/admin/users',
          component: AdminUserWorkspace,
          props: { username: '管理员', loggingOut: false, sessionError: null },
        },
        ...[
          'reviews',
          'reports',
          'restaurants',
          'announcements',
          'activity',
          'invites',
          'users/:id',
        ].map((name) => ({
          path: `/admin/${name}`,
          component: { template: '<div />' },
        })),
      ],
    })
    await router.push('/admin/users')
    app = createApp(RouterView).use(router).use(pinia)
    app.mount(host)
    await vi.waitFor(() => expect(host.querySelector('tbody')?.textContent).toContain('小林'))
    return router
  }

  it('在投稿审核通过数后展示邀请注册数，并链接到用户详情', async () => {
    const router = await mountWorkspace()

    const approvedHeader = host.querySelector('thead .user-submissions')
    expect(approvedHeader?.nextElementSibling?.textContent).toBe('邀请注册数')
    expect(host.querySelector('tbody tr .user-invites')?.textContent?.trim()).toBe('2')
    expect(host.querySelector('tbody tr:nth-child(2) .user-invites')?.textContent?.trim()).toBe('—')
    const profileLink = host.querySelector<HTMLAnchorElement>('tbody tr a.user')
    expect(profileLink).not.toBeNull()
    profileLink?.click()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/admin/users/11'))
  })

  it('领奖情况保留服务端顺序，超出用省略号并打开完整奖励记录', async () => {
    const router = await mountWorkspace()

    const heading = host.querySelector('thead .user-invites')?.nextElementSibling
    expect(heading?.textContent?.trim()).toBe('领奖情况')
    const preview = host.querySelector<HTMLAnchorElement>('tbody tr .reward-summary-link')
    expect(Array.from(preview?.children ?? [], (digit) => digit.textContent?.trim())).toEqual([
      '1',
      '2',
      '3',
      '1',
      '3',
      '…',
    ])
    expect(host.querySelector('tbody tr:nth-child(2) .user-rewards')?.textContent?.trim()).toBe('—')
    preview?.click()
    await vi.waitFor(() =>
      expect(router.currentRoute.value.fullPath).toBe('/admin/users/11?tab=rewards'),
    )
  })

  function fill(field: HTMLInputElement | HTMLTextAreaElement, value: string) {
    field.value = value
    field.dispatchEvent(new Event('input', { bubbles: true }))
  }

  function open() {
    host.querySelector<HTMLButtonElement>('.announcement-selection .announcement-primary')!.click()
    return host.querySelector<HTMLDialogElement>('.user-announcement-dialog')!
  }

  async function fillDraft(dialog: HTMLDialogElement) {
    fill(dialog.querySelector('input')!, ' 公告标题 ')
    fill(dialog.querySelector('textarea')!, '公告正文')
    await nextTick()
  }

  it('全选收件人只显示人数，跨页发送成功后保留搜索和页码', async () => {
    const router = await mountWorkspace()
    const search = host.querySelector<HTMLInputElement>('input[type="search"]')!
    fill(search, '小')
    host.querySelector<HTMLButtonElement>('.toolbar button')!.click()
    await nextTick()
    const selectAll = host.querySelector<HTMLInputElement>('[aria-label="选择本页用户"]')!
    await vi.waitFor(() => expect(selectAll.disabled).toBe(false))
    selectAll.click()
    await nextTick()
    host.querySelector<HTMLButtonElement>('[aria-label="下一页"]')!.click()
    await vi.waitFor(() => expect(host.querySelector('tbody')?.textContent).toContain('小舒'))

    const dialog = open()
    await fillDraft(dialog)
    expect(dialog.open).toBe(true)
    expect(dialog.querySelector('.announcement-recipients')?.textContent).toContain(
      '收件人（2 人）',
    )
    expect(dialog.querySelector('.announcement-recipients ul')).toBeNull()
    dialog.querySelector<HTMLButtonElement>('button[type="submit"]')!.click()
    await vi.waitFor(() => expect(dialog.open).toBe(false))

    expect(publishAnnouncement).toHaveBeenCalledExactlyOnceWith(
      {
        clientRequestId: expect.any(String),
        title: '公告标题',
        content: '公告正文',
        recipientUserIds: ['11', '22'],
      },
      'admin-token',
      expect.any(AbortSignal),
    )
    expect(fetchAdminAnnouncements).not.toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/admin/users')
    expect(search.value).toBe('小')
    expect(host.querySelector('.pagination [aria-current="page"]')?.textContent?.trim()).toBe('2')
    expect(host.querySelector('.announcement-notice')?.textContent).toContain('公告已发布')
    expect(host.querySelector('.announcement-selection')).toBeNull()
  })

  it('部分勾选显示姓名，未知结果关闭再打开仍重试同一批收件人', async () => {
    await mountWorkspace()
    vi.mocked(publishAnnouncement).mockRejectedValueOnce(new Error('网络中断'))
    expect(host.querySelector('.announcement-selection')).toBeNull()
    host.querySelector<HTMLInputElement>('[aria-label="选择 小林"]')!.click()
    await nextTick()
    const dialog = open()
    await fillDraft(dialog)
    expect(dialog.querySelector('.announcement-recipients li')?.textContent).toBe('小林')

    const submit = dialog.querySelector<HTMLButtonElement>('button[type="submit"]')!
    const rewardType = dialog.querySelector('select')!
    rewardType.value = 'FIRST_REVIEW'
    rewardType.dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick()
    expect(submit.disabled).toBe(false)
    rewardType.value = 'OTHER'
    rewardType.dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick()
    expect(submit.disabled).toBe(true)
    const rewardNote = dialog.querySelector<HTMLInputElement>('input[name="rewardNote"]')!
    fill(rewardNote, '  协助活动  ')
    await nextTick()
    submit.click()
    await vi.waitFor(() => expect(dialog.textContent).toContain('发布结果尚未确认'))
    dialog.querySelector<HTMLButtonElement>('.close-announcement')!.click()
    await nextTick()
    expect(dialog.open).toBe(false)
    expect(host.querySelector<HTMLInputElement>('[aria-label="选择 小夏"]')!.disabled).toBe(true)

    open()
    expect(dialog.querySelector('input')!.readOnly).toBe(true)
    expect(rewardType.disabled).toBe(true)
    expect(rewardNote.readOnly).toBe(true)
    submit.click()
    await vi.waitFor(() => expect(dialog.open).toBe(false))
    const attempts = vi.mocked(publishAnnouncement).mock.calls
    expect(attempts).toHaveLength(2)
    expect(attempts[0]![0].recipientUserIds).toEqual(['11'])
    expect(attempts[0]![0].rewardRegistration).toEqual({ type: 'OTHER', note: '协助活动' })
    expect(attempts[1]![0]).toEqual(attempts[0]![0])
  })
})
