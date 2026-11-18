/**
 * 工作台源码不变量：三端严格、AI 挂点、无 prompt、策略标
 * 用读源码断言，防止重构时把关键能力改丢
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const read = (p) => readFileSync(join(root, p), 'utf-8')

describe('Office.vue invariants', () => {
  const src = read('views/Office.vue')

  it('keeps strict three-view switch', () => {
    expect(src).toContain('visibleViews')
    expect(src).toContain('员工端')
    expect(src).toContain('主管端')
    expect(src).toContain('管理端')
    expect(src).toContain('isManagerUser')
    expect(src).toContain('isAdminUser')
  })

  it('wires AI points and Java session', () => {
    expect(src).toContain('/ai/v1')
    expect(src).toContain('aiTriage')
    expect(src).toContain('aiDraftLeave')
    expect(src).toContain('javaApi')
    expect(src).toContain('javaLogin')
  })

  it('shows expense policy flags and user picker', () => {
    expect(src).toContain('policyFlags')
    expect(src).toContain('flagLabel')
    expect(src).toContain('openPick')
    expect(src).toContain('pickUserId')
  })

  it('has no browser prompt/confirm leftovers', () => {
    expect(src.includes('prompt(')).toBe(false)
    expect(src.includes('confirm(')).toBe(false)
  })

  it('guards room booking with day occupancy', () => {
    expect(src).toContain('checkRoomConflict')
    expect(src).toContain('dayBookings')
  })
})

describe('Config.vue invariants', () => {
  const src = read('views/admin/Config.vue')

  it('covers nine config tabs', () => {
    for (const id of [
      'leave-types', 'sla', 'templates', 'depts', 'policies',
      'budgets', 'roles', 'rooms', 'categories',
    ]) {
      expect(src, id).toContain(id)
    }
  })

  it('uses javaApi and inline editor', () => {
    expect(src).toContain('javaApi')
    expect(src).toContain('openEdit')
    expect(src.includes('prompt(')).toBe(false)
    expect(src).toContain('meeting-rooms')
    expect(src).toContain('asset-categories')
  })
})

describe('Login.vue invariants', () => {
  const src = read('views/Login.vue')

  it('logs into Java first', () => {
    expect(src).toContain('javaLogin')
    expect(src.indexOf('javaLogin')).toBeGreaterThan(-1)
    // 主登录只认 Java
    expect(src).toContain('只认 Java')
  })
})
