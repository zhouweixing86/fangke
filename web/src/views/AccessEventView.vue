<template>
  <div class="page-card">
    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="姓名 / 证件号后四位 / 设备号" clearable style="width: 240px"
        @keyup.enter="search" />
      <el-select v-model="query.result" placeholder="通行结果" clearable style="width: 120px">
        <el-option label="通过" :value="1" />
        <el-option label="失败" :value="0" />
      </el-select>
      <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期"
        end-placeholder="结束日期" style="width: 260px" />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <span class="spacer" />
      <span class="text-muted">默认查询近 7 天</span>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="eventTime" label="通行时间" width="170" />
      <el-table-column prop="deviceSn" label="设备" width="150" />
      <el-table-column prop="directionText" label="方向" width="70" />
      <el-table-column prop="identifyTypeText" label="识别方式" width="100" />
      <el-table-column prop="personNo" label="人员编号" width="120" />
      <el-table-column prop="personName" label="姓名" width="110" />
      <el-table-column label="结果" width="90">
        <template #default="{ row }">
          <el-tag :type="row.resultStatus === 1 ? 'success' : 'danger'" size="small">
            {{ row.resultText }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sourceText" label="来源" width="110" />
      <el-table-column label="抓拍" width="100">
        <template #default="{ row }">
          <el-button v-if="row.hasMedia" link type="primary" @click="showMedia(row)">查看照片</el-button>
          <span v-else class="text-muted">无</span>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
        layout="total, prev, pager, next" @current-change="load" />
    </div>

    <el-dialog v-model="mediaVisible" title="通行抓拍" width="720px">
      <div v-if="mediaList.length" class="media-grid">
        <div v-for="m in mediaList" :key="m.id" class="media-item">
          <el-image :src="m.url" :preview-src-list="mediaUrls" fit="contain" preview-teleported />
          <div class="text-muted">{{ m.mediaType === 1 ? '通行照片' : '其他' }}</div>
        </div>
      </div>
      <el-empty v-else description="照片已过保留期或未落库" />
      <el-alert type="info" :closable="false" show-icon style="margin-top: 12px"
        title="照片通过 MinIO 预签名 URL 直出，有效期 5 分钟" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { listEventMedia, listEvents } from '@/api'
import type { EventMedia, EventRow } from '@/api/types'

const rows = ref<EventRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const dateRange = ref<[string, string] | null>(defaultRange())
const query = reactive<{ keyword: string; result: number | undefined }>({ keyword: '', result: undefined })

const mediaVisible = ref(false)
const mediaList = ref<EventMedia[]>([])
const mediaUrls = computed(() => mediaList.value.map((m) => m.url))

function defaultRange(): [string, string] {
  const end = new Date()
  const start = new Date(end.getTime() - 6 * 24 * 3600 * 1000)
  const fmt = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  return [fmt(start), fmt(end)]
}

async function load() {
  loading.value = true
  try {
    const result = await listEvents({
      keyword: query.keyword || undefined,
      result: query.result,
      from: dateRange.value?.[0],
      to: dateRange.value?.[1],
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

async function showMedia(row: EventRow) {
  mediaList.value = await listEventMedia(row.id)
  mediaVisible.value = true
}

onMounted(load)
</script>

<style scoped>
.media-grid {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}

.media-item {
  width: 200px;
  text-align: center;
}

.media-item .el-image {
  width: 200px;
  height: 200px;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
}
</style>
