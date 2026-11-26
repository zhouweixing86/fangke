<template>
  <div class="page-card">
    <div class="toolbar">
      <el-tag :type="status?.connected ? 'success' : 'danger'" size="large">
        {{ status?.connected ? 'MQTT 已连接' : 'MQTT 未连接' }}
      </el-tag>
      <span class="text-muted">broker：{{ status?.brokerUrl || '-' }}</span>
      <span class="text-muted">上行：{{ status?.uploadTopic || '-' }}</span>
      <span class="text-muted">下行：{{ status?.downloadTopicPrefix || '-' }}&lt;设备SN&gt;</span>
      <span class="spacer" />
      <el-button :icon="Refresh" :loading="loading" @click="loadAll">刷新</el-button>
      <el-button :loading="reconnecting" @click="doReconnect">重连</el-button>
      <el-button :disabled="!form.deviceSn" :loading="busy === 'reply'" @click="doEnableReply">
        使能回执
      </el-button>
    </div>

    <el-alert v-if="status && !status.enabled" type="warning" :closable="false" show-icon
      title="MQTT 通道未启用：在服务器 .env 设 VEHICLE_MQTT_ENABLED=true 并重启服务" />
    <el-alert v-else-if="status?.lastError" type="error" :closable="false" show-icon style="margin-bottom: 12px"
      :title="`最近错误：${status.lastError}`" />

    <el-alert v-if="lastFailure" type="error" :closable="true" show-icon style="margin-bottom: 12px"
      :title="`最近一次失败回执：${lastFailure.created_at} ${lastFailure.device_sn || ''} ${lastFailure.message}`">
      <template #default>
        相机拒绝了下行，平台已清掉该设备的对账状态并在下一轮（20 秒内）自动整体重推；
        反复失败通常是固件不支持该命令或名单繁忙（White name list process busy），可稍后点「强制同步」。
      </template>
    </el-alert>

    <el-row :gutter="16">
      <el-col :span="12">
        <el-card shadow="never" header="下发 / 删除车牌">
          <el-form label-width="96px">
            <el-form-item label="车闸设备">
              <el-select v-model="form.deviceSn" filterable allow-create placeholder="选择或输入设备 SN" style="width: 100%">
                <el-option v-for="d in devices" :key="d.deviceSn" :value="d.deviceSn"
                  :label="`${d.deviceName || d.deviceSn}（${d.deviceSn}）`" />
              </el-select>
            </el-form-item>
            <el-form-item label="车牌号">
              <el-input v-model="form.plateNo" placeholder="如 京A12345" maxlength="16" />
            </el-form-item>
            <el-form-item label="生效时间">
              <el-date-picker v-model="form.beginTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss"
                placeholder="默认现在" style="width: 100%" />
            </el-form-item>
            <el-form-item label="到期时间">
              <el-date-picker v-model="form.endTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss"
                placeholder="默认明天" style="width: 100%" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="busy === 'add'" @click="doAdd">下发 / 更新</el-button>
              <el-button type="danger" plain :loading="busy === 'del'" @click="doDelete">删除车牌</el-button>
              <el-button :loading="busy === 'import'" @click="doImport">全量对齐</el-button>
              <el-button type="warning" plain :loading="busy === 'force'" @click="doForceResync">强制同步</el-button>
              <el-button type="danger" plain :loading="busy === 'force-all'" @click="doForceResyncAll">
                全部车闸强制同步
              </el-button>
            </el-form-item>
          </el-form>
          <div class="text-muted">
            相机会把识别到的车牌与本地白名单比对，命中即开门；下发/删除成功后可在右侧日志看到 DOWN 报文。
          </div>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="never" header="原始报文（协议探测）">
          <el-input v-model="raw.payload" type="textarea" :rows="6"
            placeholder='例如 {"command":"AddCarInfo","data":"{\"carInfoList\":[{\"plate\":\"京A12345\",\"type\":1,\"time\":\"2026-12-31\"}]}","requestId":"1","sn":"<SN>","time":"2026-11-26 00:00:00","version":"1.0.1"}' />
          <div class="toolbar" style="margin-top: 8px">
            <span class="text-muted">发到 {{ status?.downloadTopicPrefix }}{{ form.deviceSn || '<SN>' }}</span>
            <span class="spacer" />
            <el-button :loading="busy === 'raw'" @click="doRaw">发送原始报文</el-button>
          </div>
          <el-divider content-position="left">当前模板</el-divider>
          <pre class="mono pre">{{ status?.addTemplate }}</pre>
          <pre class="mono pre">{{ status?.deleteTemplate }}</pre>
        </el-card>
      </el-col>
    </el-row>

    <div class="toolbar" style="margin-top: 16px">
      <span class="section-title">最近上下行</span>
      <span class="spacer" />
      <el-button size="small" :icon="Refresh" @click="loadLogs">刷新日志</el-button>
      <el-button size="small" type="danger" plain @click="doClearLogs">清空</el-button>
    </div>
    <el-table :data="logs" border size="small" max-height="420" v-loading="logsLoading">
      <el-table-column prop="created_at" label="时间" width="180"  sortable />
      <el-table-column label="方向" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="row.direction === 'UP' ? 'success' : 'primary'">
            {{ row.direction === 'UP' ? '上行' : '下行' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="device_sn" label="设备 SN" width="170"  sortable />
      <el-table-column prop="topic" label="主题" width="200" show-overflow-tooltip  sortable />
      <el-table-column prop="message" label="结果" width="240" show-overflow-tooltip  sortable />
      <el-table-column prop="payload" label="报文" show-overflow-tooltip  sortable />
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  listDevices,
  vehicleMqttAddPlate,
  vehicleMqttClearLogs,
  vehicleMqttDeletePlate,
  vehicleMqttEnableReply,
  vehicleMqttImport,
  vehicleMqttForceResync,
  vehicleMqttForceResyncAll,
  vehicleMqttLastFailure,
  vehicleMqttLogs,
  vehicleMqttRaw,
  vehicleMqttReconnect,
  vehicleMqttStatus
} from '@/api'
import type { DeviceView, VehicleMqttLogRow, VehicleMqttStatus } from '@/api/types'

const status = ref<VehicleMqttStatus | null>(null)
const devices = ref<DeviceView[]>([])
const logs = ref<VehicleMqttLogRow[]>([])
const loading = ref(false)
const logsLoading = ref(false)
const reconnecting = ref(false)
const busy = ref('')
const lastFailure = ref<VehicleMqttLogRow | null>(null)

const form = ref({ deviceSn: '', plateNo: '', beginTime: '', endTime: '' })
const raw = ref({ payload: '' })

function errorText(error: unknown): string {
  return (error as { response?: { data?: { message?: string } } })?.response?.data?.message || '操作失败'
}

async function loadAll() {
  loading.value = true
  try {
    status.value = await vehicleMqttStatus()
    devices.value = await listDevices(undefined, 'VEHICLE')
    if (!form.value.deviceSn && devices.value.length) {
      form.value.deviceSn = devices.value[0].deviceSn
    }
    await Promise.all([loadLogs(), loadLastFailure()])
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    loading.value = false
  }
}

/** 最近一次失败回执：相机拒绝下行时直接提示，不用去日志里翻 */
async function loadLastFailure() {
  try {
    const result = await vehicleMqttLastFailure()
    lastFailure.value = result.failure
  } catch {
    lastFailure.value = null
  }
}

async function loadLogs() {
  logsLoading.value = true
  try {
    logs.value = await vehicleMqttLogs(80)
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    logsLoading.value = false
  }
}

async function doReconnect() {
  reconnecting.value = true
  try {
    status.value = await vehicleMqttReconnect()
    ElMessage.success(status.value.connected ? 'MQTT 已连接' : '重连完成，但仍未连接')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    reconnecting.value = false
  }
}

async function doEnableReply() {
  if (!form.value.deviceSn) {
    ElMessage.warning('请先选择车闸设备')
    return
  }
  busy.value = 'reply'
  try {
    await vehicleMqttEnableReply(form.value.deviceSn)
    ElMessage.success('已下发「使能回执」，之后每条下行相机都会回执 errCode（见下方日志）')
    await loadLogs()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    busy.value = ''
  }
}

async function doAdd() {
  if (!form.value.deviceSn || !form.value.plateNo.trim()) {
    ElMessage.warning('请选择设备并填写车牌号')
    return
  }  busy.value = 'add'
  try {
    await vehicleMqttAddPlate({
      deviceSn: form.value.deviceSn,
      plateNo: form.value.plateNo.trim(),
      beginTime: form.value.beginTime || undefined,
      endTime: form.value.endTime || undefined
    })
    ElMessage.success('已下发（相机收到后识别到该车牌即开门）')
    await loadLogs()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    busy.value = ''
  }
}

async function doDelete() {
  if (!form.value.deviceSn || !form.value.plateNo.trim()) {
    ElMessage.warning('请选择设备并填写车牌号')
    return
  }
  await ElMessageBox.confirm(`从设备「${form.value.deviceSn}」删除车牌 ${form.value.plateNo}？`, '删除车牌', {
    type: 'warning'
  })
  busy.value = 'del'
  try {
    await vehicleMqttDeletePlate(form.value.deviceSn, form.value.plateNo.trim())
    ElMessage.success('删除指令已下发')
    await loadLogs()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    busy.value = ''
  }
}

async function doImport() {
  await ElMessageBox.confirm('把平台当前的员工 + 当天已授权访客车牌全量推给所有车闸相机？', '全量对齐', {
    type: 'warning'
  })
  busy.value = 'import'
  try {
    const result = await vehicleMqttImport()
    ElMessage.success(`已推送 ${result.plateTotal} 条车牌到 ${result.devices.length} 台设备`)
    await loadLogs()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    busy.value = ''
  }
}

async function doForceResync() {
  if (!form.value.deviceSn) {
    ElMessage.warning('请先选择要强制同步的车闸设备')
    return
  }
  await ElMessageBox.confirm(
    `⚠️ 慎用：这是「清空重建」，不是普通同步\n\n`
    + `1. 会先让相机清空本地名单 + 放行规则，再按平台数据全量重推；\n`
    + `2. 清空后相机会有几秒钟没有名单（期间车到闸口不会开闸）；\n`
    + `3. 相机离线时下发不出去，会直接报错；\n`
    + `4. 只有「怀疑相机名单被现场人工改过 / 和平台对不上」时才需要用它，\n`
    + `   日常新增/删除车牌不用点（20 秒定时对账自动处理）。\n\n`
    + `设备：${form.value.deviceSn}\n确定现在执行吗？`,
    '强制同步（清空重推）· 慎用',
    { type: 'warning', confirmButtonText: '我确认，立即执行', cancelButtonText: '取消' }
  )
  busy.value = 'force'
  try {
    const result = await vehicleMqttForceResync(form.value.deviceSn)
    const repushed = (result.repushed || {}) as Record<string, unknown>
    ElMessage.success(
      `已清空并重推：新增 ${repushed.added ?? 0} / 更新 ${repushed.updated ?? 0} / 删除 ${repushed.removed ?? 0}`
    )
    await loadLogs()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    busy.value = ''
  }
}

/** 全部车闸强制同步：逐台清空重推，离线的跳过（一台失败不影响其它台） */
async function doForceResyncAll() {
  if (!devices.value.length) {
    ElMessage.warning('还没有登记任何车闸相机')
    return
  }
  await ElMessageBox.confirm(
    `⚠️ 慎用：会对全部 ${devices.value.length} 台车闸相机执行「清空重建」\n\n`
    + `1. 每台相机先清空本地名单 + 放行规则，再按平台数据全量重推；\n`
    + `2. 清空后相机会有几秒钟没有名单（期间车到闸口不会开闸）；\n`
    + `3. 离线的相机会跳过并报原因，不影响在线的那几台；\n`
    + `4. 只在「怀疑多台相机名单都被现场改过」时用，日常新增/删除车牌不用点。\n\n`
    + `确定现在执行吗？`,
    '全部车闸强制同步 · 慎用',
    { type: 'warning', confirmButtonText: '我确认，立即执行', cancelButtonText: '取消' }
  )
  busy.value = 'force-all'
  try {
    const result = await vehicleMqttForceResyncAll()
    const skipped = result.devices.filter((d) => d.status !== 'CLEARED')
    ElMessage.success(`已处理 ${result.deviceTotal} 台：成功 ${result.succeeded} 台，跳过 ${skipped.length} 台`)
    if (skipped.length) {
      ElMessageBox.alert(
        skipped.map((d) => `${d.deviceSn}：${d.error || '已跳过'}`).join('；'),
        '被跳过的相机',
        { confirmButtonText: '知道了' }
      )
    }
    await Promise.all([loadLogs(), loadLastFailure()])
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    busy.value = ''
  }
}

async function doRaw() {
  if (!form.value.deviceSn || !raw.value.payload.trim()) {
    ElMessage.warning('请选择设备并填写报文')
    return
  }
  busy.value = 'raw'
  try {
    await vehicleMqttRaw({ deviceSn: form.value.deviceSn, payload: raw.value.payload.trim() })
    ElMessage.success('原始报文已发送')
    await loadLogs()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    busy.value = ''
  }
}

async function doClearLogs() {
  await ElMessageBox.confirm('清空最近上下行日志？', '清空', { type: 'warning' })
  await vehicleMqttClearLogs()
  logs.value = []
}

onMounted(loadAll)
</script>

<style scoped>
.section-title {
  font-weight: 600;
}

.mono {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}

.pre {
  background: #f5f7fa;
  padding: 8px;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0 0 6px;
}
</style>
