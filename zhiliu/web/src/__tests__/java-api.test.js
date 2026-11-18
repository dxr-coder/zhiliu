/**
 * Java 会话：java_token 存取 / 登出清理
 * 数据流：localStorage + javaLogin mock axios -> 断言 key
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'

vi.mock('axios', () => {
  const instance = {
    interceptors: { request: { use: () => {} }, response: { use: () => {} } },
    post: vi.fn(),
    get: vi.fn(),
    put: vi.fn(),
    create: () => instance,
  }
  return { default: instance }
})

describe('java session', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetModules()
  })

  it('javaLoggedIn false without token', async () => {
    const { javaLoggedIn } = await import('../api/java.js')
    expect(javaLoggedIn()).toBe(false)
  })

  it('javaLogin stores token and user', async () => {
    const axios = (await import('axios')).default
    axios.post.mockResolvedValue({
      data: { code: 200, data: { token: 'tok-abc', user: { id: 1, username: 'admin', roles: ['admin'] } } },
    })
    const { javaLogin, javaLoggedIn, javaUser } = await import('../api/java.js')
    const d = await javaLogin('admin', '123456')
    expect(d.token).toBe('tok-abc')
    expect(javaLoggedIn()).toBe(true)
    expect(javaUser().username).toBe('admin')
    expect(localStorage.getItem('java_token')).toBe('tok-abc')
  })

  it('javaLogout clears session', async () => {
    localStorage.setItem('java_token', 'x')
    localStorage.setItem('java_user', '{}')
    const { javaLogout, javaLoggedIn } = await import('../api/java.js')
    javaLogout()
    expect(javaLoggedIn()).toBe(false)
    expect(localStorage.getItem('java_token')).toBeNull()
  })
})
