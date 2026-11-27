<script setup lang="ts">
import { onBeforeUnmount, ref, useTemplateRef, watch } from 'vue'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import {
  createAdminInvite,
  fetchAdminInvites,
  inviteStatusLabels,
  revokeAdminInvite,
  type AdminInviteRecord,
  type CreatedAdminInvite,
  type InviteStatus,
} from '../api/adminInvitesApi'
import AdminHeader from './AdminHeader.vue'
import AdminSidebar from './AdminSidebar.vue'

defineProps<{ username: string; loggingOut: boolean; sessionError: string | null }>()
defineEmits<{ logout: [] }>()

const records = ref<AdminInviteRecord[]>([])
const status = ref<InviteStatus | ''>('')
const page = ref(1)
const hasNext = ref(false)
const loading = ref(false)
const loadError = ref('')
const modal = useTemplateRef<globalThis.HTMLDialogElement>('modal')
const validDays = ref<number | ''>(7)
const created = ref<CreatedAdminInvite>()
const selected = ref<AdminInviteRecord>()
const actionError = ref('')
const saving = ref(false)
const copied = ref(false)
const notice = ref('')
let read: globalThis.AbortController | undefined
let action: globalThis.AbortController | undefined
const dateFormat = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
})

function time(value: string) {
  return dateFormat.format(new Date(value))
}

async function load(next = page.value) {
  read?.abort()
  const token = authToken.value
  if (!token) return
  const request = new globalThis.AbortController()
  read = request
  const current = () => !request.signal.aborted && token === authToken.value
  page.value = next
  loading.value = true
  loadError.value = ''
  try {
    const result = await fetchAdminInvites(next, status.value, token, request.signal)
    if (!current()) return
    if (!result.records.length && next > 1) {
      void load(next - 1)
      return
    }
    records.value = result.records
    hasNext.value = result.hasNext
  } catch (error) {
    if (current()) loadError.value = toErrorMessage(error)
  } finally {
    if (current()) loading.value = false
  }
}

function clearModal() {
  created.value = undefined
  selected.value = undefined
  copied.value = false
  actionError.value = ''
}

function afterClose() {
  if (!modal.value?.open) clearModal()
}

function openCreate() {
  clearModal()
  validDays.value = 7
  modal.value?.showModal()
}

function openRevoke(record: AdminInviteRecord) {
  clearModal()
  selected.value = record
  modal.value?.showModal()
}

async function submit() {
  const token = authToken.value
  if (!token || saving.value || created.value) return
  const days = Number(validDays.value)
  if (!selected.value && (!Number.isInteger(days) || days < 1 || days > 30)) {
    actionError.value = '有效期请输入 1–30 天的整数。'
    return
  }
  const request = new globalThis.AbortController()
  action = request
  const current = () => !request.signal.aborted && token === authToken.value
  saving.value = true
  actionError.value = ''
  notice.value = ''
  try {
    if (selected.value) {
      await revokeAdminInvite(selected.value.id, token, request.signal)
      if (!current()) return
      notice.value = '邀请码已撤销。'
      modal.value?.close()
      void load()
    } else {
      const response = await createAdminInvite(days, token, request.signal)
      if (!current()) return
      created.value = response
      status.value = ''
      void load(1)
    }
  } catch (error) {
    if (current()) {
      actionError.value = toErrorMessage(error)
      if (selected.value) void load()
    }
  } finally {
    if (current()) saving.value = false
  }
}

async function copyCode() {
  const value = created.value
  if (!value) return
  actionError.value = ''
  try {
    await globalThis.navigator.clipboard.writeText(value.code)
    if (created.value === value) copied.value = true
  } catch {
    if (created.value === value) actionError.value = '无法自动复制，请选中邀请码手动复制。'
  }
}

watch(
  authToken,
  () => {
    read?.abort()
    action?.abort()
    modal.value?.close()
    clearModal()
    saving.value = false
    loading.value = false
    records.value = []
    hasNext.value = false
    notice.value = ''
    void load(1)
  },
  { immediate: true, flush: 'sync' },
)

onBeforeUnmount(() => {
  read?.abort()
  action?.abort()
  clearModal()
})
</script>

<template>
  <div class="invite-workspace admin-theme">
    <AdminHeader :username="username" :logging-out="loggingOut" @logout="$emit('logout')" />
    <div class="invite-shell">
      <AdminSidebar />
      <main aria-labelledby="admin-invite-title">
        <div class="invite-heading">
          <h1 id="admin-invite-title">校友邀请</h1>
          <button class="primary" type="button" @click="openCreate">生成邀请码</button>
        </div>
        <p v-if="sessionError" role="alert">{{ sessionError }}</p>
        <p v-if="notice" class="invite-notice" role="status">{{ notice }}</p>
        <div class="invite-filter">
          <label for="invite-status">状态</label>
          <select id="invite-status" v-model="status" @change="load(1)">
            <option value="">全部状态</option>
            <option v-for="(label, key) in inviteStatusLabels" :key="key" :value="key">
              {{ label }}
            </option>
          </select>
        </div>
        <section class="invite-records" aria-label="邀请记录" :aria-busy="loading">
          <p v-if="loading" class="invite-feedback" role="status">正在加载邀请记录…</p>
          <div v-else-if="loadError" class="invite-feedback" role="alert">
            <p>{{ loadError }}</p>
            <button type="button" @click="load()">重试</button>
          </div>
          <p v-else-if="!records.length" class="invite-feedback">暂无邀请记录</p>
          <div v-else class="invite-table-scroll" tabindex="0" aria-label="邀请记录表格">
            <table>
              <thead>
                <tr>
                  <th scope="col">记录编号</th>
                  <th scope="col">状态</th>
                  <th scope="col">注册账号</th>
                  <th scope="col">创建时间</th>
                  <th scope="col">有效期至</th>
                  <th scope="col">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="record in records" :key="record.id">
                  <th scope="row">#{{ record.id }}</th>
                  <td>
                    <span class="invite-status" :data-status="record.status">{{
                      inviteStatusLabels[record.status]
                    }}</span>
                  </td>
                  <td>
                    {{ record.registeredUsername ?? '—' }}
                    <small v-if="record.boundEmail" class="invite-legacy-email"
                      >绑定 {{ record.boundEmail }}</small
                    >
                  </td>
                  <td>
                    <time :datetime="record.createdAt">{{ time(record.createdAt) }}</time>
                  </td>
                  <td>
                    <time :datetime="record.expiresAt">{{ time(record.expiresAt) }}</time>
                  </td>
                  <td>
                    <button
                      v-if="record.status === 'AVAILABLE'"
                      class="invite-revoke"
                      type="button"
                      :aria-label="`撤销记录 ${record.id} 的邀请码`"
                      @click="openRevoke(record)"
                    >
                      撤销
                    </button>
                    <span v-else>—</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
        <nav v-if="page > 1 || hasNext" class="invite-pages" aria-label="邀请记录分页">
          <button type="button" :disabled="page === 1 || loading" @click="load(page - 1)">
            上一页
          </button>
          <span>第 {{ page }} 页</span>
          <button type="button" :disabled="!hasNext || loading" @click="load(page + 1)">
            下一页
          </button>
        </nav>
      </main>
    </div>

    <dialog
      ref="modal"
      class="invite-dialog"
      aria-labelledby="invite-dialog-title"
      @close="afterClose"
      @cancel="saving && $event.preventDefault()"
    >
      <form novalidate @submit.prevent="submit">
        <div class="invite-dialog-heading">
          <h2 id="invite-dialog-title">
            {{ selected ? '撤销邀请码' : created ? '邀请码已生成' : '生成邀请码' }}
          </h2>
          <button
            class="invite-dialog-close"
            type="button"
            aria-label="关闭弹窗"
            :disabled="saving"
            @click="modal?.close()"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="m6 6 12 12M18 6 6 18" />
            </svg>
          </button>
        </div>
        <template v-if="created">
          <p>邀请码原文仅显示一次，请复制保存。</p>
          <div class="created-code">
            <code>{{ created.code }}</code>
            <button class="primary" type="button" @click="copyCode">
              {{ copied ? '已复制' : '复制邀请码' }}
            </button>
          </div>
          <span class="copy-status" role="status">{{ copied ? '邀请码已复制' : '' }}</span>
          <p class="invite-expiry">
            有效期至 <time :datetime="created.expiresAt">{{ time(created.expiresAt) }}</time>
          </p>
        </template>
        <p v-else-if="selected">撤销后，校友将无法使用这张邀请码注册。</p>
        <label v-else class="invite-days" for="invite-days">
          <span>有效期（天）</span>
          <input
            id="invite-days"
            v-model.number="validDays"
            type="number"
            min="1"
            max="30"
            step="1"
            required
            :disabled="saving"
          />
        </label>
        <p v-if="actionError" class="invite-error" role="alert">{{ actionError }}</p>
        <footer>
          <button type="button" :disabled="saving" @click="modal?.close()">
            {{ created ? '关闭' : '取消' }}
          </button>
          <button v-if="!created" class="primary" type="submit" :disabled="saving">
            {{ saving ? '正在处理…' : selected ? '确认撤销' : '生成邀请码' }}
          </button>
        </footer>
      </form>
    </dialog>
  </div>
</template>

<style scoped src="./admin-invites.css"></style>
