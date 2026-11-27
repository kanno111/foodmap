import { createApp, nextTick, type App } from 'vue'
import { createPinia, disposePinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useLoginStore } from '@/modules/auth'
import { clearAccessToken } from '@/shared/api/authSession'
import { mockDialog } from '@/shared/test/mockDialog'
import {
  fetchAdminAnnouncement,
  fetchAdminAnnouncements,
  searchAnnouncementRecipients,
  type AdminAnnouncementDetail,
} from '@/modules/announcement'
import AdminAnnouncementWorkspace from './AdminAnnouncementWorkspace.vue'

vi.mock('../api/restaurantRequestsApi', () => ({
  fetchRequestPage: vi.fn().mockResolvedValue({ counts: { PENDING: 0 } }),
}))
vi.mock('@/modules/announcement', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/modules/announcement')>()),
  searchAnnouncementRecipients: vi.fn(),
  fetchAdminAnnouncements: vi.fn(),
  fetchAdminAnnouncement: vi.fn(),
}))

describe('公告编辑', () => {
  let app: App, host: HTMLDivElement, restore: () => void
  let pinia: ReturnType<typeof createPinia>
  beforeEach(() => {
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
          path: '/admin/announcements',
          component: AdminAnnouncementWorkspace,
          props: { username: '管理员', loggingOut: false, sessionError: null },
        },
        ...['reviews', 'reports', 'restaurants', 'users', 'activity'].map((name) => ({
          path: `/admin/${name}`,
          component: { template: '<div />' },
        })),
      ],
    })
    await router.push('/admin/announcements')
    app = createApp(RouterView).use(router).use(pinia)
    app.mount(host)
    return router
  }
  it('未发布公告取消离开保留正文，确认后离开', async () => {
    const router = await mountWorkspace()
    const input = host.querySelector('textarea')!
    input.value = '尚未发布的正文'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    const cancel = router.push('/admin/reviews')
    const dialog = () => document.querySelector<HTMLDialogElement>('.leave-confirmation')!
    await vi.waitFor(() => expect(dialog().open).toBe(true))
    dialog().querySelector<HTMLButtonElement>('button')!.click()
    await cancel
    expect(router.currentRoute.value.path).toBe('/admin/announcements')
    expect(input.value).toBe('尚未发布的正文')
    const leave = router.push('/admin/reviews')
    await vi.waitFor(() => expect(dialog().open).toBe(true))
    dialog().querySelector<HTMLButtonElement>('.leave')!.click()
    await leave
    expect(router.currentRoute.value.path).toBe('/admin/reviews')
  })
  it('默认全体用户，换关键词保留勾选，确认后展示收件人但按钮文案不变', async () => {
    vi.mocked(searchAnnouncementRecipients).mockImplementation(async (keyword) => ({
      records:
        keyword === '雨'
          ? [{ id: '22', username: '雨后米线' }]
          : [{ id: '11', username: '林小北' }],
      hasNext: false,
    }))
    await mountWorkspace()
    const range = host.querySelector<HTMLElement>('.announcement-audience')!
    expect(range.querySelector<HTMLInputElement>('input[value="ALL"]')!.checked).toBe(true)
    range.querySelector<HTMLInputElement>('input[value="TARGETED"]')!.click()
    await nextTick()
    const picker = host.querySelector<HTMLDialogElement>('.recipient-dialog')!
    expect(picker.open).toBe(true)
    const save = picker.querySelector<HTMLButtonElement>('footer .primary')!
    expect(save.disabled).toBe(true)
    await vi.waitFor(() => expect(picker.textContent).toContain('林小北'))
    picker.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click()
    const search = picker.querySelector<HTMLInputElement>('input[aria-label="搜索用户名"]')!
    search.value = '雨'
    search.dispatchEvent(new Event('input', { bubbles: true }))
    await vi.waitFor(() =>
      expect(picker.querySelector('.recipient-results')?.textContent).toContain('雨后米线'),
    )
    picker.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click()
    await nextTick()
    expect(picker.querySelector('.recipient-chips')?.textContent).toContain('林小北')
    save.click()
    await nextTick()
    expect(picker.open).toBe(false)
    expect(range.textContent).toContain('指定用户')
    expect(
      [...range.querySelectorAll('.audience-names li')].map((item) => item.textContent),
    ).toEqual(['林小北', '雨后米线'])
    expect(host.querySelector('button[type="submit"]')?.textContent?.trim()).toBe('发布公告')

    range.querySelector<HTMLButtonElement>('button')!.click()
    await nextTick()
    picker.querySelector<HTMLButtonElement>('button[aria-label="关闭选择收件人"]')!.click()
    await nextTick()
    expect(range.textContent).toContain('已选 2 人')
    range.querySelector<HTMLInputElement>('input[value="ALL"]')!.click()
    await nextTick()
    expect(range.querySelector('.audience-names')).toBeNull()
  })

  it('指定公告显示历史收件人及不可用账号，关闭后保留记录列表', async () => {
    const targeted: AdminAnnouncementDetail = {
      id: '45',
      title: '指定公告',
      content: '正文',
      audience: 'TARGETED',
      status: 'WITHDRAWN',
      publishedBy: '22',
      publishedAt: '2026-11-27T00:00:00Z',
      withdrawnBy: '22',
      withdrawnAt: '2026-11-28T00:00:00Z',
      replayed: false,
      recipients: [
        { id: '11', username: '小林' },
        { id: '22', username: null },
        { id: '33', username: '山茶' },
      ],
    }
    const all: AdminAnnouncementDetail = {
      ...targeted,
      id: '46',
      title: '全体公告',
      audience: 'ALL',
      recipients: [],
    }
    vi.mocked(fetchAdminAnnouncements).mockResolvedValue({
      page: 1,
      pageSize: 10,
      total: 2,
      totalPages: 1,
      records: [
        { ...targeted, recipientCount: 3, recipientPreview: targeted.recipients!.slice(0, 2) },
        { ...all, recipientCount: 0, recipientPreview: [] },
      ],
    })
    vi.mocked(fetchAdminAnnouncement).mockImplementation(async (id) =>
      id === targeted.id ? targeted : all,
    )
    await mountWorkspace()
    host.querySelectorAll<HTMLButtonElement>('.announcement-tabs button')[1]!.click()
    const button = () =>
      host.querySelector<HTMLButtonElement>('[aria-label="查看全部 3 位收件人：指定公告"]')!
    await vi.waitFor(() => expect(button()).not.toBeNull())
    const pageSize = host.querySelector<HTMLSelectElement>('[aria-label="每页公告条数"]')!
    expect(pageSize.value).toBe('10')
    expect([...pageSize.options].map(({ value }) => value)).toEqual(['5', '10', '20'])
    expect(host.querySelector('.record-audience')?.textContent).toContain('小林')
    expect(host.querySelector('.record-audience')?.textContent).not.toContain('山茶')
    expect(button().textContent).toContain('+1 人')
    button().click()
    const detail = host.querySelector<HTMLDialogElement>('.record-detail')!
    await vi.waitFor(() => expect(detail.textContent).toContain('收件人（3 人）'))
    expect(
      [...detail.querySelectorAll('.record-recipients li')].map((item) => item.textContent?.trim()),
    ).toEqual(['小林', '账号不可用 · ID 22', '山茶'])
    detail.querySelector<HTMLButtonElement>('.dialog-close')!.click()
    await nextTick()
    expect(detail.open).toBe(false)
    expect(host.querySelectorAll('tbody tr')).toHaveLength(2)
    expect(document.activeElement).toBe(button())

    host.querySelector<HTMLButtonElement>('[aria-label="查看公告详情：全体公告"]')!.click()
    await vi.waitFor(() => expect(detail.querySelector('h3')?.textContent).toBe('全体公告'))
    expect(detail.querySelector('.record-recipients')).toBeNull()
  })
})
