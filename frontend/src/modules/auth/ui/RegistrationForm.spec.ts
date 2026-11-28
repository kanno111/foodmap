import { createApp, nextTick, type App } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RegistrationForm from './RegistrationForm.vue'
import { register, sendRegistrationCode } from '../api/authApi'

vi.mock('../api/authApi', () => ({ register: vi.fn(), sendRegistrationCode: vi.fn() }))

describe('注册方式', () => {
  let host: HTMLDivElement
  let app: App
  beforeEach(async () => {
    vi.resetAllMocks()
    vi.mocked(register).mockRejectedValue(new Error('测试请求已捕获'))
    vi.mocked(sendRegistrationCode).mockResolvedValue()
    host = document.createElement('div')
    document.body.append(host)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: ['/register', '/login', '/'].map((path) => ({
        path,
        component: { template: '<div />' },
      })),
    })
    await router.push('/register')
    app = createApp(RegistrationForm).use(createPinia()).use(router)
    app.mount(host)
  })

  afterEach(() => {
    app.unmount()
    host.remove()
  })

  function fill(fields: Record<string, string>) {
    for (const [id, value] of Object.entries(fields)) {
      const input = host.querySelector<HTMLInputElement>(`#register-${id}`)!
      input.value = value
      input.dispatchEvent(new Event('input', { bubbles: true }))
    }
  }

  async function submit() {
    fill({
      code: '012345',
      username: '测试同学',
      password: 'GoodPassword',
      'confirm-password': 'GoodPassword',
    })
    host
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await nextTick()
  }

  it.each(['', 'k7m4p9txw2c8n6rd'])('学生注册允许留空或填写推荐码 %s', async (code) => {
    expect(host.querySelector('label[for=register-invite]')?.textContent).toBe('邀请码（选填）')
    expect(host.querySelector('#register-invite-contact')).toBeNull()
    fill({ email: '001234', invite: code })
    await submit()
    expect(register).toHaveBeenCalledWith(
      expect.objectContaining({
        email: '001234@std.uestc.edu.cn',
        inviteCode: code ? code.toUpperCase() : undefined,
      }),
    )
  })

  it('校友使用自己的个人邮箱和管理员邀请码申请验证码和注册', async () => {
    const invite = 'ABCDEFGHJKMNPQRSTVWXYZ2345'
    const alumni = [...host.querySelectorAll<HTMLButtonElement>('.registration-modes button')][1]!
    alumni.click()
    await nextTick()
    expect(host.querySelector('#email-suffix')).toBeNull()
    expect(host.querySelector('label[for=register-invite]')?.textContent).toBe('校友邀请码')
    expect(host.querySelector('#register-invite-contact')?.textContent).toBe(
      '（联系qq1010958435获取邀请码）',
    )
    const send = host.querySelector<HTMLButtonElement>('.code-button')!
    fill({ email: 'Alumni@example.com' })
    send.click()
    expect(sendRegistrationCode).not.toHaveBeenCalled()
    fill({ invite: invite.toLowerCase() })
    send.click()
    await vi.waitFor(() =>
      expect(host.querySelector('.code-button')?.textContent).toContain('秒后重试'),
    )
    expect(sendRegistrationCode).toHaveBeenCalledExactlyOnceWith('alumni@example.com', invite)
    await submit()
    expect(register).toHaveBeenCalledWith(
      expect.objectContaining({ email: 'alumni@example.com', inviteCode: invite }),
    )
  })
})
