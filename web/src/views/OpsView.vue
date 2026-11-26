<template>
  <div class="page-card">
    <el-tabs v-model="tab">
      <el-tab-pane label="定时任务" name="job">
        <div class="toolbar">
          <el-select v-model="jobCode" placeholder="全部任务" clearable style="width: 190px">
            <el-option v-for="c in codes" :key="c.code" :label="c.label" :value="c.code" />
          </el-select>
          <el-button type="primary" @click="loadJobs">查询</el-button>
          <el-button :icon="Refresh" @click="loadJobs">刷新</el-button>
          <span class="spacer" />
          <el-select v-model="runCode" placeholder="选择要执行的任务" style="width: 190px">
            <el-option v-for="c in codes" :key="c.code" :label="c.label" :value="c.code" />
          </el-select>
          <el-button type="warning" :loading="running" @click="doRun">立即执行</el-button>
        </div>

        <el-table v-loading="jobLoading" :data="jobs" border stripe>
          <el-table-column label="任务" width="160">
            <template #default="{ row }">{{ codeLabel(row.jobCode) }}</template>
          </el-table-column>
          <el-table-column prop="startedAt" label="开始时间" width="170"  sortable />
          <el-table-column prop="finishedAt" label="结束时间" width="170"  sortable />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : row.status === 2 ? 'danger' : 'warning'"
                size="small">{{ row.statusText }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="affected" label="处理条数" width="100"  sortable />
          <el-table-column prop="message" label="说明" min-width="220"  sortable />
        </el-table>

        <div class="pager">
          <el-pagination v-model:current-page="jobPage" v-model:page-size="jobSize" :total="jobTotal"
            layout="total, prev, pager, next" @current-change="loadJobs" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="通知记录" name="notify">
        <div class="toolbar">
          <el-input v-model="notifyQuery.templateCode" placeholder="模板编码，如 APPROVAL_TODO"
            clearable style="width: 240px" @keyup.enter="loadNotify" />
          <el-select v-model="notifyQuery.receiverType" placeholder="接收方" clearable style="width: 120px">
            <el-option label="员工" :value="1" />
            <el-option label="访客" :value="2" />
          </el-select>
          <el-select v-model="notifyQuery.status" placeholder="状态" clearable style="width: 120px">
            <el-option label="已发" :value="1" />
            <el-option label="失败" :value="2" />
            <el-option label="待发" :value="0" />
          </el-select>
          <el-button type="primary" @click="loadNotify">查询</el-button>
          <el-button :icon="Refresh" @click="loadNotify">刷新</el-button>
          <span class="spacer" />
          <span class="text-muted">移动端只展示站内消息，短信记录仅在此排查</span>
        </div>

        <el-table v-loading="notifyLoading" :data="notifications" border stripe>
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="detail">
                <div><b>内容：</b>{{ row.content }}</div>
                <div v-if="row.errorMsg"><b>失败原因：</b>{{ row.errorMsg }}</div>
                <div><b>业务单号：</b>{{ row.bizNo || '-' }}</div>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="时间" width="170"  sortable />
          <el-table-column label="接收方" width="120">
            <template #default="{ row }">{{ row.receiverTypeText }}#{{ row.receiverId }}</template>
          </el-table-column>
          <el-table-column prop="channelText" label="渠道" width="80"  sortable />
          <el-table-column prop="templateCode" label="模板" width="180"  sortable />
          <el-table-column prop="title" label="标题" width="160"  sortable />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : row.status === 2 ? 'danger' : 'warning'"
                size="small">{{ row.statusText }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="已读" width="80">
            <template #default="{ row }">
              <span class="text-muted">{{ row.read ? '已读' : '未读' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="retryCount" label="重试" width="80"  sortable />
        </el-table>

        <div class="pager">
          <el-pagination v-model:current-page="notifyPage" v-model:page-size="notifySize"
            :total="notifyTotal" layout="total, prev, pager, next" @current-change="loadNotify" />
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { listJobCodes, listJobLog, listNotifications, runJob } from '@/api'
import type { JobCodeOption, JobRunLogRow, NotificationRow } from '@/api/types'

const tab = ref<'job' | 'notify'>('job')

const codes = ref<JobCodeOption[]>([])
const jobs = ref<JobRunLogRow[]>([])
const jobCode = ref('')
const runCode = ref('')
const jobPage = ref(1)
const jobSize = ref(20)
const jobTotal = ref(0)
const jobLoading = ref(false)
const running = ref(false)

const notifications = ref<NotificationRow[]>([])
const notifyQuery = reactive<{ templateCode: string; receiverType: number | undefined; status: number | undefined }>({
  templateCode: '',
  receiverType: undefined,
  status: undefined
})
const notifyPage = ref(1)
const notifySize = ref(20)
const notifyTotal = ref(0)
const notifyLoading = ref(false)

function codeLabel(code: string) {
  return codes.value.find((c) => c.code === code)?.label ?? code
}

async function loadJobs() {
  jobLoading.value = true
  try {
    const result = await listJobLog({
      jobCode: jobCode.value || undefined,
      page: jobPage.value,
      size: jobSize.value
    })
    jobs.value = result.list
    jobTotal.value = result.total
  } finally {
    jobLoading.value = false
  }
}

async function doRun() {
  if (!runCode.value) {
    ElMessage.warning('请选择要执行的任务')
    return
  }
  await ElMessageBox.confirm(
    `立即执行「${codeLabel(runCode.value)}」？该操作会真实处理数据（过期/签离/催办等）。`,
    '手动执行任务',
    { type: 'warning' }
  )
  running.value = true
  try {
    const result = await runJob(runCode.value)
    ElMessage.success(`已执行，处理 ${result.affected} 条`)
    await loadJobs()
  } finally {
    running.value = false
  }
}

async function loadNotify() {
  notifyLoading.value = true
  try {
    const result = await listNotifications({
      templateCode: notifyQuery.templateCode || undefined,
      receiverType: notifyQuery.receiverType,
      status: notifyQuery.status,
      page: notifyPage.value,
      size: notifySize.value
    })
    notifications.value = result.list
    notifyTotal.value = result.total
  } finally {
    notifyLoading.value = false
  }
}

onMounted(async () => {
  codes.value = await listJobCodes()
  runCode.value = codes.value[0]?.code ?? ''
  await Promise.all([loadJobs(), loadNotify()])
})
</script>

<style scoped>
.detail {
  padding: 8px 24px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--el-text-color-regular);
}
</style>
