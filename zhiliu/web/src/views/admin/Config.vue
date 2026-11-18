
<script setup>
import { ref, onMounted } from "vue"
import { javaApi } from "../../api/java"
import { toast } from "../../composables/useToast"

const tab = ref("leave-types")
const tabs = [
  { id: "leave-types", label: "假种" },
  { id: "sla", label: "SLA" },
  { id: "templates", label: "审批模板" },
  { id: "depts", label: "部门" },
  { id: "policies", label: "报销标准" },
  { id: "budgets", label: "预算" },
  { id: "roles", label: "角色" },
  { id: "rooms", label: "会议室" },
  { id: "categories", label: "资产分类" },
]

const leaveTypes = ref([])
const slas = ref([])
const templates = ref([])
const depts = ref([])
const policies = ref([])
const budgets = ref([])
const roles = ref([])
const rooms = ref([])
const categories = ref([])

const err = (e) => toast.error(e.message || "操作失败")
const ok = (m) => toast.success(m)

const load = async () => {
  try {
    const [lt, sl, tp, dp, po, bd, ro, roomsRes, catRes] = await Promise.all([
      javaApi.get("/config/leave-types"),
      javaApi.get("/config/sla-policies"),
      javaApi.get("/config/approval-templates"),
      javaApi.get("/config/depts"),
      javaApi.get("/config/expense-policies"),
      javaApi.get("/config/budgets"),
      javaApi.get("/config/roles"),
      javaApi.get("/config/meeting-rooms"),
      javaApi.get("/config/asset-categories"),
    ])
    leaveTypes.value = lt.data.data || []
    slas.value = sl.data.data || []
    templates.value = tp.data.data || []
    depts.value = dp.data.data || []
    policies.value = po.data.data || []
    budgets.value = bd.data.data || []
    roles.value = ro.data.data || []
    rooms.value = roomsRes.data.data || []
    categories.value = catRes.data.data || []
  } catch (e) { err(e) }
}
onMounted(load)

const save = async (path, body) => {
  try { await javaApi.post("/config/" + path, body); ok("已保存"); load() } catch (e) { err(e) }
}

// 页内编辑面板（替代 prompt/confirm）
const editKind = ref(null)
const editRow = ref({})
const openEdit = (kind, row) => {
  editKind.value = kind
  editRow.value = row ? JSON.parse(JSON.stringify(row)) : {}
  if (kind === "room" && !editRow.value.code) {
    editRow.value = { code: "", name: "", capacity: 8, equip: "", location: "", status: "active" }
  }
  if (kind === "category" && !editRow.value.code) {
    editRow.value = { code: "", name: "", depreciateYears: 3, warrantyDefault: 36, status: "active" }
  }
  if (kind === "template" && !editRow.value.stepsJson) {
    editRow.value = {
      bizType: "expense",
      stepsJson: '[{"step":1,"role":"manager","mode":"serial"},{"step":2,"role":"finance","mode":"serial"}]',
      status: "active",
      rejectPolicy: "to_start",
    }
  }
}
const closeEdit = () => { editKind.value = null; editRow.value = {} }
const saveEdit = async () => {
  const kind = editKind.value
  const row = editRow.value
  if (kind === "leave") {
    row.needBalance = row.needBalance ? 1 : 0
    row.status = row.status || "active"
    await save("leave-types", row)
  } else if (kind === "sla") {
    row.resolveMin = Number(row.resolveMin)
    await save("sla-policies", row)
  } else if (kind === "policy") {
    row.limitPerDay = row.limitPerDay === "" || row.limitPerDay == null ? null : Number(row.limitPerDay)
    row.status = "active"
    await save("expense-policies", row)
  } else if (kind === "budget") {
    row.amount = Number(row.amount)
    await save("budgets", row)
  } else if (kind === "template") {
    await save("approval-templates", row)
  } else if (kind === "room") {
    row.capacity = Number(row.capacity)
    row.status = row.status || "active"
    await save("meeting-rooms", row)
  } else if (kind === "category") {
    row.depreciateYears = Number(row.depreciateYears || 3)
    row.warrantyDefault = Number(row.warrantyDefault || 36)
    row.status = row.status || "active"
    await save("asset-categories", row)
  }
  closeEdit()
}
</script>

<template>
  <div class="space-y-4">
    <div class="flex items-center justify-between">
      <div>
        <h1 class="text-xl font-semibold text-ink">配置中心</h1>
        <p class="text-sm text-ink-faint">改配置不改代码 · 新单生效，旧单锁提交时快照</p>
      </div>
      <button class="px-3 py-1.5 rounded-lg border border-line text-sm" @click="load">刷新</button>
    </div>

    <nav class="flex flex-wrap gap-2">
      <button
        v-for="t in tabs"
        :key="t.id"
        class="px-3 py-1.5 rounded-full text-sm border transition"
        :class="tab === t.id ? 'bg-brand text-white border-brand' : 'border-line hover:bg-body'"
        @click="tab = t.id"
      >{{ t.label }}</button>
    </nav>

    <div class="rounded-card bg-surface border border-line overflow-hidden">
      <!-- 假种 -->
      <table v-if="tab === 'leave-types'" class="w-full text-sm">
        <thead class="bg-body text-ink-faint text-left">
          <tr><th class="p-3">编码</th><th class="p-3">名称</th><th class="p-3">扣余额</th><th class="p-3">状态</th><th class="p-3">操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="r in leaveTypes" :key="r.id" class="border-t border-line">
            <td class="p-3 font-mono text-xs text-ink-faint">{{ r.code }}</td>
            <td class="p-3">{{ r.name }}</td>
            <td class="p-3">{{ r.needBalance ? "是" : "否" }}</td>
            <td class="p-3">{{ r.status }}</td>
            <td class="p-3"><button class="text-brand text-xs" @click="openEdit('leave', r)">改余额规则</button></td>
          </tr>
        </tbody>
      </table>

      <!-- SLA -->
      <table v-else-if="tab === 'sla'" class="w-full text-sm">
        <thead class="bg-body text-ink-faint text-left">
          <tr><th class="p-3">优先级</th><th class="p-3">首响(分)</th><th class="p-3">解决(分)</th><th class="p-3">操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="r in slas" :key="r.id" class="border-t border-line">
            <td class="p-3 font-mono text-xs text-ink-faint">{{ r.priority }}</td>
            <td class="p-3">{{ r.firstRespMin }}</td>
            <td class="p-3">{{ r.resolveMin }}</td>
            <td class="p-3"><button class="text-brand text-xs" @click="openEdit('sla', r)">改解决时限</button></td>
          </tr>
        </tbody>
      </table>

      <!-- 审批模板 -->
      <div v-else-if="tab === 'templates'" class="p-4 space-y-3">
        <button class="px-3 py-1.5 rounded bg-brand text-white text-sm" @click="openEdit('template', null)">+ 新建模板（新版本）</button>
        <div v-for="r in templates" :key="r.id" class="p-3 rounded border border-line text-sm">
          <div class="font-medium">{{ r.bizType }} v{{ r.version }} · {{ r.status }}</div>
          <pre class="mt-1 text-xs opacity-70 overflow-x-auto">{{ r.stepsJson }}</pre>
        </div>
      </div>

      <!-- 部门 -->
      <table v-else-if="tab === 'depts'" class="w-full text-sm">
        <thead class="bg-body text-ink-faint text-left">
          <tr><th class="p-3">ID</th><th class="p-3">名称</th><th class="p-3">成本中心</th></tr>
        </thead>
        <tbody>
          <tr v-for="r in depts" :key="r.id" class="border-t border-line">
            <td class="p-3">{{ r.id }}</td>
            <td class="p-3">{{ r.name }}</td>
            <td class="p-3">{{ r.costCenter || "—" }}</td>
          </tr>
        </tbody>
      </table>

      <!-- 报销标准 -->
      <table v-else-if="tab === 'policies'" class="w-full text-sm">
        <thead class="bg-body text-ink-faint text-left">
          <tr><th class="p-3">类型</th><th class="p-3">日限额</th><th class="p-3">须发票</th><th class="p-3">操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="r in policies" :key="r.id" class="border-t border-line">
            <td class="p-3 font-mono text-xs text-ink-faint">{{ r.type }}</td>
            <td class="p-3">{{ r.limitPerDay ?? "不限" }}</td>
            <td class="p-3">{{ r.needInvoice ? "是" : "否" }}</td>
            <td class="p-3"><button class="text-brand text-xs" @click="openEdit('policy', r)">编辑</button></td>
          </tr>
        </tbody>
      </table>

      <!-- 预算 -->
      <table v-else-if="tab === 'budgets'" class="w-full text-sm">
        <thead class="bg-body text-ink-faint text-left">
          <tr><th class="p-3">部门</th><th class="p-3">期间</th><th class="p-3">额度</th><th class="p-3">已用</th><th class="p-3">操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="r in budgets" :key="r.id" class="border-t border-line">
            <td class="p-3">{{ r.deptId }}</td>
            <td class="p-3">{{ r.period }}</td>
            <td class="p-3">{{ r.amount }}</td>
            <td class="p-3">{{ r.used }}</td>
            <td class="p-3"><button class="text-brand text-xs" @click="openEdit('budget', r)">改额度</button></td>
          </tr>
        </tbody>
      </table>

      <!-- 会议室 -->
      <div v-else-if="tab === 'rooms'" class="p-4 space-y-3">
        <button class="px-3 py-1.5 rounded bg-brand text-white text-sm" @click="openEdit('room', null)">+ 新建会议室</button>
        <table class="w-full text-sm">
          <thead class="bg-body text-ink-faint text-left">
            <tr><th class="p-3">编码</th><th class="p-3">名称</th><th class="p-3">容量</th><th class="p-3">位置</th><th class="p-3">状态</th><th class="p-3">操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="r in rooms" :key="r.id" class="border-t border-line">
              <td class="p-3 font-mono text-xs text-ink-faint">{{ r.code }}</td>
              <td class="p-3">{{ r.name }}</td>
              <td class="p-3">{{ r.capacity }}</td>
              <td class="p-3">{{ r.location || '—' }}</td>
              <td class="p-3">{{ r.status }}</td>
              <td class="p-3"><button class="text-brand text-xs" @click="openEdit('room', r)">编辑</button></td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 资产分类 -->
      <div v-else-if="tab === 'categories'" class="p-4 space-y-3">
        <button class="px-3 py-1.5 rounded bg-brand text-white text-sm" @click="openEdit('category', null)">+ 新建分类</button>
        <table class="w-full text-sm">
          <thead class="bg-body text-ink-faint text-left">
            <tr><th class="p-3">编码</th><th class="p-3">名称</th><th class="p-3">折旧(年)</th><th class="p-3">默认保修(月)</th><th class="p-3">状态</th><th class="p-3">操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="r in categories" :key="r.id" class="border-t border-line">
              <td class="p-3 font-mono text-xs text-ink-faint">{{ r.code }}</td>
              <td class="p-3">{{ r.name }}</td>
              <td class="p-3">{{ r.depreciateYears }}</td>
              <td class="p-3">{{ r.warrantyDefault }}</td>
              <td class="p-3">{{ r.status }}</td>
              <td class="p-3"><button class="text-brand text-xs" @click="openEdit('category', r)">编辑</button></td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 角色 -->
      <table v-else class="w-full text-sm">
        <thead class="bg-body text-ink-faint text-left">
          <tr><th class="p-3">编码</th><th class="p-3">名称</th><th class="p-3">说明</th></tr>
        </thead>
        <tbody>
          <tr v-for="r in roles" :key="r.id" class="border-t border-line">
            <td class="p-3 font-mono">{{ r.roleCode }}</td>
            <td class="p-3">{{ r.name }}</td>
            <td class="p-3 opacity-70">{{ r.description || "—" }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 页内编辑 -->
    <div v-if="editKind" class="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4" @click.self="closeEdit">
      <div class="w-full max-w-lg rounded-xl bg-surface border border-line p-5 space-y-3">
        <div class="flex items-center justify-between">
          <h3 class="font-medium">编辑配置</h3>
          <button class="text-sm opacity-60" @click="closeEdit">关闭</button>
        </div>

        <div v-if="editKind === 'leave'" class="space-y-2 text-sm">
          <div class="opacity-70">{{ editRow.code }} · {{ editRow.name }}</div>
          <label class="flex items-center gap-2">
            <input v-model="editRow.needBalance" type="checkbox" :true-value="1" :false-value="0" />
            扣余额
          </label>
          <select v-model="editRow.status" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent">
            <option value="active">启用</option>
            <option value="disabled">停用</option>
          </select>
        </div>

        <div v-else-if="editKind === 'sla'" class="space-y-2 text-sm">
          <div class="opacity-70">{{ editRow.priority }} 首响 {{ editRow.firstRespMin }} 分</div>
          <label class="block">解决时限（分钟）
            <input v-model.number="editRow.resolveMin" type="number" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent" />
          </label>
        </div>

        <div v-else-if="editKind === 'policy'" class="space-y-2 text-sm">
          <div class="opacity-70">类型 {{ editRow.type }}</div>
          <label class="block">单日限额（空=不限）
            <input v-model="editRow.limitPerDay" type="number" step="0.01" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent" />
          </label>
          <label class="flex items-center gap-2">
            <input v-model="editRow.needInvoice" type="checkbox" :true-value="true" :false-value="false" />
            必须发票
          </label>
        </div>

        <div v-else-if="editKind === 'budget'" class="space-y-2 text-sm">
          <div class="opacity-70">部门 {{ editRow.deptId }} · {{ editRow.period }}</div>
          <label class="block">预算金额
            <input v-model.number="editRow.amount" type="number" step="0.01" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent" />
          </label>
        </div>

        <div v-else-if="editKind === ' + chr(39) + 'room' + chr(39) + '" class="space-y-2 text-sm">
          <label class="block">编码
            <input v-model="editRow.code" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent font-mono text-xs" placeholder="R-A / A101" />
          </label>
          <label class="block">名称
            <input v-model="editRow.name" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent" />
          </label>
          <div class="grid grid-cols-2 gap-2">
            <label class="block">容量
              <input v-model.number="editRow.capacity" type="number" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent" />
            </label>
            <label class="block">位置
              <input v-model="editRow.location" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent" />
            </label>
          </div>
          <label class="block">设备（逗号分隔）
            <input v-model="editRow.equip" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent" placeholder="投影,白板,视频会议" />
          </label>
          <select v-model="editRow.status" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent">
            <option value="active">启用</option>
            <option value="disabled">停用</option>
          </select>
        </div>

        <div v-else-if="editKind === ' + chr(39) + 'category' + chr(39) + '" class="space-y-2 text-sm">
          <label class="block">编码
            <input v-model="editRow.code" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent font-mono text-xs" placeholder="laptop" />
          </label>
          <label class="block">名称
            <input v-model="editRow.name" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent" placeholder="笔记本" />
          </label>
          <div class="grid grid-cols-2 gap-2">
            <label class="block">折旧年限
              <input v-model.number="editRow.depreciateYears" type="number" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent" />
            </label>
            <label class="block">默认保修（月）
              <input v-model.number="editRow.warrantyDefault" type="number" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent" />
            </label>
          </div>
          <select v-model="editRow.status" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent">
            <option value="active">启用</option>
            <option value="disabled">停用</option>
          </select>
        </div>

        <div v-else-if="editKind === ' + chr(39) + 'template' + chr(39) + '" class="space-y-2 text-sm">
          <label class="block">bizType
            <input v-model="editRow.bizType" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent" placeholder="expense / leave / purchase" />
          </label>
          <label class="block">steps JSON
            <textarea v-model="editRow.stepsJson" rows="5" class="w-full px-3 py-2 rounded-lg border border-line bg-transparent font-mono text-xs"></textarea>
          </label>
        </div>

        <button class="w-full py-2 rounded-lg bg-brand text-white text-sm" @click="saveEdit">保存</button>
      </div>
    </div>
  </div>
</template>
