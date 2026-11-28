<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useTemplateRef, watch } from 'vue'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { useListRouteState } from '@/shared/lib/useListRouteState'
import AppLink from '@/shared/ui/AppLink.vue'
import UserAvatar from '@/modules/account/ui/UserAvatar.vue'
import {
  ANNOUNCEMENT_RECIPIENT_LIMIT as recipientLimit,
  manualRewardLabels,
  normalizeAnnouncementText,
  type AnnouncementRecipient,
  type ManualRewardType,
} from '@/modules/announcement'
import {
  fetchAdminUsers,
  setAdminUserMuted,
  type AdminUser,
  type AdminUserPage,
} from '../api/adminUsersApi'
import { useAdminAnnouncements } from '../model/useAdminAnnouncements'
import AdminHeader from './AdminHeader.vue'
import AdminSidebar from './AdminSidebar.vue'

defineProps<{ username: string; loggingOut: boolean; sessionError: string | null }>()
defineEmits<{ logout: [] }>()

const routeState = useListRouteState(() => true)
const keyword = ref((routeState.read('keyword') ?? '').trim().slice(0, 32))
const input = ref(keyword.value)
const page = ref(routeState.page())
const result = ref<AdminUserPage | null>(null)
const loading = ref(false)
const error = ref('')
let request: globalThis.AbortController | undefined
let muteRequest: globalThis.AbortController | undefined
const muteDialog = useTemplateRef('muteDialog')
const selected = ref<AdminUser | null>(null)
const updatingMute = ref(false)
const muteError = ref('')
const announcementDialog = useTemplateRef('announcementDialog')
const openAnnouncementButton = useTemplateRef('openAnnouncementButton')
const searchInput = useTemplateRef('searchInput')
const selectedRecipients = ref(new Map<string, AnnouncementRecipient>())
// 保留仍完整的全选批次，翻页后打开弹窗也只显示收件人数。
let bulkPages: string[][] = []
const summarizeRecipients = ref(false)
const announcement = useAdminAnnouncements({ targetedOnly: true })
const {
  title: announcementTitle,
  content: announcementContent,
  rewardType,
  rewardNote,
  recipients,
  pending: pendingAnnouncement,
  busy: sendingAnnouncement,
  validForm: validAnnouncement,
  actionError: announcementError,
  notice: announcementNotice,
} = announcement
const selectionLocked = computed(() => sendingAnnouncement.value || !!pendingAnnouncement.value)
const visibleUsers = computed(() => result.value?.records ?? [])
const selectedCount = computed(() => selectedRecipients.value.size)
const selectedOnPage = computed(
  () => visibleUsers.value.filter(({ id }) => selectedRecipients.value.has(id)).length,
)
const pageFullySelected = computed(
  () => visibleUsers.value.length > 0 && selectedOnPage.value === visibleUsers.value.length,
)
const pageSelectionExceedsLimit = computed(
  () => selectedCount.value + visibleUsers.value.length - selectedOnPage.value > recipientLimit,
)
const number = new Intl.NumberFormat('zh-CN')
const rewardCodes = {
  FIRST_REVIEW: 1,
  REGISTRATION_INVITE: 2,
  OTHER: 3,
} satisfies Record<ManualRewardType, number>
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
  void routeState.save({ keyword: keyword.value || undefined, page: next > 1 ? next : undefined })
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

function clearSelection() {
  selectedRecipients.value.clear()
  bulkPages = []
}

function selectUsers(users: AdminUser[], checked: boolean) {
  if (selectionLocked.value) return
  if (checked) {
    const toAdd = users.filter(({ id }) => !selectedRecipients.value.has(id))
    if (selectedCount.value + toAdd.length > recipientLimit) return
    for (const { id, username } of toAdd) selectedRecipients.value.set(id, { id, username })
  } else {
    for (const { id } of users) selectedRecipients.value.delete(id)
    bulkPages = bulkPages.filter((ids) => ids.every((id) => selectedRecipients.value.has(id)))
  }
}

function selectPage(checked: boolean) {
  selectUsers(visibleUsers.value, checked)
  if (checked && pageFullySelected.value) bulkPages.push(visibleUsers.value.map(({ id }) => id))
}

function openAnnouncement() {
  if (!selectedCount.value || announcementDialog.value?.open) return
  if (!pendingAnnouncement.value) {
    recipients.value = [...selectedRecipients.value.values()]
    summarizeRecipients.value =
      bulkPages.length > 0 || (visibleUsers.value.length > 1 && pageFullySelected.value)
  }
  announcementNotice.value = ''
  announcementDialog.value?.showModal()
}

function closeAnnouncement() {
  if (sendingAnnouncement.value) return
  announcementDialog.value?.close()
  if (!pendingAnnouncement.value) {
    announcementTitle.value = ''
    announcementContent.value = ''
    rewardType.value = ''
    rewardNote.value = ''
    announcementError.value = ''
    recipients.value = []
  }
  openAnnouncementButton.value?.focus({ preventScroll: true })
}

async function sendAnnouncement() {
  if (!(await announcement.publish())) return
  announcementDialog.value?.close()
  clearSelection()
  await nextTick()
  searchInput.value?.focus({ preventScroll: true })
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
    announcementDialog.value?.close()
    clearSelection()
  },
  { flush: 'sync' },
)
onBeforeUnmount(() => {
  request?.abort()
  muteRequest?.abort()
  announcementDialog.value?.close()
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
              ref="searchInput"
              v-model="input"
              type="search"
              maxlength="32"
              aria-label="用户名"
              placeholder="搜索用户名"
            />
          </label>
          <button class="primary" type="submit">查询</button>
          <p id="reward-summary-legend" class="reward-summary-legend">
            <span><strong>1</strong> 首次点评</span>
            <span><strong>2</strong> 邀请注册</span>
            <span><strong>3</strong> 其他</span>
          </p>
        </form>
        <p v-if="announcementNotice" class="announcement-notice" role="status">
          {{ announcementNotice }}
        </p>
        <div v-if="selectedCount" class="announcement-selection">
          <div class="selection-summary">
            <strong role="status">已选 {{ selectedCount }} 人</strong>
            <button type="button" :disabled="selectionLocked" @click="clearSelection">
              清空选择
            </button>
            <span v-if="pageSelectionExceedsLimit || selectedCount === recipientLimit">
              每次最多选择 {{ recipientLimit }} 人
            </span>
          </div>
          <button
            ref="openAnnouncementButton"
            class="announcement-primary"
            type="button"
            aria-haspopup="dialog"
            @click="openAnnouncement"
          >
            {{ pendingAnnouncement ? '重试发系统公告' : '发系统公告' }}
          </button>
        </div>
        <section class="results" aria-label="用户列表" :aria-busy="loading">
          <div class="user-table-scroll" aria-label="用户列表表格">
            <table>
              <thead>
                <tr>
                  <th scope="col" class="user-select">
                    <input
                      type="checkbox"
                      aria-label="选择本页用户"
                      :checked="pageFullySelected"
                      :indeterminate.prop="selectedOnPage > 0 && !pageFullySelected"
                      :disabled="
                        selectionLocked || !visibleUsers.length || pageSelectionExceedsLimit
                      "
                      @change="selectPage(($event.target as HTMLInputElement).checked)"
                    />
                  </th>
                  <th scope="col">用户</th>
                  <th scope="col" class="user-metric">点评数</th>
                  <th scope="col" class="user-metric user-submissions">投稿审核通过数</th>
                  <th scope="col" class="user-metric user-invites">邀请注册数</th>
                  <th scope="col" class="user-rewards" aria-describedby="reward-summary-legend">
                    领奖情况
                  </th>
                  <th scope="col" class="user-action">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="user in visibleUsers"
                  :key="user.id"
                  :class="{ 'recipient-selected': selectedRecipients.has(user.id) }"
                >
                  <td class="user-select">
                    <input
                      type="checkbox"
                      :aria-label="`选择 ${user.username}`"
                      :checked="selectedRecipients.has(user.id)"
                      :disabled="
                        selectionLocked ||
                        (selectedCount >= recipientLimit && !selectedRecipients.has(user.id))
                      "
                      @change="selectUsers([user], ($event.target as HTMLInputElement).checked)"
                    />
                  </td>
                  <td>
                    <AppLink class="user" :to="`/admin/users/${user.id}`">
                      <UserAvatar
                        :user-id="user.hasAvatar ? user.id : null"
                        :username="user.username"
                      />
                      <span class="user-name">
                        {{ user.username }}
                        <span v-if="user.mutedAt" class="muted-badge">已禁言</span>
                      </span>
                    </AppLink>
                  </td>
                  <td class="user-metric">{{ number.format(user.reviewCount) }}</td>
                  <td class="user-metric user-submissions">
                    {{ number.format(user.approvedSubmissionCount) }}
                  </td>
                  <td class="user-metric user-invites">
                    {{
                      user.invitedRegistrationCount == null
                        ? '—'
                        : number.format(user.invitedRegistrationCount)
                    }}
                  </td>
                  <td class="user-rewards">
                    <AppLink
                      v-if="user.rewardSummary?.types.length"
                      class="reward-summary-link"
                      :to="{ path: `/admin/users/${user.id}`, query: { tab: 'rewards' } }"
                      :aria-label="`查看${user.username}的奖励情况`"
                      title="查看完整奖励记录"
                    >
                      <span v-for="(type, index) in user.rewardSummary.types" :key="index">{{
                        rewardCodes[type]
                      }}</span>
                      <span v-if="user.rewardSummary.hasMore" class="muted">…</span>
                    </AppLink>
                    <span v-else class="muted">—</span>
                  </td>
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
          </div>
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
    <dialog
      ref="announcementDialog"
      class="user-announcement-dialog"
      aria-labelledby="user-announcement-title"
      @cancel.prevent="closeAnnouncement"
    >
      <form @submit.prevent="sendAnnouncement">
        <header>
          <h2 id="user-announcement-title">发系统公告</h2>
          <button
            class="close-announcement"
            type="button"
            aria-label="关闭系统公告"
            :disabled="sendingAnnouncement"
            @click="closeAnnouncement"
          >
            ×
          </button>
        </header>
        <div class="user-announcement-fields">
          <div class="announcement-recipients">
            <strong>收件人（{{ recipients.length }} 人）</strong>
            <ul v-if="!summarizeRecipients" aria-label="收件人名单">
              <li v-for="recipient in recipients" :key="recipient.id">{{ recipient.username }}</li>
            </ul>
          </div>
          <label>
            公告标题
            <input v-model="announcementTitle" required autofocus :readonly="selectionLocked" />
            <small v-if="Array.from(announcementTitle).length > 120" class="alert">
              标题超过120字
            </small>
          </label>
          <label>
            公告正文
            <textarea v-model="announcementContent" required :readonly="selectionLocked" />
            <small v-if="Array.from(announcementContent).length > 10000" class="alert">
              正文超过10000字
            </small>
          </label>
          <label>
            <span>奖励类型 <span class="reward-field-hint">（选填）</span></span>
            <select v-model="rewardType" name="rewardType" :disabled="selectionLocked">
              <option value="">不登记奖励</option>
              <option v-for="(label, value) in manualRewardLabels" :key="value" :value="value">
                {{ label }}
              </option>
            </select>
          </label>
          <label v-if="rewardType">
            <span>
              奖励说明
              <span class="reward-field-hint">{{
                rewardType === 'OTHER' ? '（必填）' : '（选填）'
              }}</span>
            </span>
            <input
              v-model="rewardNote"
              name="rewardNote"
              :required="rewardType === 'OTHER'"
              :readonly="selectionLocked"
              :placeholder="rewardType === 'OTHER' ? '填写奖励原因或内容' : '可填写奖励内容'"
            />
            <small
              v-if="Array.from(normalizeAnnouncementText(rewardNote)).length > 300"
              class="alert"
            >
              奖励说明超过300字
            </small>
          </label>
          <p v-if="announcementError" class="alert" role="alert">{{ announcementError }}</p>
        </div>
        <footer>
          <button type="button" :disabled="sendingAnnouncement" @click="closeAnnouncement">
            取消
          </button>
          <button
            class="announcement-primary"
            type="submit"
            :disabled="sendingAnnouncement || (!pendingAnnouncement && !validAnnouncement)"
          >
            {{
              sendingAnnouncement
                ? '正在发送…'
                : pendingAnnouncement
                  ? '重试发送'
                  : `发送给 ${recipients.length} 位用户`
            }}
          </button>
        </footer>
      </form>
    </dialog>
  </div>
</template>

<style scoped src="./admin-users.css"></style>
