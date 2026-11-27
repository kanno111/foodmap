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
  detailError,
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
const audience = ref<'ALL' | 'TARGETED'>('ALL')
const missingRecipients = computed(() => audience.value === 'TARGETED' && !recipients.value.length)
const titleLength = computed(() => Array.from(title.value).length)
const contentLength = computed(() => Array.from(content.value).length)
const recordId = ref('')
let recordSource: globalThis.HTMLButtonElement | undefined
const wideRecords = globalThis.matchMedia?.('(min-width: 1180px)')
const readingTime = (value: string) =>
  new Date(value).toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  })
function chooseAll() {
  audience.value = 'ALL'
  recipients.value = []
}
function chooseRecipients() {
  audience.value = 'TARGETED'
  recipientDialog.value?.open(recipients.value)
}
function ask(kind: 'publish' | 'withdraw', row?: AnnouncementDetail) {
  if (busy.value) return
  actionError.value = ''
  if (kind === 'publish' && missingRecipients.value) {
    actionError.value = '请选择公告收件人。'
    return
  }
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
  if (success) {
    if (action.kind === 'publish') chooseAll()
    else closeRecord()
    closeConfirmation()
  }
}
function showRecord() {
  const dialog = recordDialog.value
  if (!dialog || !recordId.value || dialog.open) return
  if (wideRecords?.matches) dialog.show()
  else dialog.showModal()
}
function syncRecord() {
  if (!recordDialog.value?.open) return
  recordDialog.value.close()
  showRecord()
}
wideRecords?.addEventListener('change', syncRecord)
function inspect(id: string, event: globalThis.MouseEvent) {
  recordId.value = id
  recordSource = event.currentTarget as globalThis.HTMLButtonElement
  showRecord()
  void state.inspect(id)
}
function closeRecord(restoreFocus = false) {
  if (!recordId.value) return
  recordDialog.value?.close()
  recordId.value = ''
  state.clearDetail()
  if (restoreFocus) recordSource?.focus({ preventScroll: true })
  recordSource = undefined
}
watch([tab, status, page], () => closeRecord())
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
    closeRecord()
    chooseAll()
    confirming.value = null
  },
  { flush: 'sync' },
)
onBeforeUnmount(() => {
  globalThis.removeEventListener('beforeunload', leave)
  wideRecords?.removeEventListener('change', syncRecord)
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
        <header class="announcement-page-heading">
          <h1>公告管理</h1>
          <div class="announcement-tabs" role="group" aria-label="公告管理视图">
            <button
              type="button"
              :aria-pressed="tab === 'publish'"
              :disabled="busy"
              @click="tab = 'publish'"
            >
              发布公告
            </button>
            <button
              type="button"
              :aria-pressed="tab === 'records'"
              :disabled="busy"
              @click="tab = 'records'"
            >
              公告记录
            </button>
          </div>
        </header>
        <p v-if="notice" role="status" class="announcement-notice">{{ notice }}</p>
        <form v-if="tab === 'publish'" class="announcement-editor" @submit.prevent="ask('publish')">
          <div class="announcement-editor-grid">
            <div class="announcement-writing">
              <div class="announcement-field">
                <div class="field-heading">
                  <label for="announcement-title">公告标题</label>
                  <small :class="{ invalid: titleLength > 120 }">{{ titleLength }} / 120</small>
                </div>
                <input
                  id="announcement-title"
                  v-model="title"
                  required
                  :readonly="!!pending"
                  :disabled="busy"
                  :aria-invalid="titleLength > 120"
                  autocomplete="off"
                />
              </div>
              <div class="announcement-field">
                <div class="field-heading">
                  <label for="announcement-content">公告正文</label>
                  <small :class="{ invalid: contentLength > 10000 }"
                    >{{ contentLength }} / 10,000</small
                  >
                </div>
                <textarea
                  id="announcement-content"
                  v-model="content"
                  required
                  :readonly="!!pending"
                  :disabled="busy"
                  :aria-invalid="contentLength > 10000"
                />
              </div>
              <p v-if="actionError" role="alert" class="announcement-error">{{ actionError }}</p>
            </div>
            <section class="announcement-audience" aria-labelledby="announcement-audience-heading">
              <h2 id="announcement-audience-heading">发送范围</h2>
              <div
                class="audience-options"
                role="group"
                aria-labelledby="announcement-audience-heading"
              >
                <label :class="{ active: audience === 'ALL' }">
                  <input
                    type="radio"
                    name="announcement-audience"
                    value="ALL"
                    :checked="audience === 'ALL'"
                    :disabled="busy || !!pending"
                    @change="chooseAll"
                  />
                  全体用户
                </label>
                <label :class="{ active: audience === 'TARGETED' }">
                  <input
                    type="radio"
                    name="announcement-audience"
                    value="TARGETED"
                    :checked="audience === 'TARGETED'"
                    :disabled="busy || !!pending"
                    @change="chooseRecipients"
                  />
                  指定用户
                </label>
              </div>
              <div v-if="audience === 'TARGETED'" class="audience-summary">
                <div class="audience-summary-heading">
                  <strong>已选 {{ recipients.length }} 人</strong>
                  <button
                    class="text-action"
                    type="button"
                    :disabled="busy || !!pending"
                    aria-haspopup="dialog"
                    @click="chooseRecipients"
                  >
                    {{ recipients.length ? '更改' : '选择用户' }}
                  </button>
                </div>
                <ul v-if="recipients.length" class="audience-names" aria-label="已选收件人">
                  <li v-for="recipient in recipients" :key="recipient.id">
                    {{ recipient.username }}
                  </li>
                </ul>
              </div>
            </section>
          </div>
          <footer class="announcement-editor-footer">
            <span>{{
              audience === 'ALL'
                ? '发送给全体用户'
                : missingRecipients
                  ? '请选择收件人'
                  : `发送给指定的 ${recipients.length} 位用户`
            }}</span>
            <button class="primary" type="submit" :disabled="busy || missingRecipients">
              发布公告
            </button>
          </footer>
        </form>
        <div v-else class="announcement-records-layout" :class="{ 'with-detail': recordId }">
          <section class="announcement-records" aria-label="公告记录">
            <div class="announcement-record-toolbar">
              <select v-model="status" aria-label="公告状态" :disabled="busy">
                <option value="ALL">全部状态</option>
                <option value="PUBLISHED">已发布</option>
                <option value="WITHDRAWN">已撤回</option>
              </select>
              <span>{{ loading ? '正在加载…' : result ? `共 ${result.total} 条公告` : '' }}</span>
            </div>
            <p v-if="actionError" role="alert" class="announcement-error list-message">
              {{ actionError }}
            </p>
            <p v-if="loading" class="list-message" role="status">正在加载公告…</p>
            <div v-else-if="error" class="list-message" role="alert">
              <p>{{ error }}</p>
              <button @click="state.load()">重试加载</button>
            </div>
            <template v-else-if="result"
              ><p v-if="!result.records.length" class="announcement-empty">
                {{ result.total ? '当前页记录已变化，请返回第一页。' : '暂无公告记录' }}
              </p>
              <table v-else class="announcement-record-table" aria-label="公告列表" role="table">
                <thead>
                  <tr>
                    <th scope="col">公告标题</th>
                    <th scope="col">发送范围</th>
                    <th scope="col">发布时间</th>
                    <th scope="col">状态</th>
                    <th scope="col">操作</th>
                  </tr>
                </thead>
                <tbody role="rowgroup">
                  <tr
                    v-for="row in result.records"
                    :key="row.id"
                    :class="{ selected: recordId === row.id }"
                    role="row"
                  >
                    <td class="record-title-cell" role="cell">
                      <button
                        class="record-title"
                        type="button"
                        :disabled="busy"
                        @click="inspect(row.id, $event)"
                      >
                        {{ row.title }}
                      </button>
                    </td>
                    <td class="record-audience" role="cell">
                      {{ row.audience === 'TARGETED' ? '指定用户' : '全体用户' }}
                    </td>
                    <td class="record-time" role="cell">
                      <time :datetime="row.publishedAt">{{ readingTime(row.publishedAt) }}</time>
                    </td>
                    <td class="record-status" role="cell">
                      <span
                        class="announcement-status"
                        :class="{ withdrawn: row.status === 'WITHDRAWN' }"
                        >{{ row.status === 'PUBLISHED' ? '已发布' : '已撤回' }}</span
                      >
                    </td>
                    <td class="record-actions-cell" role="cell">
                      <div class="record-actions">
                        <button
                          type="button"
                          class="text-action"
                          :disabled="busy"
                          :aria-label="`查看公告详情：${row.title}`"
                          :aria-expanded="recordId === row.id"
                          aria-controls="announcement-record-detail"
                          @click="inspect(row.id, $event)"
                        >
                          详情
                        </button>
                        <button
                          v-if="row.status === 'PUBLISHED'"
                          type="button"
                          class="text-action withdraw"
                          :disabled="busy"
                          :aria-label="`撤回公告：${row.title}`"
                          @click="ask('withdraw', row)"
                        >
                          撤回
                        </button>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
              <div class="announcement-pagination">
                <span>第 {{ page }} / {{ result.totalPages || 1 }} 页</span>
                <div class="record-page-buttons">
                  <button type="button" :disabled="page <= 1 || busy" @click="state.load(page - 1)">
                    上一页
                  </button>
                  <button
                    type="button"
                    :disabled="page >= result.totalPages || busy"
                    @click="state.load(page + 1)"
                  >
                    下一页
                  </button>
                </div>
              </div>
            </template>
          </section>
          <dialog
            id="announcement-record-detail"
            ref="recordDialog"
            class="record-detail"
            aria-labelledby="announcement-detail-heading"
            @cancel.prevent="closeRecord(true)"
            @keydown.esc.stop.prevent="closeRecord(true)"
          >
            <header class="record-detail-header">
              <h2 id="announcement-detail-heading">公告详情</h2>
              <button
                type="button"
                class="dialog-close"
                aria-label="关闭公告详情"
                @click="closeRecord(true)"
              >
                ×
              </button>
            </header>
            <div class="record-detail-content">
              <p v-if="inspecting" role="status">正在加载公告…</p>
              <div v-else-if="!detail" role="alert" class="announcement-error">
                <p>{{ detailError || '公告暂时不可用。' }}</p>
                <button type="button" @click="state.inspect(recordId)">重试加载</button>
              </div>
              <article v-else>
                <div class="record-detail-title">
                  <h3>{{ detail.title }}</h3>
                  <span
                    class="announcement-status"
                    :class="{ withdrawn: detail.status === 'WITHDRAWN' }"
                  >
                    {{ detail.status === 'PUBLISHED' ? '已发布' : '已撤回' }}
                  </span>
                </div>
                <dl class="record-detail-meta">
                  <dt>发布时间</dt>
                  <dd>
                    <time :datetime="detail.publishedAt">{{
                      readingTime(detail.publishedAt)
                    }}</time>
                  </dd>
                  <dt>发送范围</dt>
                  <dd>{{ detail.audience === 'TARGETED' ? '指定用户' : '全体用户' }}</dd>
                  <template v-if="detail.withdrawnAt">
                    <dt>撤回时间</dt>
                    <dd>
                      <time :datetime="detail.withdrawnAt">{{
                        readingTime(detail.withdrawnAt)
                      }}</time>
                    </dd>
                  </template>
                </dl>
                <section v-if="detail.audience === 'TARGETED'" class="record-detail-section">
                  <h4>收件人{{ detail.recipients ? `（${detail.recipients.length} 人）` : '' }}</h4>
                  <ul v-if="detail.recipients" class="record-recipients" aria-label="收件人名单">
                    <li v-for="recipient in detail.recipients" :key="recipient.id">
                      {{ recipient.username ?? `账号不可用 · ID ${recipient.id}` }}
                    </li>
                  </ul>
                  <p v-else>当前服务版本未返回收件人名单。</p>
                </section>
                <section class="record-detail-section">
                  <h4>公告正文</h4>
                  <p class="announcement-body">{{ detail.content }}</p>
                </section>
              </article>
            </div>
          </dialog>
        </div>
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
        <p>
          {{
            audience === 'TARGETED' ? `发送给指定的 ${recipients.length} 位用户` : '发送给全体用户'
          }}
        </p>
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
  </div>
</template>
<style scoped src="./admin-announcements.css"></style>
