<template>
  <view class="page">
    <view class="card head">
      <view>
        <view class="name">{{ auth.name }}</view>
        <view class="muted">{{ auth.roles.join(' / ') }} · 管理端</view>
      </view>
      <text class="link" @click="onLogout">退出</text>
    </view>

    <view class="stats">
      <view class="stat"><text class="stat-value">{{ dashboard.onsite }}</text><text class="muted">当前在访</text></view>
      <view class="stat"><text class="stat-value">{{ dashboard.pendingApproval }}</text><text class="muted">待审批</text></view>
      <view class="stat">
        <text class="stat-value">{{ dashboard.devicesOnline }}/{{ dashboard.devicesTotal }}</text>
        <text class="muted">设备在线</text>
      </view>
      <view class="stat"><text class="stat-value">{{ dashboard.todayCheckins }}</text><text class="muted">今日到访</text></view>
    </view>

    <view class="quick">
      <text class="quick-item" @click="go('/pages/admin/blacklist')">黑名单</text>
      <text class="quick-item" @click="go('/pages/admin/report')">报表</text>
      <text class="quick-item" @click="refresh">刷新</text>
    </view>

    <view class="chips">
      <view class="chip" :class="{ active: tab === 'onsite' }" @click="tab = 'onsite'">在访人员</view>
      <view class="chip" :class="{ active: tab === 'pending' }" @click="tab = 'pending'">待审核</view>
      <view class="chip" :class="{ active: tab === 'cc' }" @click="openCc">
        抄送<text v-if="ccUnread > 0">（{{ ccUnread }}）</text>
      </view>
    </view>

    <view v-if="tab === 'onsite'">
      <view v-if="!onsite.length" class="empty">当前没有在访人员</view>
      <view v-for="row in onsite" :key="row.appointmentNo" class="card">
        <view class="card-title">
          <text>{{ row.visitorName }}</text>
          <text class="tag" :class="row.overdue ? 'tag--danger' : 'tag--ok'">{{ row.overdue ? '已超时' : '在访' }}</text>
        </view>
        <view class="line">{{ row.purpose || '事由未填' }} · {{ row.idCardMask }}</view>
        <view class="line">入场 {{ row.checkinAt }}，预约结束 {{ row.endTime }}</view>
        <view class="actions">
          <button class="btn btn--ghost" @click="openDoor(row.appointmentNo)">开门</button>
          <button class="btn btn--danger" @click="checkout(row.appointmentNo)">签离</button>
        </view>
      </view>
    </view>

    <view v-else-if="tab === 'pending'">
      <view v-if="!reviewRows.length" class="empty">没有待审核的预约</view>
      <view v-for="row in reviewRows" :key="row.taskId" class="card">
        <view class="card-title">
          <text>{{ row.visitorName }}</text>
          <text class="tag tag--warn">{{ row.nodeName || '待审核' }}</text>
        </view>
        <view class="line">{{ row.purpose || '事由未填' }} · {{ row.idCardMask }}</view>
        <view class="line">{{ row.startTime }} ~ {{ row.endTime }}</view>
        <view class="actions">
          <button class="btn btn--primary" @click="openReview(row)">审核</button>
          <button class="btn btn--ghost" @click="remind(row.appointmentNo)">催办</button>
        </view>
      </view>
    </view>

    <view v-else>
      <view v-if="ccUnread > 0" class="quick">
        <text class="quick-item" @click="markAllCc">全部已读</text>
      </view>
      <view v-if="!ccList.length" class="empty">暂无抄送消息</view>
      <view v-for="msg in ccList" :key="msg.id" class="card" @click="openCcMessage(msg)">
        <view class="card-title">
          <text>{{ msg.title }}</text>
          <text class="tag" :class="msg.read ? '' : 'tag--warn'">{{ msg.read ? '已读' : '未读' }}</text>
        </view>
        <view class="line">{{ msg.content }}</view>
        <view class="line muted">{{ msg.createdAt }}</view>
      </view>
    </view>

    <!-- 审核弹层：门点可增减、可决定是否同意二维码开门 -->
    <view v-if="reviewVisible" class="mask">
      <view class="sheet">
        <view class="sheet-title">预约审核</view>
        <view v-if="review" class="sheet-body">
          <view class="line strong">{{ review.visitorName }} · {{ review.idCardMask }}</view>
          <view class="line"><text class="tag tag--warn">{{ review.nodeName || `第 ${review.level} 级审核` }}</text></view>
          <view class="line">{{ review.purpose || '事由未填' }} · {{ review.visitorType }}</view>
          <view class="line">{{ review.startTime }} ~ {{ review.endTime }}</view>

          <view v-if="review.gates && review.allowEditGates !== false">
            <view class="sheet-label">开放门点（可增减）</view>
            <checkbox-group class="gates" @change="onGateChange">
              <label v-for="g in review.gates.selectable" :key="g.id" class="gate">
                <checkbox :value="String(g.id)" :checked="reviewGateIds.includes(g.id)" />
                <text>{{ g.name }}</text>
              </label>
            </checkbox-group>
          </view>
          <view v-else class="line muted">本节点不允许调整门点，沿用上一级的批准结果</view>

          <label v-if="review.qrRequested && review.allowQr !== false" class="gate qr-row">
            <checkbox :checked="reviewQrApproved" @click="reviewQrApproved = !reviewQrApproved" />
            <text>同意二维码开门</text>
          </label>

          <textarea v-model="reviewComment" class="opinion" maxlength="200"
            placeholder="通过可不填；拒绝必须填原因" />

          <view class="actions">
            <button class="btn btn--danger" :loading="reviewActing" @click="doReject">拒绝</button>
            <button class="btn btn--primary" :loading="reviewActing" @click="doPass">通过</button>
          </view>
        </view>
        <text class="sheet-cancel" @click="reviewVisible = false">取消</text>
      </view>
    </view>

    <TabBar :items="tabbar" />
  </view>
</template>

<script setup lang="ts">
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import TabBar from '@/components/TabBar.vue'
import { guardCheckout, guardDashboard, guardOnsite, guardOpenDoor, guardRemind,
  guardReviewDetail, guardReviewPass, guardReviewReject, guardReviewTodo,
  listMyNotifications, myUnreadCount, readAllMyNotifications, readMyNotification } from '@/api'
import type { GuardDashboard, MyNotification, OnsiteRow, ReviewDetail, ReviewRow } from '@/api/types'
import { auth, isGuardOrAdmin, logout, requireLogin } from '@/store/auth'
import { alert, confirm, showToast } from '@/utils/ui'

const tabbar = [
  { text: '工作台', path: '/pages/admin/workbench' },
  { text: '预约', path: '/pages/admin/appointments' },
  { text: '通行', path: '/pages/admin/events' },
  { text: '设备', path: '/pages/admin/devices' }
]

const dashboard = ref<GuardDashboard>({
  onsite: 0, pendingApproval: 0, devicesOnline: 0, devicesTotal: 0, todayCheckins: 0, deviceStatusText: ''
})
const onsite = ref<OnsiteRow[]>([])
/** 待审核列表（任务维度，可操作） */
const reviewRows = ref<ReviewRow[]>([])
const tab = ref<'onsite' | 'pending' | 'cc'>('onsite')

/* 抄送（管理端消息中心） */
const ccList = ref<MyNotification[]>([])
const ccUnread = ref(0)

async function loadCcUnread() {
  try {
    ccUnread.value = (await myUnreadCount()).unread
  } catch {
    // 角标失败不影响主流程
  }
}

async function openCc() {
  tab.value = 'cc'
  try {
    ccList.value = (await listMyNotifications({ page: 1, size: 50 })).list
  } catch {
    ccList.value = []
  }
}

async function openCcMessage(message: MyNotification) {
  if (!message.read) {
    try {
      await readMyNotification(message.id)
      message.read = true
      ccUnread.value = Math.max(0, ccUnread.value - 1)
    } catch {
      // 已读失败不阻断
    }
  }
}

/* 审核弹层 */
const reviewVisible = ref(false)
const review = ref<ReviewDetail | null>(null)
const reviewGateIds = ref<number[]>([])
const reviewQrApproved = ref(false)
const reviewComment = ref('')
const reviewActing = ref(false)

async function refresh() {
  dashboard.value = await guardDashboard()
  onsite.value = (await guardOnsite({ page: 1, size: 50 })).list
  reviewRows.value = (await guardReviewTodo({ page: 1, size: 50 })).list
  await loadCcUnread()
}

/** 抄送全部已读 */
async function markAllCc() {
  await readAllMyNotifications()
  ccList.value = ccList.value.map((m) => ({ ...m, read: true }))
  ccUnread.value = 0
}

/** 打开审核弹层：二维码默认勾选（访客申请了就默认同意，减少一次点击） */
async function openReview(row: ReviewRow) {
  try {
    const detail = await guardReviewDetail(row.taskId)
    review.value = detail
    reviewGateIds.value = [...(detail.gates?.approvedGateIds ?? [])]
    reviewQrApproved.value = Boolean(detail.qrRequested) || Boolean(detail.qrApproved)
    reviewComment.value = ''
    reviewVisible.value = true
  } catch {
    // 详情拉取失败的错误已由 http 层提示
  }
}

function onGateChange(e: any) {
  reviewGateIds.value = (((e.detail.value as string[]) || [])).map(Number)
}

async function doPass() {
  if (!review.value) return
  if (review.value.gates && reviewGateIds.value.length === 0) {
    showToast('至少要保留一个开放门点')
    return
  }
  reviewActing.value = true
  try {
    const result = await guardReviewPass(review.value.taskId, {
      comment: reviewComment.value || undefined,
      approvedGateIds: review.value.gates ? reviewGateIds.value : undefined,
      qrApproved: review.value.qrRequested ? reviewQrApproved.value : undefined
    })
    reviewVisible.value = false
    await alert(
      '预约 ' + result.appointmentNo + ' 已通过，' +
        (result.dispatchStarted ? '门禁授权正在下发到设备' : '等待下发授权'),
      '审核通过'
    )
    await refresh()
  } finally {
    reviewActing.value = false
  }
}

async function doReject() {
  if (!review.value) return
  if (!reviewComment.value.trim()) {
    showToast('拒绝必须填写原因')
    return
  }
  reviewActing.value = true
  try {
    await guardReviewReject(review.value.taskId, reviewComment.value.trim())
    reviewVisible.value = false
    showToast('已拒绝')
    await refresh()
  } finally {
    reviewActing.value = false
  }
}

function go(path: string) {
  uni.navigateTo({ url: path })
}

async function openDoor(appointmentNo: string) {
  if (!(await confirm('确认为该访客远程开门？', '开门'))) return
  const result = await guardOpenDoor({ appointmentNo })
  showToast(result.opened ? '已开门（' + result.devices + ' 台设备）' : '开门失败')
  await refresh()
}

async function checkout(appointmentNo: string) {
  if (!(await confirm('确认手工签离？', '签离'))) return
  await guardCheckout(appointmentNo)
  showToast('已签离')
  await refresh()
}

async function remind(appointmentNo: string) {
  try {
    const result = await guardRemind(appointmentNo)
    showToast('已催办，剩余 ' + result.remaining + ' 次')
  } catch {
    // 限流等错误已由 http 层提示
  }
}

async function onLogout() {
  if (await confirm('确认退出登录？', '退出')) logout()
}

onShow(() => {
  if (!requireLogin('ADMIN')) return
  if (!isGuardOrAdmin()) {
    alert('当前账号没有保安/管理员权限', '无权限').then(() => logout())
    return
  }
  refresh()
})

onPullDownRefresh(async () => {
  await refresh()
  uni.stopPullDownRefresh()
})
</script>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.name {
  font-size: 16px;
  font-weight: 600;
}

.link {
  color: #1989fa;
  font-size: 13px;
}

.stats {
  display: flex;
  background: #fff;
  border-radius: 10px;
  padding: 12px 0;
  margin-bottom: 10px;
}

.stat {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.stat-value {
  font-size: 18px;
  font-weight: 600;
}

.quick {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.quick-item {
  flex: 1;
  text-align: center;
  padding: 9px 0;
  background: #fff;
  border-radius: 8px;
  font-size: 13px;
  color: #1989fa;
}

.chips {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.chip {
  padding: 5px 14px;
  border-radius: 14px;
  background: #fff;
  color: #646566;
  font-size: 13px;
}

.chip.active {
  background: #1989fa;
  color: #fff;
}
.mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: flex-end;
  z-index: 99;
}

.sheet {
  width: 100%;
  max-height: 82vh;
  overflow-y: auto;
  background: #fff;
  border-radius: 24rpx 24rpx 0 0;
  padding: 28rpx 28rpx 40rpx;
  box-sizing: border-box;
}

.sheet-title {
  font-size: 34rpx;
  font-weight: 600;
  text-align: center;
  margin-bottom: 20rpx;
}

.sheet-label {
  font-size: 28rpx;
  color: #646566;
  margin: 16rpx 0 8rpx;
}

.sheet-body .line {
  font-size: 28rpx;
  color: #646566;
  margin-bottom: 6rpx;
}

.sheet-body .line.strong {
  font-size: 32rpx;
  color: #323233;
  font-weight: 600;
}

.gates {
  display: flex;
  flex-wrap: wrap;
  gap: 20rpx;
}

.gate {
  display: flex;
  align-items: center;
  gap: 8rpx;
  font-size: 28rpx;
  color: #323233;
}

.qr-row {
  margin-top: 16rpx;
}

.opinion {
  width: 100%;
  min-height: 130rpx;
  margin-top: 16rpx;
  padding: 16rpx;
  box-sizing: border-box;
  background: #f7f8fa;
  border-radius: 12rpx;
  font-size: 28rpx;
}

.sheet-cancel {
  display: block;
  text-align: center;
  margin-top: 20rpx;
  font-size: 30rpx;
  color: #969799;
}
</style>
