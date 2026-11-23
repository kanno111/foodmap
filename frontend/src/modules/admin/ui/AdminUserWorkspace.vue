<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useTemplateRef, watch } from 'vue'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import UserAvatar from '@/modules/account/ui/UserAvatar.vue'
import {
  fetchAdminUsers,
  setAdminUserMuted,
  type AdminUser,
  type AdminUserPage,
} from '../api/adminUsersApi'
import AdminHeader from './AdminHeader.vue'
import AdminSidebar from './AdminSidebar.vue'

defineProps<{ username: string; loggingOut: boolean; sessionError: string | null }>()
defineEmits<{ logout: [] }>()

const input = ref('')
const keyword = ref('')
const page = ref(1)
const result = ref<AdminUserPage | null>(null)
const loading = ref(false)
const error = ref('')
let request: globalThis.AbortController | undefined
let muteRequest: globalThis.AbortController | undefined
const muteDialog = useTemplateRef('muteDialog')
const selected = ref<AdminUser | null>(null)
const updatingMute = ref(false)
const muteError = ref('')
const number = new Intl.NumberFormat('zh-CN')
const pageNumbers = computed(() => {
  const total = Math.min(result.value?.totalPages ?? 0, 1_000_000)
  const first = Math.max(1, Math.min(page.value - 1, total - 2))
  return Array.from({ length: Math.min(total, 3) }, (_, index) => first + index)
})

async function load(next = page.value) {
  const token = authToken.value
  if (!token) return
  request?.abort()
  request = new globalThis.AbortController()
  const signal = request.signal
  const current = () => !signal.aborted && token === authToken.value
  page.value = next
  loading.value = true
  result.value = null
  error.value = ''
  try {
    const data = await fetchAdminUsers(keyword.value, next, token, signal)
    if (!current()) return
    if (next > data.totalPages && next > 1) {
      void load(Math.max(1, data.totalPages))
      return
    }
    result.value = data
  } catch (reason) {
    if (current()) error.value = toErrorMessage(reason)
  } finally {
    if (current()) loading.value = false
  }
}

function search() {
  keyword.value = input.value.trim()
  void load(1)
}

async function confirmMute(user: AdminUser) {
  selected.value = user
  muteError.value = ''
  await nextTick()
  if (selected.value?.id === user.id && !muteDialog.value?.open) muteDialog.value?.showModal()
}

function closeMute() {
  muteDialog.value?.close()
  selected.value = null
  muteError.value = ''
}

async function changeMute() {
  const token = authToken.value
  const user = selected.value
  if (!token || !user || updatingMute.value) return
  const controller = new globalThis.AbortController()
  muteRequest = controller
  updatingMute.value = true
  muteError.value = ''
  const current = () => !controller.signal.aborted && authToken.value === token
  try {
    const mutedAt = await setAdminUserMuted(user.id, !user.mutedAt, token, controller.signal)
    if (!current()) return
    closeMute()
    const row = result.value?.records.find(({ id }) => id === user.id)
    if (row) row.mutedAt = mutedAt
  } catch (reason) {
    if (current()) muteError.value = toErrorMessage(reason)
  } finally {
    if (current()) updatingMute.value = false
  }
}

onMounted(() => void load())
watch(
  authToken,
  () => {
    muteRequest?.abort()
    updatingMute.value = false
    closeMute()
  },
  { flush: 'sync' },
)
onBeforeUnmount(() => {
  request?.abort()
  muteRequest?.abort()
})
</script>

<template>
  <div class="user-workspace admin-theme">
    <AdminHeader :username="username" :logging-out="loggingOut" @logout="$emit('logout')" />
    <div class="user-shell">
      <AdminSidebar />
      <main>
        <h1>用户管理</h1>
        <p v-if="sessionError" class="alert" role="alert">{{ sessionError }}</p>
        <form class="toolbar" role="search" @submit.prevent="search">
          <label class="search">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="10.5" cy="10.5" r="5.5" />
              <path d="m15 15 4 4" />
            </svg>
            <input
              v-model="input"
              type="search"
              maxlength="32"
              aria-label="用户名"
              placeholder="搜索用户名"
            />
          </label>
          <button class="primary" type="submit">查询</button>
        </form>
        <section class="results" aria-label="用户列表" :aria-busy="loading">
          <table>
            <thead>
              <tr>
                <th scope="col">用户</th>
                <th scope="col">点评数</th>
                <th scope="col">投稿审核通过数</th>
                <th scope="col" class="user-action">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="user in result?.records" :key="user.id">
                <td>
                  <div class="user">
                    <UserAvatar
                      :user-id="user.hasAvatar ? user.id : null"
                      :username="user.username"
                    />
                    <span class="user-name">
                      {{ user.username }}
                      <span v-if="user.mutedAt" class="muted-badge">已禁言</span>
                    </span>
                  </div>
                </td>
                <td>{{ number.format(user.reviewCount) }}</td>
                <td>{{ number.format(user.approvedSubmissionCount) }}</td>
                <td class="user-action">
                  <button
                    v-if="user.canModerate"
                    class="mute-action"
                    :class="{ unmute: user.mutedAt }"
                    type="button"
                    :aria-label="`${user.mutedAt ? '解禁' : '禁言'} ${user.username}`"
                    @click="confirmMute(user)"
                  >
                    {{ user.mutedAt ? '解禁' : '禁言' }}
                  </button>
                  <span v-else aria-hidden="true">—</span>
                </td>
              </tr>
            </tbody>
          </table>
          <p v-if="loading" class="list-state" role="status">正在查询用户…</p>
          <div v-else-if="error" class="list-state">
            <p class="alert" role="alert">{{ error }}</p>
            <button type="button" @click="load()">重试</button>
          </div>
          <p v-else-if="result && !result.records.length" class="list-state" role="status">
            {{ keyword ? '没有找到匹配的用户' : '暂无用户' }}
          </p>
          <div v-if="result" class="pagination">
            <span>共 {{ number.format(result.total) }} 位用户</span>
            <nav v-if="result.totalPages > 1" aria-label="用户列表分页">
              <button
                type="button"
                aria-label="上一页"
                :disabled="page <= 1"
                @click="load(page - 1)"
              >
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path d="m14 6-6 6 6 6" />
                </svg>
              </button>
              <button
                v-for="item in pageNumbers"
                :key="item"
                type="button"
                :aria-current="item === page ? 'page' : undefined"
                :disabled="item === page"
                @click="load(item)"
              >
                {{ item }}
              </button>
              <button
                type="button"
                aria-label="下一页"
                :disabled="page >= Math.min(result.totalPages, 1_000_000)"
                @click="load(page + 1)"
              >
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path d="m10 6 6 6-6 6" />
                </svg>
              </button>
            </nav>
          </div>
        </section>
      </main>
    </div>
    <dialog
      ref="muteDialog"
      class="mute-dialog"
      aria-labelledby="admin-user-mute-title"
      @cancel.prevent="!updatingMute && closeMute()"
    >
      <template v-if="selected">
        <div class="mute-dialog-body">
          <h2 id="admin-user-mute-title">
            {{
              selected.mutedAt
                ? `解除「${selected.username}」的禁言？`
                : `禁言「${selected.username}」？`
            }}
          </h2>
          <p>
            {{
              selected.mutedAt
                ? '解禁后，该用户可以继续发布点评和回复。'
                : '禁言后仍可登录和浏览，暂停发布点评与回复。'
            }}
          </p>
          <p v-if="muteError" class="alert" role="alert">{{ muteError }}</p>
        </div>
        <footer>
          <button
            type="button"
            class="cancel-mute"
            autofocus
            :disabled="updatingMute"
            @click="closeMute"
          >
            取消
          </button>
          <button
            type="button"
            class="apply-mute"
            :class="{ unmute: selected.mutedAt }"
            :disabled="updatingMute"
            @click="changeMute"
          >
            {{ updatingMute ? '正在处理…' : selected.mutedAt ? '确认解禁' : '确认禁言' }}
          </button>
        </footer>
      </template>
    </dialog>
  </div>
</template>

<style scoped src="./admin-users.css"></style>
