<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount, useTemplateRef } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { authToken } from '@/shared/api/authSession'
import LeaveConfirmation from '@/shared/ui/LeaveConfirmation.vue'
import { useAdminAnnouncements } from '../model/useAdminAnnouncements'
import type { AnnouncementDetail } from '@/modules/announcement'
import AdminSidebar from './AdminSidebar.vue'
import AdminHeader from './AdminHeader.vue'
import AnnouncementRecipientDialog from './AnnouncementRecipientDialog.vue'
defineProps<{ username: string; loggingOut: boolean; sessionError: string | null }>()
defineEmits<{ logout: [] }>()
const state = useAdminAnnouncements()
const {
  tab,
  title,
  content,
  status,
  result,
  detail,
  busy,
  loading,
  inspecting,
  error,
  actionError,
  notice,
  pending,
  recipients,
  page,
  dirty,
  validForm,
} = state
const confirmation = useTemplateRef('confirmation'),
  recordDialog = useTemplateRef('recordDialog'),
  recipientDialog = useTemplateRef('recipientDialog')
const confirming = ref<{ kind: 'publish' | 'withdraw'; row?: AnnouncementDetail } | null>(null)
const recipientNames = computed(() => recipients.value.map(({ username }) => username).join('、'))
const recordId = ref('')
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
const readingTime = (value: string) =>
  new Date(value).toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
function ask(kind: 'publish' | 'withdraw', row?: AnnouncementDetail) {
  if (busy.value) return
  actionError.value = ''
  if (kind === 'publish' && !pending.value && !validForm.value) {
    actionError.value = '请填写公告标题和正文，标题最多120字，正文最多10000字。'
    return
  }
  confirming.value = { kind, row }
  confirmation.value?.showModal()
}
function closeConfirmation() {
  if (busy.value) return
  confirmation.value?.close()
  confirming.value = null
}
async function confirm() {
  const action = confirming.value
  if (!action || busy.value) return
  const success =
    action.kind === 'publish'
      ? await state.publish()
      : action.row
        ? await state.withdraw(action.row.id)
        : false
  if (success) closeConfirmation()
}
function inspect(id: string) {
  recordId.value = id
  recordDialog.value?.showModal()
  void state.inspect(id)
}
function leave(event: globalThis.BeforeUnloadEvent) {
  if (dirty.value || busy.value) event.preventDefault()
}
globalThis.addEventListener('beforeunload', leave)
const leaveConfirmation = useTemplateRef('leaveConfirmation')
onBeforeRouteLeave(() => !busy.value && (!dirty.value || leaveConfirmation.value?.ask()))
watch(
  authToken,
  () => {
    confirmation.value?.close()
    recordDialog.value?.close()
    confirming.value = null
  },
  { flush: 'sync' },
)
onBeforeUnmount(() => {
  globalThis.removeEventListener('beforeunload', leave)
  confirmation.value?.close()
  recordDialog.value?.close()
})
</script>
<template>
  <div class="announcement-workspace admin-theme">
    <LeaveConfirmation ref="leaveConfirmation" message="公告尚未发布或确认，确定离开？" />
    <AdminHeader
      :username="username"
      :logging-out="loggingOut"
      :disabled="busy"
      @logout="$emit('logout')"
    />
    <p v-if="sessionError" role="alert" class="announcement-error">{{ sessionError }}</p>
    <div class="announcement-shell">
      <AdminSidebar />
      <main>
        <h1>公告管理</h1>
        <div class="announcement-tabs" aria-label="公告管理视图">
          <button
            :class="{ active: tab === 'publish' }"
            :aria-pressed="tab === 'publish'"
            :disabled="busy"
            @click="tab = 'publish'"
          >
            发布公告</button
          ><button
            :class="{ active: tab === 'records' }"
            :aria-pressed="tab === 'records'"
            :disabled="busy"
            @click="tab = 'records'"
          >
            公告记录
          </button>
        </div>
        <p v-if="notice" role="status" class="announcement-notice">{{ notice }}</p>
        <form v-if="tab === 'publish'" class="announcement-editor" @submit.prevent="ask('publish')">
          <div class="announcement-field audience-field">
            <span class="audience-label">发送范围</span>
            <div class="announcement-audience">
              <strong>{{ recipients.length ? '指定用户' : '全体用户' }}</strong>
              <span v-if="recipientNames" class="audience-names">{{ recipientNames }}</span>
              <button
                type="button"
                :disabled="busy || !!pending"
                aria-haspopup="dialog"
                @click="recipientDialog?.open(recipients)"
              >
                更改
              </button>
            </div>
          </div>
          <div class="announcement-field">
            <label for="announcement-title">公告标题 <span>*</span></label
            ><input
              id="announcement-title"
              v-model="title"
              required
              :readonly="!!pending"
              :disabled="busy"
              autocomplete="off"
            /><small v-if="Array.from(title).length > 120" class="invalid">标题超过120字</small>
          </div>
          <div class="announcement-field">
            <label for="announcement-content">公告正文 <span>*</span></label
            ><textarea
              id="announcement-content"
              v-model="content"
              required
              :readonly="!!pending"
              :disabled="busy"
            /><small v-if="Array.from(content).length > 10000" class="invalid"
              >正文超过10000字</small
            >
          </div>
          <p v-if="actionError" role="alert" class="announcement-error">{{ actionError }}</p>
          <footer class="announcement-actions">
            <button class="primary" type="submit" :disabled="busy">发布公告</button>
          </footer>
        </form>
        <section v-else class="announcement-records" aria-label="公告记录">
          <div class="announcement-record-toolbar">
            <select v-model="status" aria-label="公告状态" :disabled="busy">
              <option value="ALL">全部状态</option>
              <option value="PUBLISHED">已发布</option>
              <option value="WITHDRAWN">已撤回</option>
            </select>
            <span>{{ loading ? '正在加载…' : result ? `共 ${result.total} 条公告` : '' }}</span>
          </div>
          <p v-if="actionError" role="alert" class="announcement-error">{{ actionError }}</p>
          <p v-if="loading" role="status">正在加载公告…</p>
          <div v-else-if="error" role="alert">
            <p>{{ error }}</p>
            <button @click="state.load()">重试加载</button>
          </div>
          <template v-else-if="result"
            ><p v-if="!result.records.length" class="announcement-empty">
              {{ result.total ? '当前页记录已变化，请返回第一页。' : '暂无公告记录' }}
            </p>
            <ul v-else class="announcement-record-list">
              <li v-for="row in result.records" :key="row.id">
                <div class="record-summary">
                  <div class="record-heading">
                    <button class="record-title" :disabled="busy" @click="inspect(row.id)">
                      {{ row.title }}
                    </button>
                    <span
                      class="announcement-status"
                      :class="{ withdrawn: row.status === 'WITHDRAWN' }"
                      >{{ row.status === 'PUBLISHED' ? '已发布' : '已撤回' }}</span
                    >
                  </div>
                  <p>
                    <time :datetime="row.publishedAt">{{ time(row.publishedAt) }}</time>
                    <span>{{ row.audience === 'TARGETED' ? '指定用户' : '全体用户' }}</span>
                  </p>
                </div>
                <div class="record-actions">
                  <button
                    v-if="row.status === 'PUBLISHED'"
                    :disabled="busy"
                    @click="ask('withdraw', row)"
                  >
                    撤回公告
                  </button>
                </div>
              </li>
            </ul>
            <div class="announcement-pagination">
              <span>第 {{ page }} / {{ result.totalPages || 1 }} 页</span
              ><button :disabled="page <= 1 || busy" @click="state.load(page - 1)">上一页</button
              ><button :disabled="page >= result.totalPages || busy" @click="state.load(page + 1)">
                下一页
              </button>
            </div>
          </template>
        </section>
      </main>
    </div>
    <AnnouncementRecipientDialog ref="recipientDialog" @save="recipients = $event" />
    <dialog
      ref="confirmation"
      class="announcement-dialog"
      aria-labelledby="announcement-confirm-title"
      @cancel="busy ? $event.preventDefault() : closeConfirmation()"
    >
      <h2 id="announcement-confirm-title">
        {{ confirming?.kind === 'withdraw' ? '确认撤回公告？' : '确认发布公告？' }}
      </h2>
      <template v-if="confirming?.kind === 'publish'"
        ><p class="confirmation-title">{{ pending?.title || title }}</p>
        <p>{{ recipientNames ? `发送给 ${recipientNames}` : '发送给全体用户' }}</p>
        <p>发布后不可编辑，如需更正可撤回后重新发布。</p></template
      >
      <template v-else-if="confirming?.kind === 'withdraw'"
        ><p class="confirmation-title">{{ confirming.row?.title }}</p>
        <p>撤回后，用户将无法查看这条公告。</p></template
      >
      <p v-if="actionError" role="alert" class="announcement-error">{{ actionError }}</p>
      <footer class="announcement-actions">
        <button :disabled="busy" @click="closeConfirmation">取消</button
        ><button class="primary" :disabled="busy" @click="confirm">
          {{ busy ? '正在提交…' : confirming?.kind === 'withdraw' ? '确认撤回' : '确认发布' }}
        </button>
      </footer>
    </dialog>
    <dialog
      ref="recordDialog"
      class="announcement-dialog record-dialog"
      aria-labelledby="announcement-detail-title"
    >
      <div class="record-dialog-shell">
        <header class="record-dialog-header">
          <div class="announcement-reading-heading">
            <h2 id="announcement-detail-title">{{ detail?.title || '公告详情' }}</h2>
            <div v-if="detail" class="announcement-detail-meta">
              <time :datetime="detail.publishedAt">{{ readingTime(detail.publishedAt) }}</time>
              <span>{{ detail.audience === 'TARGETED' ? '指定用户' : '全体用户' }}</span>
              <span
                class="announcement-status"
                :class="{ withdrawn: detail.status === 'WITHDRAWN' }"
                >{{ detail.status === 'PUBLISHED' ? '已发布' : '已撤回' }}</span
              >
            </div>
          </div>
          <button class="dialog-close" aria-label="关闭公告详情" @click="recordDialog?.close()">
            ×
          </button>
        </header>
        <div class="record-dialog-content">
          <p v-if="inspecting" role="status" class="announcement-empty">正在加载公告…</p>
          <div v-else-if="!detail" role="alert" class="detail-failure">
            <p>{{ actionError || '公告暂时不可用。' }}</p>
            <button @click="state.inspect(recordId)">重试加载</button>
          </div>
          <article v-else>
            <p class="announcement-body">{{ detail.content }}</p>
            <p
              v-if="detail.withdrawnAt"
              class="announcement-detail-meta announcement-withdrawn-time"
            >
              撤回于 {{ readingTime(detail.withdrawnAt) }}
            </p>
          </article>
        </div>
      </div>
    </dialog>
  </div>
</template>
<style scoped src="./admin-announcements.css"></style>
