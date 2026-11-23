import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { register, sendRegistrationCode } from '../api/authApi'
import { useRegistrationStore } from './registration.store'
import type { RegistrationRequest } from './registration.types'
import { clearAccessToken, getAccessToken } from '@/shared/api/authSession'
import { useLoginStore } from './login.store'

vi.mock('../api/authApi', () => ({
  register: vi.fn(),
  sendRegistrationCode: vi.fn(),
}))

const request: RegistrationRequest = {
  email: 'student@std.uestc.edu.cn',
  verificationCode: '123456',
  username: 'foodmap_user',
  password: 'Foodmap2026',
}

describe('registration store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    clearAccessToken()
  })

  it('reports success after the registration code is accepted', async () => {
    vi.mocked(sendRegistrationCode).mockResolvedValue()
    const store = useRegistrationStore()

    await expect(store.sendCode(request.email)).resolves.toBe(true)

    expect(sendRegistrationCode).toHaveBeenCalledWith(request.email)
    expect(store.sendingCode).toBe(false)
    expect(store.error).toBeNull()
  })

  it('keeps a safe message when sending the code fails', async () => {
    vi.mocked(sendRegistrationCode).mockRejectedValue(new Error('验证码发送失败'))
    const store = useRegistrationStore()

    await expect(store.sendCode(request.email)).resolves.toBe(false)

    expect(store.sendError).toBe('验证码发送失败')
    expect(store.error).toBeNull()
    expect(store.sendingCode).toBe(false)
  })

  it('ignores a send failure from a previous registration page', async () => {
    let fail!: (error: Error) => void
    vi.mocked(sendRegistrationCode).mockReturnValueOnce(
      new Promise((_, reject) => {
        fail = reject
      }),
    )
    const store = useRegistrationStore()
    const previous = store.sendCode(request.email)
    store.reset()
    fail(new Error('验证码发送失败'))

    await expect(previous).resolves.toBe(false)
    expect(store.sendError).toBeNull()
    expect(store.sendingCode).toBe(false)
  })

  it('marks the account as registered only after the backend succeeds', async () => {
    vi.mocked(register).mockResolvedValue({
      accessToken: 'registered-session',
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id: 42, username: 'foodmap_user', roles: ['ROLE_USER'] },
    })
    const store = useRegistrationStore()

    await expect(store.submit(request)).resolves.toBe(true)

    expect(register).toHaveBeenCalledWith(request)
    expect(store.registered).toBe(true)
    expect(store.registering).toBe(false)
    expect(getAccessToken()).toBe('registered-session')
    expect(useLoginStore().authenticated).toBe(true)
  })
})
