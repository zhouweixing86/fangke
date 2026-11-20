<template>
  <div class="page-card">
    <div class="toolbar">
      <el-date-picker v-model="query.range" type="daterange" value-format="YYYY-MM-DD"
        start-placeholder="开始日期" end-placeholder="结束日期" style="width: 240px" />
      <el-select v-model="query.personType" placeholder="全部人员" clearable style="width: 130px">
        <el-option label="员工" value="EMPLOYEE" />
        <el-option label="访客" value="VISITOR" />
      </el-select>
      <el-select v-model="query.channel" placeholder="全部方式" clearable style="width: 130px">
        <el-option label="行人" value="PEDESTRIAN" />
        <el-option label="车辆" value="VEHICLE" />
      </el-select>
      <el-input v-model="query.keyword" placeholder="姓名 / 人员编号" clearable style="width: 180px"
        @keyup.enter="search" />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <span class="spacer" />
      <span class="text-muted">一人一天一行：最早进入 / 最晚出去，点「详情」看当天全部进出</span>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="day" label="日期" width="110" />
      <el-table-column prop="personName" label="姓名" width="110" />
      <el-table-column prop="personNo" label="人员编号" width="150" />
      <el-table-column label="人员类型" width="100">
        <template #default="{ row }">
          <el-tag :type="row.personType === 'EMPLOYEE' ? 'primary' : 'success'" size="small">
            {{ row.personTypeText }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="firstIn" label="最早进入" width="170" />
      <el-table-column prop="lastOut" label="最晚出去" width="170" />
      <el-table-column label="进 / 出" width="100">
        <template #default="{ row }">
          <span :class="{ 'text-muted': row.inCount === 0 }">{{ row.inCount }}</span>
          <span class="text-muted"> / </span>
          <span :class="{ 'text-muted': row.outCount === 0 }">{{ row.outCount }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="channelText" label="通道" width="110" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
        layout="total, prev, pager, next" @current-change="load" />
    </div>

    <el-drawer v-model="detailVisible" :title="detailTitle" size="720px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 12px">
          <el-descriptions-item label="姓名">{{ detail.personName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="人员编号">{{ detail.personNo }}</el-descriptions-item>
          <el-descriptions-item label="日期">{{ detail.date }}</el-descriptions-item>
          <el-descriptions-item label="记录数">{{ detail.records.length }}</el-descriptions-item>
        </el-descriptions>

        <el-table :data="detail.records" border size="small">
          <el-table-column prop="eventTime" label="时间" width="160" />
          <el-table-column label="进出" width="70">
            <template #default="{ row }">
              <el-tag :type="row.direction === 1 ? 'success' : 'info'" size="small">
                {{ row.directionText }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="channelText" label="通行方式" width="90" />
          <el-table-column prop="methodText" label="识别方式" width="100" />
          <el-table-column label="点位" min-width="180">
            <template #default="{ row }">
              <span>{{ row.gateName || '-' }}</span>
              <span v-if="row.deviceName" class="text-muted"> · {{ row.deviceName }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="plateNo" label="车牌" width="110" />
          <el-table-column label="照片" width="90">
            <template #default="{ row }">
              <el-button v-if="row.eventId" link type="primary" @click="showMedia(row)">查看</el-button>
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
import { Refresh } from '@element-plus/icons-vue'
import { listEventMedia, trafficDetail, trafficSummary } from '@/api'
import type { EventMedia, TrafficDetail, TrafficRow } from '@/api/types'

const loading = ref(false)
const rows = ref<TrafficRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

const query = reactive<{
  range: [string, string] | null
  personType: string | undefined
  channel: string | undefined
  keyword: string
}>({ range: null, personType: undefined, channel: undefined, keyword: '' })

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
      from: query.range?.[0],
      to: query.range?.[1],
      personType: query.personType,
      channel: query.channel,
      keyword: query.keyword || undefined,
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

async function openDetail(row: TrafficRow) {
  detail.value = await trafficDetail(row.personNo, row.day)
  detailVisible.value = true
}

async function showMedia(row: { eventId: number }) {
  mediaList.value = await listEventMedia(row.eventId)
  mediaVisible.value = true
}

onMounted(load)
</script>

<style scoped>
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
