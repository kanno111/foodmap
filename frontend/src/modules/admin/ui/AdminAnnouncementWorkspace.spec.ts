import { createApp, nextTick, type App } from 'vue'
import { createPinia, disposePinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useLoginStore } from '@/modules/auth'
import { clearAccessToken } from '@/shared/api/authSession'
import { mockDialog } from '@/shared/test/mockDialog'
import { searchAnnouncementRecipients } from '@/modules/announcement'
import AdminAnnouncementWorkspace from './AdminAnnouncementWorkspace.vue'

vi.mock('../api/restaurantRequestsApi', () => ({
  fetchRequestPage: vi.fn().mockResolvedValue({ counts: { PENDING: 0 } }),
}))
vi.mock('@/modules/announcement', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/modules/announcement')>()),
  searchAnnouncementRecipients: vi.fn(),
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
        { path: '/admin/reviews', component: { template: '<div />' } },
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
    const change = range.querySelector('button')!
    expect(range.textContent).toContain('全体用户')
    change.click()
    const picker = host.querySelector<HTMLDialogElement>('.recipient-dialog')!
    expect(picker.open).toBe(true)
    expect(picker.querySelector<HTMLInputElement>('input[value="all"]')!.checked).toBe(true)
    picker.querySelector<HTMLInputElement>('input[value="selected"]')!.click()
    await nextTick()
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
    expect(range.textContent).toContain('林小北、雨后米线')
    expect(host.querySelector('button[type="submit"]')?.textContent).toBe('发布公告')

    change.click()
    await nextTick()
    picker.querySelector<HTMLInputElement>('input[value="all"]')!.click()
    picker.querySelector<HTMLButtonElement>('button[aria-label="关闭选择收件人"]')!.click()
    await nextTick()
    expect(range.textContent).toContain('林小北、雨后米线')
  })
})
