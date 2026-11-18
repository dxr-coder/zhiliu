/**
 * 路由完整性：三端入口、旧路径收口、admin 守卫
 * 数据流：router 配置 -> 断言 meta/redirect，不发网络
 */
import { describe, it, expect } from 'vitest'
import router from '../router/index.js'

const find = (path) => router.getRoutes().find((r) => r.path === path)

describe('smartflow router', () => {
  it('legacy business paths redirect to /office', () => {
    for (const p of ['/tickets', '/transfer', '/assets']) {
      const r = find(p)
      expect(r, p).toBeTruthy()
      expect(r.redirect).toBeTruthy()
      expect(typeof r.redirect === 'string' ? r.redirect : r.redirect.path).toBe('/office')
    }
  })

  it('office and chat are public-less app routes', () => {
    expect(find('/office')).toBeTruthy()
    expect(find('/chat')).toBeTruthy()
    expect(find('/login')).toBeTruthy()
  })

  it('admin section requires admin and has config child', () => {
    const matches = router.resolve('/admin').matched
    expect(matches.length).toBeGreaterThan(0)
    expect(matches.some((m) => m.meta && m.meta.requiresAdmin)).toBe(true)
    const paths = router.getRoutes().map((r) => r.path)
    expect(paths.some((p) => p.includes('admin') && p.includes('config'))).toBe(true)
    expect(paths.some((p) => p.includes('admin') && p.includes('users'))).toBe(true)
    expect(paths.some((p) => p.includes('admin') && p.includes('eval'))).toBe(true)
  })

  it('unknown path goes to NotFound', () => {
    const nf = router.getRoutes().find((r) => r.path.includes('pathMatch'))
    expect(nf).toBeTruthy()
  })
})
