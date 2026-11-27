<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import UserAvatar from '@/modules/account/ui/UserAvatar.vue'
import AppLink from '@/shared/ui/AppLink.vue'
import { authToken } from '@/shared/api/authSession'
import { useListRouteState } from '@/shared/lib/useListRouteState'
import { useReturnNavigation } from '@/shared/lib/useReturnNavigation'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { manualRewardLabels } from '@/modules/announcement'
import {
  fetchAdminUserInvitees,
  fetchAdminUserProfile,
  fetchAdminUserReviews,
  fetchAdminUserRewards,
  type AdminUserInvitee,
  type AdminUserProfile,
  type AdminUserReview,
  type AdminUserReward,
} from '../api/adminUsersApi'
import AdminHeader from './AdminHeader.vue'
import AdminSidebar from './AdminSidebar.vue'

const props = defineProps<{ userId: string; username: string; loggingOut: boolean }>()
defineEmits<{ logout: [] }>()

const route = useRoute()
const router = useRouter()
const routeState = useListRouteState(() => true)
type DetailTab = 'reviews' | 'invites' | 'rewards'
const tab = computed<DetailTab>(() => {
  const selected = route.query.tab
  return selected === 'invites' || selected === 'rewards' ? selected : 'reviews'
})
const page = computed(routeState.page)
const { returnTarget, returnToPrevious } = useReturnNavigation(
  '/admin/users',
  (value): value is string =>
    typeof value === 'string' && /^\/admin\/users(?:\/[1-9]\d{0,18})?(?:\?[^#\\\s]*)?$/.test(value),
)
const backLabel = computed(() => {
  if (!returnTarget.value.startsWith('/admin/users/')) return '返回用户管理'
  const name = router.options.history.state.adminInviteFrom
  return typeof name === 'string' && name ? `返回${name}的邀请记录` : '返回上一位用户'
})

const profile = ref<AdminUserProfile>()
const reviews = ref<AdminUserReview[]>([])
const invitees = ref<AdminUserInvitee[]>([])
const rewards = ref<AdminUserReward[]>([])
const totalPages = ref(0)
const profileLoading = ref(false)
const recordsLoading = ref(false)
const profileError = ref('')
const recordsError = ref('')
const profileRetry = ref(0)
const recordsRetry = ref(0)
const busy = computed(() => profileLoading.value || recordsLoading.value)
const error = computed(() => profileError.value || recordsError.value)
const dateFormatter = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' })
const timeFormatter = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' })
const rewardTimeFormatter = new Intl.DateTimeFormat('sv-SE', {
  dateStyle: 'short',
  timeStyle: 'short',
})
const recommendation = { RECOMMENDED: '推荐', NEUTRAL: '一般', NOT_RECOMMENDED: '不推荐' }

function formatDate(value: string, formatter = dateFormatter) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : formatter.format(date)
}

const accountLabel = computed(() => {
  if (profile.value?.status === 'LOCKED') return '已锁定'
  if (profile.value?.status === 'DISABLED') return '已停用'
  return profile.value?.mutedAt ? '已禁言' : '正常'
})
const registrationLabel = computed(() => {
  const source = profile.value?.registrationSource
  if (source?.kind === 'ADMIN_INVITE') return '校友邀请'
  if (source?.kind === 'REFERRAL')
    return source.inviterName ? `${source.inviterName}邀请` : '好友邀请'
  return '直接注册'
})

function changeTab(next: DetailTab) {
  if (tab.value !== next)
    void routeState.save({ tab: next === 'reviews' ? undefined : next, page: undefined })
}

function goToPage(next: number) {
  void routeState.save({ page: next > 1 ? next : undefined })
}

function retry() {
  if (profileError.value) profileRetry.value++
  if (recordsError.value) recordsRetry.value++
}

watch(
  [() => props.userId, authToken, profileRetry],
  async ([id, token], _, onCleanup) => {
    profile.value = undefined
    profileError.value = ''
    profileLoading.value = !!token
    if (!token) return
    const request = new globalThis.AbortController()
    onCleanup(() => request.abort())
    try {
      const result = await fetchAdminUserProfile(id, token, request.signal)
      if (!request.signal.aborted) profile.value = result
    } catch (reason) {
      if (!request.signal.aborted) profileError.value = toErrorMessage(reason)
    } finally {
      if (!request.signal.aborted) profileLoading.value = false
    }
  },
  { immediate: true, flush: 'sync' },
)

watch(
  [() => props.userId, tab, page, authToken, recordsRetry],
  async ([id, section, currentPage, token], _, onCleanup) => {
    reviews.value = []
    invitees.value = []
    rewards.value = []
    recordsError.value = ''
    totalPages.value = 0
    recordsLoading.value = !!token
    if (!token) return
    const request = new globalThis.AbortController()
    onCleanup(() => request.abort())
    try {
      if (section === 'invites') {
        const result = await fetchAdminUserInvitees(id, currentPage, token, request.signal)
        if (request.signal.aborted) return
        invitees.value = result.records
        totalPages.value = result.totalPages
      } else if (section === 'rewards') {
        const result = await fetchAdminUserRewards(id, currentPage, token, request.signal)
        if (request.signal.aborted) return
        rewards.value = result.records
        totalPages.value = result.totalPages
      } else {
        const result = await fetchAdminUserReviews(id, currentPage, token, request.signal)
        if (request.signal.aborted) return
        reviews.value = result.records
        totalPages.value = result.totalPages
      }
      if (currentPage > Math.max(1, totalPages.value)) goToPage(Math.max(1, totalPages.value))
    } catch (reason) {
      if (!request.signal.aborted) recordsError.value = toErrorMessage(reason)
    } finally {
      if (!request.signal.aborted) recordsLoading.value = false
    }
  },
  { immediate: true },
)
</script>

<template>
  <div class="admin-user-detail admin-theme">
    <AdminHeader :username="username" :logging-out="loggingOut" @logout="$emit('logout')" />
    <div class="detail-shell">
      <AdminSidebar />
      <main>
        <AppLink class="back" :to="returnTarget" @click.capture="returnToPrevious">
          <span aria-hidden="true">‹</span>{{ backLabel }}
        </AppLink>
        <header v-if="profile" class="identity">
          <UserAvatar
            :user-id="profile.hasAvatar ? profile.id : null"
            :username="profile.username"
          />
          <div class="identity-text">
            <div class="identity-title">
              <h1>{{ profile.username }}</h1>
              <span class="account-status" :class="{ normal: accountLabel === '正常' }">{{
                accountLabel
              }}</span>
            </div>
            <p>用户 ID {{ profile.id }} · 注册于 {{ formatDate(profile.createdAt) }}</p>
          </div>
          <dl class="metrics">
            <div>
              <dt>点评</dt>
              <dd>{{ profile.reviewCount }}</dd>
            </div>
            <div>
              <dt>投稿通过</dt>
              <dd>{{ profile.approvedSubmissionCount }}</dd>
            </div>
            <div>
              <dt>成功邀请</dt>
              <dd>{{ profile.invitedRegistrationCount ?? '—' }}</dd>
            </div>
          </dl>
        </header>
        <nav v-if="profile" class="detail-tabs" aria-label="用户详情">
          <button type="button" :aria-pressed="tab === 'reviews'" @click="changeTab('reviews')">
            点评记录
          </button>
          <button type="button" :aria-pressed="tab === 'invites'" @click="changeTab('invites')">
            邀请注册
          </button>
          <button type="button" :aria-pressed="tab === 'rewards'" @click="changeTab('rewards')">
            奖励情况
          </button>
        </nav>
        <div class="detail-records" :aria-busy="busy">
          <p v-if="busy" class="panel feedback" role="status">正在加载用户详情…</p>
          <div v-else-if="error" class="panel feedback" role="alert">
            <p>{{ error }}</p>
            <button class="secondary" type="button" @click="retry">重试</button>
          </div>
          <template v-else-if="profile && tab === 'invites'">
            <section class="panel" aria-label="邀请注册记录">
              <div class="registration-source">
                <span>注册来源</span><strong>{{ registrationLabel }}</strong>
              </div>
              <table v-if="invitees.length">
                <thead>
                  <tr>
                    <th scope="col">受邀用户</th>
                    <th scope="col">注册时间</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="invitee in invitees" :key="invitee.id">
                    <td>
                      <AppLink
                        v-if="invitee.profileAvailable !== false"
                        class="invitee"
                        :to="{
                          path: `/admin/users/${invitee.id}`,
                          state: { adminInviteFrom: profile.username },
                        }"
                      >
                        <UserAvatar
                          :user-id="invitee.hasAvatar ? invitee.id : null"
                          :username="invitee.username"
                        />
                        <span>{{ invitee.username }}</span
                        ><span aria-hidden="true" class="invitee-chevron">›</span>
                      </AppLink>
                      <span v-else>{{ invitee.username }}</span>
                    </td>
                    <td>
                      <time :datetime="invitee.registeredAt">{{
                        formatDate(invitee.registeredAt, timeFormatter)
                      }}</time>
                    </td>
                  </tr>
                </tbody>
              </table>
              <p v-else class="feedback">暂无成功邀请</p>
            </section>
          </template>
          <section v-else-if="profile && tab === 'rewards'" class="panel" aria-label="奖励记录">
            <div v-if="rewards.length" class="reward-table-scroll">
              <table class="reward-table">
                <thead>
                  <tr>
                    <th scope="col">奖励类型</th>
                    <th scope="col">奖励说明</th>
                    <th scope="col">来源公告</th>
                    <th scope="col">登记时间</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="reward in rewards" :key="reward.id">
                    <td>{{ manualRewardLabels[reward.rewardType] }}</td>
                    <td class="reward-description">{{ reward.rewardNote ?? '—' }}</td>
                    <td>
                      {{ reward.sourceAnnouncementTitle ?? `公告 ${reward.sourceAnnouncementId}` }}
                      <span v-if="reward.sourceWithdrawn" class="withdrawn-notice">已撤回</span>
                    </td>
                    <td>
                      <time :datetime="reward.recordedAt">{{
                        formatDate(reward.recordedAt, rewardTimeFormatter)
                      }}</time>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p v-else class="feedback">暂无奖励记录</p>
          </section>
          <section v-else-if="profile" class="panel" aria-label="点评记录">
            <details v-for="review in reviews" :key="review.id" class="review">
              <summary>
                <span class="review-title">{{ review.restaurantName }}</span>
                <span class="review-action"
                  ><span class="show-review">展开点评</span
                  ><span class="hide-review">收起</span></span
                >
                <span class="review-meta">
                  {{ formatDate(review.createdAt) }} · {{ recommendation[review.recommendation] }} ·
                  {{ review.overallScore }}/10<span v-if="review.scoringStatus === 'history'">
                    · 历史点评</span
                  ><span v-if="review.photos.length"> · {{ review.photos.length }} 张图</span>
                </span>
                <span class="review-excerpt">{{ review.body }}</span>
              </summary>
              <div class="review-expanded">
                <p class="full-review">{{ review.body }}</p>
                <div v-if="review.photos.length" class="review-photos">
                  <img
                    v-for="photo in review.photos"
                    :key="photo.id"
                    :src="photo.url"
                    alt="点评照片"
                    loading="lazy"
                  />
                </div>
                <dl class="metrics">
                  <div>
                    <dt>口味</dt>
                    <dd>{{ review.scores.taste }}</dd>
                  </div>
                  <div>
                    <dt>性价比</dt>
                    <dd>{{ review.scores.value }}</dd>
                  </div>
                  <div>
                    <dt>服务</dt>
                    <dd>{{ review.scores.service }}</dd>
                  </div>
                  <div>
                    <dt>环境</dt>
                    <dd>{{ review.scores.ambience }}</dd>
                  </div>
                </dl>
              </div>
            </details>
            <p v-if="!reviews.length" class="feedback">暂无点评记录</p>
          </section>
          <nav
            v-if="!busy && !error && totalPages > 1"
            class="detail-pagination"
            aria-label="用户详情分页"
          >
            <button
              class="secondary"
              type="button"
              :disabled="page <= 1"
              @click="goToPage(page - 1)"
            >
              上一页
            </button>
            <span>第 {{ page }} / {{ totalPages }} 页</span>
            <button
              class="secondary"
              type="button"
              :disabled="page >= totalPages"
              @click="goToPage(page + 1)"
            >
              下一页
            </button>
          </nav>
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped src="./admin-user-detail.css"></style>
