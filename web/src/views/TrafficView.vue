<template>
  <div class="page-card">
    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <el-tab-pane label="人员通行" name="traffic" />
      <el-tab-pane label="拒绝记录" name="denied" />
    </el-tabs>

    <template v-if="activeTab === 'traffic'">
    <div class="toolbar">
      <el-date-picker v-model="query.range" type="daterange" value-format="YYYY-MM-DD"
        start-placeholder="开始日期" end-placeholder="结束日期" style="width: 240px" />
      <el-select v-model="query.personType" placeholder="全部人员" clearable style="width: 120px">
        <el-option label="员工" value="EMPLOYEE" />
        <el-option label="访客" value="VISITOR" />
      </el-select>
      <el-select v-model="query.deptId" placeholder="全部部门" clearable filterable
        style="width: 150px" :disabled="query.personType === 'VISITOR'">
        <el-option v-for="d in depts" :key="d.id" :value="d.id" :label="d.name" />
      </el-select>
      <el-select v-model="query.channel" placeholder="全部方式" clearable style="width: 120px">
        <el-option label="行人" value="PEDESTRIAN" />
        <el-option label="车辆" value="VEHICLE" />
      </el-select>
      <el-input v-model="query.keyword" placeholder="姓名 / 人员编号" clearable style="width: 170px"
        @keyup.enter="search" />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-button :icon="Download" :loading="exporting" @click="doExport">导出</el-button>
      <span class="spacer" />
      <span class="text-muted">一人一天一行：最早进入 / 最晚出去，点「详情」看当天全部进出与审批</span>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="day" label="日期" width="110"  sortable />
      <el-table-column prop="personName" label="姓名" width="110"  sortable />
      <el-table-column prop="personNo" label="人员编号" width="140"  sortable />
      <el-table-column label="人员类型" width="95">
        <template #default="{ row }">
          <el-tag :type="row.personType === 'EMPLOYEE' ? 'primary' : 'success'" size="small">
            {{ row.personTypeText }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="部门" width="120">
        <template #default="{ row }">
          <span :class="{ 'text-muted': !row.deptName }">{{ row.deptName || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="firstIn" label="最早进入" width="165"  sortable />
      <el-table-column prop="lastOut" label="最晚出去" width="165"  sortable />
      <el-table-column label="进 / 出" width="90">
        <template #default="{ row }">
          <span :class="{ 'text-muted': row.inCount === 0 }">{{ row.inCount }}</span>
          <span class="text-muted"> / </span>
          <span :class="{ 'text-muted': row.outCount === 0 }">{{ row.outCount }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="channelText" label="通道" width="105"  sortable />
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
        layout="total, prev, pager, next" @current-change="load" />
    </div>
    </template>

    <template v-else>
      <div class="toolbar">
        <el-date-picker v-model="denyRange" type="daterange" value-format="YYYY-MM-DD"
          start-placeholder="开始日期" end-placeholder="结束日期" style="width: 240px" />
        <el-input v-model="denyKeyword" placeholder="姓名 / 人员编号 / 证件号" clearable
          style="width: 200px" @keyup.enter="loadDenied" />
        <el-button type="primary" @click="loadDenied">查询</el-button>
        <el-button :icon="Refresh" @click="loadDenied">刷新</el-button>
        <span class="spacer" />
        <span class="text-muted">设备判为不通过的记录（黑名单拦截、无效卡、过期证等），含拒绝原因</span>
      </div>

      <el-table v-loading="denyLoading" :data="denyRows" border stripe>
        <el-table-column prop="eventTime" label="时间" width="165"  sortable />
        <el-table-column label="进出" width="70">
          <template #default="{ row }">
            <el-tag :type="row.direction === 1 ? 'success' : 'info'" size="small">
              {{ row.directionText }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="identifyTypeText" label="识别方式" width="95"  sortable />
        <el-table-column prop="personName" label="姓名" width="110"  sortable />
        <el-table-column prop="idCardMask" label="证件号" width="150"  sortable />
        <el-table-column prop="personNo" label="人员编号" width="130"  sortable />
        <el-table-column label="原因" min-width="150">
          <template #default="{ row }">
            <el-tag v-if="row.failReason" type="danger" size="small" effect="plain">
              {{ row.failReason }}
            </el-tag>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="deviceSn" label="设备" width="150"  sortable />
        <el-table-column label="照片" width="80">
          <template #default="{ row }">
            <el-button v-if="row.hasMedia" link type="primary" @click="showMedia({ eventId: row.id } as any)">
              查看
            </el-button>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination v-model:current-page="denyPage" v-model:page-size="denySize" :total="denyTotal"
          layout="total, prev, pager, next" @current-change="loadDenied" />
      </div>
    </template>

    <el-drawer v-model="detailVisible" :title="detailTitle" size="820px">
      <template v-if="detail">
        <el-descriptions v-if="detail.personType === 'EMPLOYEE'" :column="3" border size="small">
          <el-descriptions-item label="姓名">{{ detail.personName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="工号">{{ detail.empNo || '-' }}</el-descriptions-item>
          <el-descriptions-item label="人员编号">{{ detail.personNo }}</el-descriptions-item>
          <el-descriptions-item label="部门">
            <el-tag size="small" type="info">{{ detail.deptName || '未分配' }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="职务">{{ detail.title || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.statusText || '-' }}</el-descriptions-item>
          <el-descriptions-item label="手机">{{ detail.phoneMask || '-' }}</el-descriptions-item>
          <el-descriptions-item label="车牌">{{ detail.plateNo || '-' }}</el-descriptions-item>
          <el-descriptions-item label="证件有效期">
            {{ detail.validFrom || '-' }} ~ {{ detail.validTo || '-' }}
          </el-descriptions-item>
        </el-descriptions>

        <el-descriptions v-else :column="2" border size="small">
          <el-descriptions-item label="姓名">{{ detail.personName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="人员编号">{{ detail.personNo }}</el-descriptions-item>
          <el-descriptions-item label="单位">{{ detail.company || '-' }}</el-descriptions-item>
          <el-descriptions-item label="手机">{{ detail.phoneMask || '-' }}</el-descriptions-item>
          <el-descriptions-item label="人脸">
            <el-tag size="small" :type="detail.faceStatus ? 'success' : 'info'">
              {{ detail.faceStatus ? '已采集' : '未采集' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="日期">{{ detail.date }}</el-descriptions-item>
        </el-descriptions>

        <template v-if="detail.personType === 'VISITOR'">
          <div class="section-title">当天申请与审批</div>
          <el-empty v-if="!detail.appointments || detail.appointments.length === 0"
            description="当天没有预约单" :image-size="60" />
          <div v-for="a in detail.appointments || []" :key="a.id" class="appt-card">
            <div class="appt-head">
              <span class="appt-no">{{ a.appointmentNo }}</span>
              <el-tag size="small" :type="statusTagType(a.status)">{{ a.statusText }}</el-tag>
              <span class="spacer" />
              <span class="appt-range">{{ shortTime(a.startTime) }} ~ {{ shortTime(a.endTime) }}</span>
            </div>

            <el-descriptions :column="2" size="small" border>
              <el-descriptions-item label="事由" :span="2">{{ a.purpose || '-' }}</el-descriptions-item>
              <el-descriptions-item label="访客类型">{{ a.visitorType || '-' }}</el-descriptions-item>
              <el-descriptions-item label="被访人">{{ a.visitEmployeeName || '-' }}</el-descriptions-item>
              <el-descriptions-item label="批准门点">{{ a.approvedGates || '-' }}</el-descriptions-item>
              <el-descriptions-item label="车牌">{{ a.plateNo || '-' }}</el-descriptions-item>
              <el-descriptions-item label="随行人员">
                {{ a.mates && a.mates.length ? a.mates.join('、') : '-' }}
              </el-descriptions-item>
              <el-descriptions-item label="提交时间" :span="2">{{ shortTime(a.createdAt) }}</el-descriptions-item>
            </el-descriptions>

            <div class="appt-sub">审批轨迹</div>
            <el-timeline v-if="a.actions.length" class="appt-timeline">
              <el-timeline-item v-for="(act, i) in a.actions" :key="i"
                :timestamp="shortTime(act.at)" placement="top"
                :type="act.action === 2 ? 'danger' : 'success'">
                <div class="act-head">
                  <el-tag size="small" :type="act.action === 2 ? 'danger' : 'success'" effect="plain">
                    {{ act.actionText }}
                  </el-tag>
                  <span>{{ act.operatorRole }} · {{ act.operatorName || '-' }}</span>
                  <span v-if="act.gates.length" class="text-muted">门点：{{ act.gates.join('、') }}</span>
                </div>
                <div v-if="act.comment" class="act-comment">意见：{{ act.comment }}</div>
              </el-timeline-item>
            </el-timeline>
            <div v-else class="text-muted appt-noaction">暂无审批动作</div>
          </div>
        </template>

        <div class="section-title">进出明细（{{ detail.records.length }} 条）</div>
        <el-table :data="detail.records" border size="small">
          <el-table-column prop="eventTime" label="时间" width="160"  sortable />
          <el-table-column label="进出" width="70">
            <template #default="{ row }">
              <el-tag :type="row.direction === 1 ? 'success' : 'info'" size="small">
                {{ row.directionText }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="channelText" label="通行方式" width="90"  sortable />
          <el-table-column prop="methodText" label="识别方式" width="100"  sortable />
          <el-table-column label="点位" min-width="180">
            <template #default="{ row }">
              <span>{{ row.gateName || '-' }}</span>
              <span v-if="row.deviceName" class="text-muted"> · {{ row.deviceName }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="plateNo" label="车牌" width="110"  sortable />
          <el-table-column label="照片" width="85">
            <template #default="{ row }">
              <el-image v-if="row.photoUrl" :src="row.photoUrl" :preview-src-list="[row.photoUrl]"
                fit="cover" style="width: 44px; height: 32px" preview-teleported />
              <el-button v-else-if="row.eventId" link type="primary" @click="showMedia(row)">查看</el-button>
              <span v-else class="text-muted">-</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>

    <el-dialog v-model="mediaVisible" title="通行抓拍" width="720px">
      <div v-if="mediaList.length" class="media-grid">
        <div v-for="m in mediaList" :key="m.id" class="media-item">
          <el-image :src="m.url" :preview-src-list="mediaUrls" fit="contain" preview-teleported />
          <div class="text-muted">{{ m.mediaType === 1 ? '通行照片' : '其他' }}</div>
        </div>
      </div>
      <el-empty v-else description="该记录没有照片" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Download, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  exportTrafficSummary,
  listDepts,
  listEventMedia,
  listEvents,
  trafficDetail,
  trafficSummary
} from '@/api'
import type { DeptView, EventMedia, EventRow, TrafficDetail, TrafficRow } from '@/api/types'

/** 人员通行（汇总）/ 拒绝记录（失败明细） */
const activeTab = ref<'traffic' | 'denied'>('traffic')

const loading = ref(false)
const exporting = ref(false)
const rows = ref<TrafficRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const depts = ref<DeptView[]>([])

/* ---------------- 拒绝记录 ---------------- */
const denyLoading = ref(false)
const denyRows = ref<EventRow[]>([])
const denyTotal = ref(0)
const denyPage = ref(1)
const denySize = ref(20)
const denyRange = ref<[string, string] | null>(null)
const denyKeyword = ref('')

async function loadDenied() {
  denyLoading.value = true
  try {
    const result = await listEvents({
      from: denyRange.value?.[0],
      to: denyRange.value?.[1],
      result: 0,
      keyword: denyKeyword.value || undefined,
      page: denyPage.value,
      size: denySize.value
    })
    denyRows.value = result.list
    denyTotal.value = result.total
  } finally {
    denyLoading.value = false
  }
}

function onTabChange(name: string | number) {
  if (name === 'denied' && denyRows.value.length === 0) {
    loadDenied()
  }
}

const query = reactive<{
  range: [string, string] | null
  personType: string | undefined
  deptId: number | undefined
  channel: string | undefined
  keyword: string
}>({ range: null, personType: undefined, deptId: undefined, channel: undefined, keyword: '' })

const detailVisible = ref(false)
const detail = ref<TrafficDetail | null>(null)
const detailTitle = computed(() =>
  detail.value ? `${detail.value.personName || detail.value.personNo} · ${detail.value.date}` : '进出明细'
)

const mediaVisible = ref(false)
const mediaList = ref<EventMedia[]>([])
const mediaUrls = computed(() => mediaList.value.map((m) => m.url))

async function load() {
  loading.value = true
  try {
    const result = await trafficSummary({
      ...filters(),
      page: page.value,
      size: size.value
    })
    rows.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

/** 列表与导出共用的筛选条件（不含分页） */
function filters() {
  return {
    from: query.range?.[0],
    to: query.range?.[1],
    personType: query.personType,
    deptId: query.personType === 'VISITOR' ? undefined : query.deptId,
    channel: query.channel,
    keyword: query.keyword || undefined
  }
}

async function doExport() {
  exporting.value = true
  try {
    const blob = await exportTrafficSummary(filters())
    const url = URL.createObjectURL(blob as Blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `通行汇总-${new Date().toISOString().slice(0, 10)}.csv`
    link.click()
    URL.revokeObjectURL(url)
    ElMessage.success('已导出')
  } finally {
    exporting.value = false
  }
}

/** 详情里时间压缩展示：与详情同一天只显示时间部分，跨天显示「MM-dd HH:mm」。 */
function shortTime(value?: string) {
  if (!value) return '-'
  const day = detail.value?.date
  if (day && value.startsWith(day + ' ')) return value.slice(day.length + 1)
  if (day && value.startsWith(day)) return value.slice(day.length).trim() || value
  const matched = value.match(/^\d{4}-(\d{2}-\d{2}) (.+)$/)
  return matched ? `${matched[1]} ${matched[2]}` : value
}

/** 预约状态配色：通过/可通行/已签到绿，待审批黄，驳回/撤销红，其余灰。 */
function statusTagType(status: number) {
  if (status === 30 || status === 35 || status === 40) return 'success'
  if (status === 20 || status === 25) return 'warning'
  if (status === 60 || status === 90) return 'danger'
  return 'info'
}

async function openDetail(row: TrafficRow) {
  detail.value = await trafficDetail(row.personNo, row.day)
  detailVisible.value = true
}

async function showMedia(row: { eventId: number }) {
  mediaList.value = await listEventMedia(row.eventId)
  mediaVisible.value = true
}

onMounted(async () => {
  depts.value = await listDepts().catch(() => [])
  await load()
})
</script>

<style scoped>
.section-title {
  margin: 18px 0 8px;
  font-weight: 600;
  font-size: 14px;
}

.appt-card {
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 10px;
}

.appt-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-bottom: 8px;
  margin-bottom: 10px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.appt-no {
  font-weight: 600;
}

.appt-range {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.spacer {
  flex: 1;
}

.appt-sub {
  margin: 14px 0 6px;
  font-size: 13px;
  font-weight: 600;
}

.appt-timeline {
  padding-left: 2px;
  margin-top: 2px;
}

.act-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.act-comment {
  margin-top: 4px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.appt-noaction {
  font-size: 13px;
  padding: 2px 0 4px;
}

.media-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.media-item {
  width: 200px;
  text-align: center;
}

.media-item .el-image {
  width: 200px;
  height: 150px;
  border: 1px solid #e4e7ed;
}
</style>
