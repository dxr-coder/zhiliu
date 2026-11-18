<!--
  Workbench - leave/expense/ticket/approval/rooms/assets
  Data flow: api -> /api proxy -> business service; session token in localStorage
-->
<script setup>
import { ref, computed, onMounted, watch } from "vue"
import { useRouter } from "vue-router"
import { javaApi, javaLogin, javaLogout, javaUser, javaLoggedIn } from "../api/java"
import { toast } from "../composables/useToast"

const router = useRouter()
const loggedIn = ref(javaLoggedIn())
const user = ref(javaUser())
const username = ref("")
const password = ref("")
const loading = ref(false)
const tab = ref("leaves")
const view = ref("employee") // employee | manager | admin
const VIEW_TABS = {
  employee: [
    { id: "leaves", label: "请假" },
    { id: "expenses", label: "报销" },
    { id: "tickets", label: "工单" },
    { id: "rooms", label: "会议室" },
    { id: "assets", label: "资产" },
    { id: "purchases", label: "采购" },
    { id: "notices", label: "通知" },
  ],
  manager: [
    { id: "approvals", label: "待我审" },
    { id: "tickets", label: "工单队列" },
    { id: "expenses", label: "报销审批" },
    { id: "leaves", label: "请假审批" },
    { id: "purchases", label: "采购审批" },
  ],
  admin: [
    { id: "tickets", label: "工单全量" },
    { id: "expenses", label: "报销全量" },
    { id: "leaves", label: "请假全量" },
    { id: "approvals", label: "审批" },
    { id: "rooms", label: "会议室" },
    { id: "assets", label: "资产" },
    { id: "purchases", label: "采购" },
    { id: "offboard", label: "离职" },
    { id: "notices", label: "通知" },
  ],
}
const tabs = computed(() => VIEW_TABS[view.value] || VIEW_TABS.employee)
const LEAVE_TYPE = { annual: "年假", sick: "病假", personal: "事假", comp: "调休" }
const LEAVE_STATUS = { draft:"草稿", submitted:"已提交", pending_manager:"待主管审批", pending_hr:"待人事审批", approved:"已通过", rejected:"已驳回", cancelled:"已撤回", effective:"已生效", done:"已核销", cancelled_after_approve:"已销假" }
const EXP_STATUS = { draft:"草稿", pending_manager:"待主管审批", pending_finance:"待财务审批", approved:"已通过", paid:"已打款", rejected:"已驳回", cancelled:"已撤回", voided:"已冲销" }
const EXP_TYPE = { travel:"差旅", meal:"餐饮", office:"办公", train:"交通", other:"其他" }
const TKT_STATUS = { triage:"分诊中", assigned:"已受理", in_progress:"处理中", waiting_user:"等你补充", waiting_third:"等第三方", escalated:"已升级", resolved:"已解决", closed:"已关闭", rejected:"已驳回", duplicate:"已合并" }
const TKT_CAT = {
  "it.network": "IT·网络", "it.hardware": "IT·硬件", hr: "人事",
  finance: "财务", admin: "行政", mail: "邮件", bug: "缺陷",
}
const TKT_PRI = { p0:"P0 紧急", p1:"P1 高", p2:"P2 中", p3:"P3 低" }
const ROOM_STATUS = { booked:"已预订", checked_in:"已签到", done:"已完成", cancelled:"已取消", no_show:"爽约", late_cancel:"临期取消" }
const ASSET_STATUS = { stock:"在库", assigned:"在用", repair:"维修中", lost:"丢失", lost_pending_compensate:"赔偿中", dispose:"待报废", disposed:"已报废" }
const PO_STATUS = { demand:"待比价", quoted:"已比价", approved:"已通过", ordered:"已下单", received:"已到货", accepted:"已验收", rejected:"已驳回", cancelled:"已取消" }

const leaves = ref([])
const expenses = ref([])
const tickets = ref([])
const approvals = ref([])
const rooms = ref([])
const bookings = ref([])
const assets = ref([])
const notices = ref([])
const purchases = ref([])
const offboards = ref([])
const unread = ref(0)
const leaveForm = ref({ type: "annual", startDate: "", endDate: "", reason: "", aiDraft: false })
const leaveUtter = ref("")
const expenseForm = ref({ expense: { type: "travel", amountTotal: null, costCenter: "" }, items: [{ name: "", amount: null, invoiceNo: "", expenseDate: "" }], idempotencyKey: "" })
const ticketForm = ref({ title: "", category: "it.network", priority: "p2" })
const triageHint = ref(null)
const bookForm = ref({ roomId: 1, startAt: "", endAt: "", title: "" })
const poForm = ref({ title: "", category: "office", qty: 1, budget: null, reason: "" })
const importMsg = ref("")
const noticeUnread = ref(false)
const noticeType = ref("")
const dayBookings = ref([])
const roomConflict = ref("")

const err = (e) => toast.error(e.message || "操作失败")
const ok = (m) => toast.success(m)
const zh = (dict, k) => dict[k] || k || "-"
const day = (s) => (s ? String(s).slice(0, 10) : "-")
const dt = (s) => (s ? String(s).replace("T", " ").slice(0, 16) : "-")

const isAdminUser = computed(() => {
  const roles = user.value?.roles || []
  return roles.includes("admin") || user.value?.username === "admin"
})
const isManagerUser = computed(() => {
  const roles = user.value?.roles || []
  return roles.some((r) => ["manager", "admin", "agent", "finance", "hr"].includes(r))
})

// 严格模式：按角色只露能进的端
const visibleViews = computed(() => {
  const list = [{ id: 'employee', label: '员工端' }]
  if (isManagerUser.value) list.push({ id: 'manager', label: '主管端' })
  if (isAdminUser.value) list.push({ id: 'admin', label: '管理端' })
  return list
})

const switchView = (v) => {
  if (!visibleViews.value.some((x) => x.id === v)) return
  view.value = v
  const list = VIEW_TABS[v]
  if (!list.find((t) => t.id === tab.value)) tab.value = list[0].id
  loadTab()
}

const doLogin = async () => {
  loading.value = true
  try {
    const d = await javaLogin(username.value.trim(), password.value)
    user.value = d.user
    loggedIn.value = true
    if (isAdminUser.value) view.value = "admin"
    else if (isManagerUser.value) view.value = "manager"
    else view.value = "employee"
    tab.value = (VIEW_TABS[view.value][0] || {}).id || "leaves"
    toast.success("欢迎，" + d.user.username)
    loadTab()
  } catch (e) {
    toast.error(e.message || "登录失败")
  } finally { loading.value = false }
}
const doLogout = () => { javaLogout(); loggedIn.value = false; user.value = null }

const loadTab = async () => {
  if (!loggedIn.value) return
  try {
    if (tab.value === "leaves") {
      if (view.value === "admin") {
        const r = await javaApi.get("/leaves", { params: { page: 1, size: 50 } })
        leaves.value = (r.data.data && r.data.data.records) || []
      } else {
        leaves.value = (await javaApi.get("/leaves/my")).data.data || []
      }
      if (!leaveForm.value.startDate) {
        const d0 = new Date(); d0.setDate(d0.getDate() + 1)
        const d1 = new Date(); d1.setDate(d1.getDate() + 3)
        const pad = (n) => String(n).padStart(2, "0")
        leaveForm.value.startDate = d0.getFullYear() + "-" + pad(d0.getMonth() + 1) + "-" + pad(d0.getDate())
        leaveForm.value.endDate = d1.getFullYear() + "-" + pad(d1.getMonth() + 1) + "-" + pad(d1.getDate())
      }
    }
    else if (tab.value === "expenses") {
      if (view.value === "admin") {
        const r = await javaApi.get("/expenses", { params: { page: 1, size: 50 } })
        expenses.value = (r.data.data && r.data.data.records) || []
      } else {
        expenses.value = (await javaApi.get("/expenses/my")).data.data || []
      }
    }
    else if (tab.value === "tickets") {
      const r = await javaApi.get("/tickets", { params: { page: 1, size: 50 } })
      tickets.value = (r.data.data && r.data.data.records) || []
    }
    else if (tab.value === "approvals") {
      approvals.value = (await javaApi.get("/approvals/todo")).data.data || []
      try {
        const pu = await javaApi.get("/users/pickable")
        pickUsers.value = pu.data.data || []
      } catch { /* 非必要 */ }
    }
    else if (tab.value === "rooms") {
      rooms.value = (await javaApi.get("/rooms")).data.data || []
      bookings.value = (await javaApi.get("/rooms/bookings/my")).data.data || []
      try {
        const db = await javaApi.get("/rooms/bookings/day")
        dayBookings.value = db.data.data || []
      } catch { dayBookings.value = [] }
      checkRoomConflict()
      if (!bookForm.value.startAt) {
        const d = new Date(); d.setDate(d.getDate() + 1)
        const pad = (n) => String(n).padStart(2, "0")
        const dayStr = d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate())
        bookForm.value.startAt = dayStr + "T10:00"
        bookForm.value.endAt = dayStr + "T11:00"
      }
    }
    else if (tab.value === "assets") assets.value = (await javaApi.get("/assets")).data.data || []
    else if (tab.value === "purchases") {
      if (view.value === "employee") {
        purchases.value = (await javaApi.get("/purchases/my")).data.data || []
      } else {
        purchases.value = (await javaApi.get("/purchases")).data.data || []
      }
    }
    else if (tab.value === "offboard") {
      offboards.value = (await javaApi.get("/assets/offboard")).data.data || []
    }
    else if (tab.value === "notices") {
      const params = {}
      if (noticeUnread.value) params.unread = true
      if (noticeType.value) params.type = noticeType.value
      notices.value = (await javaApi.get("/notices", { params })).data.data || []
      const b = await javaApi.get("/notices/unread-count")
      unread.value = (b.data.data && b.data.data.count) || 0
    }
  } catch (e) { err(e) }
}
const checkRoomConflict = () => {
  roomConflict.value = ""
  const f = bookForm.value
  if (!f.startAt || !f.endAt || !f.roomId) return
  const s = f.startAt
  const e = f.endAt
  if (e <= s) { roomConflict.value = "结束须晚于开始"; return }
  const hit = dayBookings.value.find(b =>
    Number(b.roomId) === Number(f.roomId) && b.startAt < e && b.endAt > s
  )
  if (hit) roomConflict.value = `该时段已被占用（${dt(hit.startAt)} ~ ${dt(hit.endAt)}）`
}

watch(tab, loadTab)
watch(bookForm, checkRoomConflict, { deep: true })
onMounted(() => {
  if (!loggedIn.value) return
  // 刷新后夹到最高可用端，防止越权残留
  const ids = visibleViews.value.map((x) => x.id)
  if (!ids.includes(view.value)) view.value = ids[ids.length - 1]
  const list = VIEW_TABS[view.value] || VIEW_TABS.employee
  if (!list.find((x) => x.id === tab.value)) tab.value = list[0].id
  loadTab()
})

const submitLeave = async () => {
  try { await javaApi.post("/leaves", leaveForm.value); ok("请假已提交，等待主管审批")
    leaveForm.value = { type: "annual", startDate: "", endDate: "", reason: "" }; loadTab()
  } catch (e) { err(e) }
}
const submitExpense = async () => {
  const f = expenseForm.value
  if (!f.idempotencyKey) f.idempotencyKey = "web-" + Date.now()
  try {
    await javaApi.post("/expenses", f); ok("报销已提交，等待主管审批")
    expenseForm.value = { expense: { type: "travel", amountTotal: null, costCenter: "" }, items: [{ name: "", amount: null, invoiceNo: "", expenseDate: "" }], idempotencyKey: "" }
    loadTab()
  } catch (e) { err(e) }
}
const addItem = () => expenseForm.value.items.push({ name: "", amount: null, invoiceNo: "", expenseDate: "" })
const submitTicket = async () => {
  try { await javaApi.post("/tickets", ticketForm.value); ok("工单已创建（分诊中）")
    ticketForm.value = { title: "", category: "it.network", priority: "p2" }; loadTab()
  } catch (e) { err(e) }
}
const transition = async (id, toStatus, reason) => {
  try { await javaApi.post("/tickets/" + id + "/transition", { toStatus, reason }); ok("已更新为「" + zh(TKT_STATUS, toStatus) + "」"); loadTab()
  } catch (e) { err(e) }
}
const approveLeave = async (id, agree) => {
  try { await javaApi.post("/leaves/" + id + "/approve", { agree, comment: agree ? "同意" : "不同意" }); ok(agree ? "已同意" : "已驳回"); loadTab()
  } catch (e) { err(e) }
}
const approveExpense = async (id, agree) => {
  try { await javaApi.post("/expenses/" + id + "/approve", { agree, comment: agree ? "同意" : "不同意" }); ok(agree ? "已同意" : "已驳回"); loadTab()
  } catch (e) { err(e) }
}
const payExpense = async (id) => {
  try { await javaApi.post("/expenses/" + id + "/pay"); ok("已打款"); loadTab() } catch (e) { err(e) }
}
const withdrawLeave = async (id) => {
  try { await javaApi.post("/leaves/" + id + "/withdraw"); ok("已撤回"); loadTab() } catch (e) { err(e) }
}
const withdrawExpense = async (id) => {
  try { await javaApi.post("/expenses/" + id + "/withdraw"); ok("已撤回"); loadTab() } catch (e) { err(e) }
}
// 转办/加签：选人下拉（不背 ID）
const pickUsers = ref([])
const pickMode = ref(null) // null | 'transfer' | 'addSign'
const pickInstanceId = ref(null)
const pickUserId = ref(null)

const openPick = async (instanceId, mode) => {
  pickInstanceId.value = instanceId
  pickMode.value = mode
  pickUserId.value = null
  if (!pickUsers.value.length) {
    try {
      const r = await javaApi.get("/users/pickable")
      pickUsers.value = r.data.data || []
    } catch (e) { err(e) }
  }
}
const confirmPick = async () => {
  if (!pickUserId.value) { toast.error("请选择人员"); return }
  const path = pickMode.value === "transfer" ? "transfer" : "add-sign"
  try {
    await javaApi.post("/approvals/" + pickInstanceId.value + "/" + path, {
      toUserId: Number(pickUserId.value),
      comment: pickMode.value === "transfer" ? "转办" : "加签",
    })
    ok(pickMode.value === "transfer" ? "已转办" : "已加签")
    pickMode.value = null
    loadTab()
  } catch (e) { err(e) }
}
const cancelPick = () => { pickMode.value = null }

const flagLabel = (f) => ({
  missing_invoice: "缺票", over_daily_limit: "超日限额", over_standard: "超标准",
  budget_low: "预算不足",
}[f] || f)
const parseFlags = (s) => {
  try { return JSON.parse(s || "[]") } catch { return [] }
}
const agreeApproval = async (instanceId) => {
  try { await javaApi.post("/approvals/" + instanceId + "/agree", { comment: "同意" }); ok("审批通过"); loadTab() } catch (e) { err(e) }
}
const rejectApproval = async (instanceId) => {
  try { await javaApi.post("/approvals/" + instanceId + "/reject", { comment: "驳回" }); ok("已驳回"); loadTab() } catch (e) { err(e) }
}
const bookRoom = async () => {
  checkRoomConflict()
  if (roomConflict.value) { toast.error(roomConflict.value); return }
  try { await javaApi.post("/rooms/bookings", bookForm.value); ok("预订成功"); loadTab() } catch (e) { err(e) }
}
const checkIn = async (id) => {
  try { await javaApi.post("/rooms/bookings/" + id + "/check-in"); ok("已签到"); loadTab() } catch (e) { err(e) }
}
const cancelBook = async (id) => {
  try { await javaApi.post("/rooms/bookings/" + id + "/cancel"); ok("已取消"); loadTab() } catch (e) { err(e) }
}
const applyAsset = async (id) => {
  try { await javaApi.post("/assets/" + id + "/apply", {}); ok("领用成功"); loadTab() } catch (e) { err(e) }
}
const returnAsset = async (id) => {
  try { await javaApi.post("/assets/" + id + "/return"); ok("归还成功"); loadTab() } catch (e) { err(e) }
}
const readAll = async () => {
  try { await javaApi.post("/notices/read-all"); loadTab() } catch (e) { err(e) }
}

// ── AI 六点位（Python :8000，失败静默降级纯表单）──
const aiPost = async (path, body) => {
  try {
    const r = await fetch("/ai/v1" + path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    })
    if (!r.ok) return null
    return await r.json()
  } catch { return null }
}
const aiTriage = async () => {
  const t = ticketForm.value.title
  if (!t) return
  const d = await aiPost("/triage", { text: t })
  if (d && !d.degraded) {
    ticketForm.value.category = d.category || ticketForm.value.category
    ticketForm.value.priority = d.priority || ticketForm.value.priority
    triageHint.value = d
    toast.success("AI 分诊预填 conf " + d.conf + (d.human_queue ? " · 人工队列" : ""))
  } else {
    triageHint.value = { degraded: true }
  }
}
const aiDraftLeave = async () => {
  const d = await aiPost("/draft", { biz_type: "leave", utterance: leaveUtter.value || leaveForm.value.reason })
  if (d && !d.degraded && d.fields) {
    const f = d.fields
    if (f.type) leaveForm.value.type = f.type
    if (f.startDate) leaveForm.value.startDate = f.startDate
    if (f.endDate) leaveForm.value.endDate = f.endDate
    if (f.reason) leaveForm.value.reason = f.reason
    leaveForm.value.aiDraft = true
    toast.success("AI 草稿已填入，请核对后提交")
  } else {
    toast.error("AI 不可用，已降级纯表单")
  }
}
const aiAssist = async (t) => {
  const d = await aiPost("/assist", { title: t.title, body: t.description || "" })
  if (d && !d.degraded) {
    toast.success("坐席副驾草稿已生成（不自动发送）")
    alert(d.reply_draft)
  } else toast.error("AI 降级：请人工回复")
}

// ── 采购 ──
const submitPo = async () => {
  try { await javaApi.post("/purchases", poForm.value); ok("需求已提报")
    poForm.value = { title: "", category: "office", qty: 1, budget: null, reason: "" }; loadTab()
  } catch (e) { err(e) }
}
const poAction = async (id, action, body) => {
  try { await javaApi.post("/purchases/" + id + "/" + action, body || {}); ok("已更新"); loadTab() } catch (e) { err(e) }
}
const quoteVendorA = ref(""); const quotePriceA = ref(null)
const quoteVendorB = ref(""); const quotePriceB = ref(null)
const quoteTargetId = ref(null)
const openQuotes = (p) => { quoteTargetId.value = p.id }
const submitQuotes = async () => {
  if (!quoteTargetId.value) return
  const quotes = [
    { vendor: quoteVendorA.value || "供应商A", price: Number(quotePriceA.value) || 0 },
    { vendor: quoteVendorB.value || "供应商B", price: Number(quotePriceB.value) || 0 },
  ]
  if (quotes.some(q => !q.price)) { toast.error("两家价格都要填"); return }
  try {
    await javaApi.post("/purchases/" + quoteTargetId.value + "/quotes", { quotesJson: JSON.stringify(quotes) })
    ok("比价已录入"); quoteTargetId.value = null
    quoteVendorA.value = ""; quotePriceA.value = null
    quoteVendorB.value = ""; quotePriceB.value = null
    loadTab()
  } catch (e) { err(e) }
}

// ── 导入导出 ──
const download = async (url, filename) => {
  try {
    const r = await javaApi.get(url, { responseType: "blob" })
    const blob = new Blob([r.data])
    const a = document.createElement("a")
    a.href = URL.createObjectURL(blob)
    a.download = filename
    a.click()
    URL.revokeObjectURL(a.href)
  } catch (e) { err(e) }
}
const importFile = async (url, input) => {
  const file = input.files && input.files[0]
  if (!file) return
  const fd = new FormData()
  fd.append("file", file)
  try {
    const r = await javaApi.post(url, fd, { headers: { "Content-Type": "multipart/form-data" } })
    const d = r.data.data
    importMsg.value = "导入成功 " + d.created + " 条" + (d.errors && d.errors.length ? "，错误 " + d.errors.length + " 行" : "")
    ok(importMsg.value)
    input.value = ""
    loadTab()
  } catch (e) { err(e) }
}

// ── 离职五门槛 ──
const offboardUid = ref("")
const openOffboard = async () => {
  const uid = Number(offboardUid.value)
  if (!uid) { toast.error("填写离职用户 ID"); return }
  try { await javaApi.post("/assets/offboard", { userId: uid }); ok("已开离职单"); offboardUid.value = ""; loadTab() } catch (e) { err(e) }
}
const tickOff = async (c, item) => {
  try { await javaApi.post("/assets/offboard/" + c.id + "/tick", { item, done: true }); ok("已勾选"); loadTab() } catch (e) { err(e) }
}
const completeOff = async (c) => {
  try { await javaApi.post("/assets/offboard/" + c.id + "/complete", { forceAll: false }); ok("离职完成"); loadTab() } catch (e) { err(e) }
}
const forceOff = async (c) => {
  try { await javaApi.post("/assets/offboard/" + c.id + "/complete", { forceAll: true }); ok("离职完成（资产已校验清零）"); loadTab() } catch (e) { err(e) }
}
</script>
<template>
  <div class="min-h-dvh bg-body p-6 max-w-6xl mx-auto">
    <header class="flex items-center justify-between mb-6">
      <div>
        <h1 class="text-2xl font-semibold">工作台</h1>
        <p class="text-sm opacity-60">员工办事 · 主管审批 · 管理配置</p>
      </div>
      <div v-if="loggedIn" class="flex items-center gap-3 text-sm">
        <button v-if="isAdminUser" class="px-3 py-1.5 rounded-lg border border-line hover:bg-body" @click="router.push('/admin/config')">配置中心</button>
        <span class="px-3 py-1 rounded-full bg-brand-soft text-brand">{{ user?.username }}</span>
        <button class="px-3 py-1.5 rounded-lg border border-line hover:bg-body" @click="doLogout">退出</button>
      </div>
    </header>
    <div v-if="!loggedIn" class="max-w-md mx-auto mt-16 p-8 rounded-2xl border border-line space-y-4">
      <h2 class="text-lg font-semibold">登录</h2>
      <input v-model="username" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent" placeholder="用户名" />
      <input v-model="password" type="password" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent" placeholder="密码" @keyup.enter="doLogin" />
      <button class="w-full py-2.5 rounded-lg bg-brand text-white font-medium disabled:opacity-50" :disabled="loading" @click="doLogin">{{ loading ? "登录中..." : "登录" }}</button>
    </div>
    <div v-else>
      <div v-if="visibleViews.length > 1" class="flex flex-wrap items-center gap-2 mb-4">
        <button v-for="v in visibleViews"
          :key="v.id"
          class="px-3 py-1.5 rounded-lg text-sm border transition"
          :class="view === v.id ? 'bg-ink text-white border-ink' : 'border-line hover:bg-body'"
          @click="switchView(v.id)">{{ v.label }}</button>
      </div>
      <nav class="flex flex-wrap gap-2 mb-5">
        <button v-for="t in tabs" :key="t.id" class="px-4 py-2 rounded-full text-sm border transition" :class="tab === t.id ? 'bg-brand text-white border-brand' : 'border-line hover:bg-body'" @click="tab = t.id">{{ t.label }}</button>
      </nav>
      <section v-if="tab === 'leaves'" class="grid md:grid-cols-2 gap-6">
        <div class="p-5 rounded-2xl border border-line space-y-3">
          <h3 class="font-medium">发起请假</h3>
          <div class="flex gap-2">
            <input v-model="leaveUtter" placeholder="一句话：我明天请年假" class="flex-1 px-3 py-2 rounded-lg border border-line bg-transparent text-sm" />
            <button class="px-3 py-2 rounded-lg border border-line text-sm" @click="aiDraftLeave">AI 草稿</button>
          </div>
          <select v-model="leaveForm.type" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent">
            <option value="annual">年假</option><option value="sick">病假</option><option value="personal">事假</option><option value="comp">调休</option>
          </select>
          <div class="grid grid-cols-2 gap-2">
            <input v-model="leaveForm.startDate" type="date" class="px-3 py-2 rounded-lg border border-line bg-transparent" />
            <input v-model="leaveForm.endDate" type="date" class="px-3 py-2 rounded-lg border border-line bg-transparent" />
          </div>
          <textarea v-model="leaveForm.reason" rows="2" placeholder="事由（必填）" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent"></textarea>
          <div v-if="leaveForm.aiDraft" class="text-xs opacity-60">含 AI 草稿，请核对后提交</div>
          <button class="w-full py-2 rounded-lg bg-brand text-white" @click="submitLeave">提交请假</button>
        </div>
        <div class="p-5 rounded-2xl border border-line">
          <h3 class="font-medium mb-3">我的请假</h3>
          <div v-if="!leaves.length" class="text-sm opacity-50">暂无数据</div>
          <div v-for="l in leaves" :key="l.id" class="py-3 border-b border-line">
            <div class="font-medium text-sm">{{ l.leaveNo }}</div>
            <div class="text-sm opacity-70 mt-0.5">{{ zh(LEAVE_TYPE, l.type) }} · {{ day(l.startDate) }} ~ {{ day(l.endDate) }}</div>
            <div class="text-sm opacity-60 mt-0.5">事由：{{ l.reason || "—" }}</div>
            <div class="mt-1"><span class="px-2 py-0.5 rounded text-xs bg-gray-100">{{ zh(LEAVE_STATUS, l.status) }}</span></div>
            <div v-if="String(l.status).startsWith('pending') || l.status === 'submitted'" class="mt-2 flex gap-2 flex-wrap">
              <button v-if="String(l.status).startsWith('pending')" class="text-xs px-3 py-1 rounded bg-brand text-white" @click="approveLeave(l.id, true)">同意</button>
              <button v-if="String(l.status).startsWith('pending')" class="text-xs px-3 py-1 rounded border border-line" @click="approveLeave(l.id, false)">驳回</button>
              <button v-if="l.userId === user?.id || user?.username === 'admin'" class="text-xs px-3 py-1 rounded border border-line" @click="withdrawLeave(l.id)">撤回</button>
            </div>
          </div>
        </div>
      </section>
      <section v-else-if="tab === 'expenses'" class="grid md:grid-cols-2 gap-6">
        <div class="p-5 rounded-2xl border border-line space-y-3">
          <div class="flex items-center justify-between">
            <h3 class="font-medium">发起报销</h3>
            <div class="flex gap-1 text-xs">
              <button class="px-2 py-1 rounded border border-line" @click="download('/expenses/export', 'expenses.xlsx')">导出</button>
              <label class="px-2 py-1 rounded border border-line cursor-pointer">
                导入
                <input type="file" accept=".xlsx" class="hidden" @change="(e) => importFile('/expenses/import', e.target)" />
              </label>
            </div>
          </div>
          <select v-model="expenseForm.expense.type" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent">
            <option value="travel">差旅</option><option value="meal">餐饮</option><option value="office">办公</option><option value="train">交通</option><option value="other">其他</option>
          </select>
          <input v-model.number="expenseForm.expense.amountTotal" type="number" step="0.01" placeholder="总额" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent" />
          <div v-for="(it, i) in expenseForm.items" :key="i" class="grid grid-cols-3 gap-2">
            <input v-model="it.name" placeholder="明细" class="px-2 py-1.5 rounded border border-line bg-transparent text-sm" />
            <input v-model.number="it.amount" type="number" step="0.01" placeholder="金额" class="px-2 py-1.5 rounded border border-line bg-transparent text-sm" />
            <input v-model="it.invoiceNo" placeholder="发票号" class="px-2 py-1.5 rounded border border-line bg-transparent text-sm" />
          </div>
          <div class="flex gap-2">
            <button class="text-sm px-3 py-1.5 rounded border border-line" @click="addItem">+ 明细</button>
            <button class="flex-1 py-2 rounded-lg bg-brand text-white" @click="submitExpense">提交报销</button>
          </div>
        </div>
        <div class="p-5 rounded-2xl border border-line">
          <h3 class="font-medium mb-3">我的报销</h3>
          <div v-if="!expenses.length" class="text-sm opacity-50">暂无数据</div>
          <div v-for="e in expenses" :key="e.id" class="py-3 border-b border-line text-sm">
            <div class="font-medium">{{ e.expenseNo }} · ¥{{ e.amountTotal }}</div>
            <div class="opacity-70 mt-0.5">{{ zh(EXP_TYPE, e.type) }}</div>
            <div class="mt-1 flex flex-wrap gap-1">
              <span class="px-2 py-0.5 rounded text-xs bg-gray-100">{{ zh(EXP_STATUS, e.status) }}</span>
              <span v-for="f in parseFlags(e.policyFlags)" :key="f"
                class="px-2 py-0.5 rounded text-xs bg-amber-50 text-amber-700 border border-amber-200">
                {{ flagLabel(f) }}
              </span>
            </div>
            <div v-if="String(e.status).startsWith('pending') || e.status === 'draft'" class="mt-2 flex gap-2 flex-wrap">
              <button v-if="String(e.status).startsWith('pending')" class="text-xs px-3 py-1 rounded bg-brand text-white" @click="approveExpense(e.id, true)">同意</button>
              <button v-if="String(e.status).startsWith('pending')" class="text-xs px-3 py-1 rounded border border-line" @click="approveExpense(e.id, false)">驳回</button>
              <button v-if="e.userId === user?.id || user?.username === 'admin'" class="text-xs px-3 py-1 rounded border border-line" @click="withdrawExpense(e.id)">撤回</button>
            </div>
            <button v-if="e.status === 'approved'" class="mt-2 text-xs px-3 py-1 rounded bg-brand text-white" @click="payExpense(e.id)">标记打款</button>
          </div>
        </div>
      </section>
      <section v-else-if="tab === 'tickets'" class="grid md:grid-cols-2 gap-6">
        <div class="p-5 rounded-2xl border border-line space-y-3">
          <h3 class="font-medium">新建工单</h3>
          <input v-model="ticketForm.title" placeholder="标题（失焦 AI 分诊预填）" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent" @blur="aiTriage" />
          <select v-model="ticketForm.category" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent">
            <option value="it.network">IT 网络</option><option value="it.hardware">IT 硬件</option><option value="hr">人事</option><option value="finance">财务</option><option value="admin">行政</option>
          </select>
          <select v-model="ticketForm.priority" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent">
            <option value="p0">P0 紧急</option><option value="p1">P1 高</option><option value="p2">P2 中</option><option value="p3">P3 低</option>
          </select>
          <div v-if="triageHint && !triageHint.degraded" class="text-xs opacity-60">AI：{{ zh(TKT_CAT, triageHint.category) }} · conf {{ triageHint.conf }}<span v-if="triageHint.human_queue"> · 人工队列</span></div>
          <div v-else-if="triageHint && triageHint.degraded" class="text-xs opacity-60">AI 已降级，纯表单</div>
          <button class="w-full py-2 rounded-lg bg-brand text-white" @click="submitTicket">创建工单</button>
        </div>
        <div class="p-5 rounded-2xl border border-line">
          <h3 class="font-medium mb-3">工单列表</h3>
          <div v-if="!tickets.length" class="text-sm opacity-50">暂无数据</div>
          <div v-for="t in tickets" :key="t.id" class="py-3 border-b border-line text-sm">
            <div class="font-medium">{{ t.ticketNo }} {{ t.title }}</div>
            <div class="opacity-70 mt-0.5">{{ zh(TKT_CAT, t.category) }} · {{ zh(TKT_PRI, t.priority) }} · SLA {{ dt(t.slaDeadline) }}</div>
            <div class="mt-1"><span class="px-2 py-0.5 rounded text-xs bg-gray-100">{{ zh(TKT_STATUS, t.status) }}</span></div>
            <div class="mt-2 flex gap-1 flex-wrap">
              <button v-if="t.status === 'triage'" class="text-xs px-3 py-1 rounded border border-line" @click="transition(t.id, 'assigned')">接单</button>
              <button v-if="t.status === 'assigned'" class="text-xs px-3 py-1 rounded border border-line" @click="transition(t.id, 'in_progress')">开始处理</button>
              <button v-if="t.status === 'in_progress'" class="text-xs px-3 py-1 rounded border border-line" @click="transition(t.id, 'resolved', '已处理')">标记解决</button>
              <button v-if="t.status === 'resolved'" class="text-xs px-3 py-1 rounded border border-line" @click="transition(t.id, 'closed')">关闭</button>
              <button class="text-xs px-3 py-1 rounded border border-line" @click="aiAssist(t)">AI 副驾</button>
            </div>
          </div>
        </div>
      </section>
      <section v-else-if="tab === 'approvals'" class="p-5 rounded-2xl border border-line">
        <h3 class="font-medium mb-3">我的审批待办</h3>
        <div v-if="!approvals.length" class="text-sm opacity-50">暂无待办</div>
        <div v-for="a in approvals" :key="a.id" class="py-3 border-b border-line flex items-center justify-between gap-3">
          <div class="text-sm min-w-0">
            <div class="font-medium truncate">{{ a.summary || ('审批实例 #' + a.instanceId) }}</div>
            <div class="opacity-70 mt-0.5">第 {{ a.step }} 步 · {{ a.bizType || '单据' }} #{{ a.bizId || '-' }}</div>
          </div>
          <div class="flex gap-2 flex-wrap shrink-0">
            <button class="px-3 py-1.5 rounded bg-brand text-white text-sm" @click="agreeApproval(a.instanceId)">同意</button>
            <button class="px-3 py-1.5 rounded border border-line text-sm" @click="rejectApproval(a.instanceId)">驳回</button>
            <button class="px-3 py-1.5 rounded border border-line text-sm" @click="openPick(a.instanceId, 'transfer')">转办</button>
            <button class="px-3 py-1.5 rounded border border-line text-sm" @click="openPick(a.instanceId, 'addSign')">加签</button>
          </div>
        </div>
        <div v-if="pickMode" class="mt-4 p-4 rounded border border-brand/40 space-y-3">
          <div class="font-medium text-sm">{{ pickMode === 'transfer' ? '转办给' : '加签' }}：</div>
          <select v-model="pickUserId" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent text-sm">
            <option :value="null" disabled>选择人员…</option>
            <option v-for="u in pickUsers" :key="u.id" :value="u.id">
              {{ u.realName }}（{{ u.username }}）· {{ (u.roles || []).join('/') }}
            </option>
          </select>
          <div class="flex gap-2">
            <button class="px-3 py-1.5 rounded bg-brand text-white text-sm" @click="confirmPick">确认</button>
            <button class="px-3 py-1.5 rounded border border-line text-sm" @click="cancelPick">取消</button>
          </div>
        </div>
      </section>

      <section v-else-if="tab === 'rooms'" class="grid md:grid-cols-2 gap-6">
        <div class="p-5 rounded-2xl border border-line space-y-3">
          <h3 class="font-medium">预订会议室</h3>
          <select v-model.number="bookForm.roomId" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent">
            <option v-for="r in rooms" :key="r.id" :value="r.id">{{ r.code }} {{ r.name }}（{{ r.capacity }}人）</option>
          </select>
          <input v-model="bookForm.title" placeholder="会议主题" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent" />
          <div class="grid grid-cols-2 gap-2">
            <input v-model="bookForm.startAt" type="datetime-local" class="px-3 py-2 rounded-lg border border-line bg-transparent" />
            <input v-model="bookForm.endAt" type="datetime-local" class="px-3 py-2 rounded-lg border border-line bg-transparent" />
          </div>
          <div v-if="roomConflict" class="text-xs text-red-600">{{ roomConflict }}</div>
          <div v-else-if="bookForm.startAt && bookForm.endAt" class="text-xs opacity-60">时段可用（按当日占用实时校验）</div>
          <button class="w-full py-2 rounded-lg bg-brand text-white" @click="bookRoom">预订</button>
        </div>
        <div class="p-5 rounded-2xl border border-line">
          <h3 class="font-medium mb-3">我的预订</h3>
          <div v-if="dayBookings.length" class="mb-3 text-xs opacity-70 space-y-1">
            <div class="font-medium opacity-100">今日占用</div>
            <div v-for="b in dayBookings.slice(0, 6)" :key="b.id">
              房#{{ b.roomId }} · {{ dt(b.startAt) }} ~ {{ dt(b.endAt) }}
            </div>
          </div>
          <div v-if="!bookings.length" class="text-sm opacity-50">暂无数据</div>
          <div v-for="b in bookings" :key="b.id" class="py-3 border-b border-line text-sm">
            <div class="font-medium">{{ b.bookingNo }} · 房间 #{{ b.roomId }}</div>
            <div class="opacity-70 mt-0.5">{{ dt(b.startAt) }} ~ {{ dt(b.endAt) }}</div>
            <div class="mt-1"><span class="px-2 py-0.5 rounded text-xs bg-gray-100">{{ zh(ROOM_STATUS, b.status) }}</span></div>
            <div v-if="b.status === 'booked'" class="mt-2 flex gap-2">
              <button class="text-xs px-3 py-1 rounded bg-brand text-white" @click="checkIn(b.id)">签到</button>
              <button class="text-xs px-3 py-1 rounded border border-line" @click="cancelBook(b.id)">取消</button>
            </div>
          </div>
        </div>
      </section>
      <section v-else-if="tab === 'assets'" class="p-5 rounded-2xl border border-line">
        <div class="flex items-center justify-between mb-3">
          <h3 class="font-medium">资产台账</h3>
          <div class="flex gap-1 text-xs">
            <button class="px-2 py-1 rounded border border-line" @click="download('/assets/export', 'assets.xlsx')">导出</button>
            <label class="px-2 py-1 rounded border border-line cursor-pointer">
              导入
              <input type="file" accept=".xlsx" class="hidden" @change="(e) => importFile('/assets/import', e.target)" />
            </label>
          </div>
        </div>
        <div v-if="!assets.length" class="text-sm opacity-50">暂无数据</div>
        <div v-for="a in assets" :key="a.id" class="py-3 border-b border-line text-sm flex items-center justify-between gap-2">
          <div>
            <div class="font-medium">{{ a.sn }} · {{ a.name }}</div>
            <div class="opacity-70 mt-0.5">{{ zh(ASSET_STATUS, a.status) }} · 持有人 {{ a.holderId || '无' }}</div>
          </div>
          <div class="flex gap-1">
            <button v-if="a.status === 'stock'" class="text-xs px-3 py-1 rounded border border-line" @click="applyAsset(a.id)">领用</button>
            <button v-if="a.status === 'assigned'" class="text-xs px-3 py-1 rounded border border-line" @click="returnAsset(a.id)">归还</button>
          </div>
        </div>
      </section>

      <section v-else-if="tab === 'purchases'" class="grid md:grid-cols-2 gap-6">
        <div class="p-5 rounded-2xl border border-line space-y-3">
          <h3 class="font-medium">采购需求</h3>
          <input v-model="poForm.title" placeholder="需求标题" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent" />
          <div class="grid grid-cols-2 gap-2">
            <input v-model.number="poForm.qty" type="number" min="1" placeholder="数量" class="px-3 py-2 rounded-lg border border-line bg-transparent" />
            <input v-model.number="poForm.budget" type="number" step="0.01" placeholder="预算（建议填写）" class="px-3 py-2 rounded-lg border border-line bg-transparent" />
          </div>
          <textarea v-model="poForm.reason" rows="2" placeholder="用途" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent"></textarea>
          <button class="w-full py-2 rounded-lg bg-brand text-white" @click="submitPo">提报需求</button>
        </div>
        <div class="p-5 rounded-2xl border border-line">
          <h3 class="font-medium mb-3">采购列表</h3>
          <div v-if="!purchases.length" class="text-sm opacity-50">暂无数据</div>
          <div v-for="p in purchases" :key="p.id" class="py-3 border-b border-line text-sm">
            <div class="font-medium">{{ p.demandNo }} {{ p.title }}</div>
            <div class="opacity-70 mt-0.5">数量 {{ p.qty }} · 预算 {{ p.budget || '—' }}</div>
            <div class="mt-1"><span class="px-2 py-0.5 rounded text-xs bg-gray-100">{{ zh(PO_STATUS, p.status) }}</span></div>
            <div class="mt-2 flex gap-1 flex-wrap">
              <button v-if="p.status === 'demand' || (p.status === 'quoted' && !p.approvalInstanceId)" class="text-xs px-2 py-1 rounded border border-line" @click="openQuotes(p)">录比价(≥2)</button>
              <button v-if="p.status === 'quoted' && !p.approvalInstanceId" class="text-xs px-2 py-1 rounded border border-line" @click="poAction(p.id, 'submit')">提交审批</button>
              <button v-if="p.status === 'quoted' && p.approvalInstanceId" class="text-xs px-2 py-1 rounded bg-brand text-white" @click="poAction(p.id, 'approve', { agree: true, comment: '同意' })">同意</button>
              <button v-if="p.status === 'approved'" class="text-xs px-2 py-1 rounded border border-line" @click="poAction(p.id, 'ordered')">下单</button>
              <button v-if="p.status === 'ordered'" class="text-xs px-2 py-1 rounded border border-line" @click="poAction(p.id, 'received')">到货</button>
              <button v-if="p.status === 'received'" class="text-xs px-2 py-1 rounded border border-line" @click="poAction(p.id, 'accepted')">验收</button>
            </div>
          </div>
          <div v-if="quoteTargetId" class="mt-3 p-3 rounded border border-brand/30 space-y-2 text-sm">
            <div class="font-medium">录入比价（至少 2 家）</div>
            <div class="grid grid-cols-2 gap-2">
              <input v-model="quoteVendorA" placeholder="供应商 A" class="px-2 py-1.5 rounded border border-line bg-transparent" />
              <input v-model.number="quotePriceA" type="number" placeholder="价格 A" class="px-2 py-1.5 rounded border border-line bg-transparent" />
              <input v-model="quoteVendorB" placeholder="供应商 B" class="px-2 py-1.5 rounded border border-line bg-transparent" />
              <input v-model.number="quotePriceB" type="number" placeholder="价格 B" class="px-2 py-1.5 rounded border border-line bg-transparent" />
            </div>
            <div class="flex gap-2">
              <button class="px-3 py-1 rounded bg-brand text-white text-xs" @click="submitQuotes">保存比价</button>
              <button class="px-3 py-1 rounded border border-line text-xs" @click="quoteTargetId = null">取消</button>
            </div>
          </div>
        </div>
      </section>

      <section v-else-if="tab === 'offboard'" class="p-5 rounded-2xl border border-line">
        <div class="flex items-center justify-between mb-3">
          <h3 class="font-medium">离职清单（五门槛）</h3>
          <div class="flex gap-2">
            <input v-model="offboardUid" type="number" placeholder="用户 ID" class="px-2 py-1 rounded border border-line bg-transparent text-xs w-28" />
            <button class="text-xs px-3 py-1 rounded bg-brand text-white" @click="openOffboard">开离职单</button>
          </div>
        </div>
        <div v-if="!offboards.length" class="text-sm opacity-50">暂无离职单</div>
        <div v-for="c in offboards" :key="c.id" class="py-4 border-b border-line text-sm">
          <div class="font-medium">单 #{{ c.id }} · 用户 {{ c.userId }} · <span class="opacity-70">{{ c.status === 'done' ? '已完成' : '进行中' }}</span></div>
          <div v-if="c.status === 'open'" class="mt-2 grid sm:grid-cols-2 gap-2">
            <button class="px-3 py-1.5 rounded border border-line text-left" :class="c.assetsReturned ? 'opacity-50 line-through' : ''" @click="tickOff(c, 'assets')">1. 资产清零 {{ c.assetsReturned ? '✓' : '' }}</button>
            <button class="px-3 py-1.5 rounded border border-line text-left" :class="c.accountsDisabled ? 'opacity-50 line-through' : ''" @click="tickOff(c, 'accounts')">2. 账号禁用 {{ c.accountsDisabled ? '✓' : '' }}</button>
            <button class="px-3 py-1.5 rounded border border-line text-left" :class="c.permissionsRevoked ? 'opacity-50 line-through' : ''" @click="tickOff(c, 'permissions')">3. 权限回收 {{ c.permissionsRevoked ? '✓' : '' }}</button>
            <button class="px-3 py-1.5 rounded border border-line text-left" :class="c.badgeReturned ? 'opacity-50 line-through' : ''" @click="tickOff(c, 'badge')">4. 门禁卡归还 {{ c.badgeReturned ? '✓' : '' }}</button>
            <button class="px-3 py-1.5 rounded border border-line text-left" :class="c.loanCleared ? 'opacity-50 line-through' : ''" @click="tickOff(c, 'loan')">5. 借款核销 {{ c.loanCleared ? '✓' : '' }}</button>
            <div class="flex gap-2 sm:col-span-2 mt-1">
              <button class="px-3 py-1.5 rounded bg-brand text-white" @click="completeOff(c)">完成离职</button>
              <button class="px-3 py-1.5 rounded border border-line" @click="forceOff(c)">一键勾软项</button>
            </div>
          </div>
        </div>
      </section>

      <section v-else-if="tab === 'notices'" class="p-5 rounded-2xl border border-line">
        <div class="flex flex-wrap justify-between items-center gap-2 mb-3">
          <h3 class="font-medium">站内通知<span v-if="unread" class="ml-2 text-xs px-2 py-0.5 rounded-full bg-brand text-white">{{ unread }}</span></h3>
          <div class="flex flex-wrap gap-2 items-center text-xs">
            <button class="px-2 py-1 rounded border" :class="!noticeUnread && !noticeType ? 'bg-brand text-white border-brand' : 'border-line'"
              @click="noticeUnread=false; noticeType=''; loadTab()">全部</button>
            <button class="px-2 py-1 rounded border" :class="noticeUnread ? 'bg-brand text-white border-brand' : 'border-line'"
              @click="noticeUnread=!noticeUnread; loadTab()">未读</button>
            <select v-model="noticeType" class="px-2 py-1 rounded border border-line bg-transparent" @change="loadTab()">
              <option value="">类型</option>
              <option value="approval">审批</option>
              <option value="ticket">工单</option>
              <option value="sla_breach">SLA</option>
              <option value="system">系统</option>
            </select>
            <button class="px-2 py-1 rounded border border-line" @click="readAll">全部已读</button>
          </div>
        </div>
        <div v-if="!notices.length" class="text-sm opacity-50">暂无通知</div>
        <div v-for="n in notices" :key="n.id" class="py-3 border-b border-line text-sm" :class="{ 'opacity-50': n.readFlag }">
          <div class="font-medium">[{{ n.type }}] {{ n.title }}</div>
          <div class="opacity-70 mt-0.5">{{ n.body }} · {{ dt(n.createdAt) }}</div>
        </div>
      </section>
    </div>
  </div>
</template>
