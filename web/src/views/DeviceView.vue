<template>
  <div class="page-card">
    <div class="toolbar">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-select v-model="filterGateId" clearable placeholder="全部门点" style="width: 180px"
        @change="load">
        <el-option v-for="g in gates" :key="g.id" :value="g.id" :label="g.gateName" />
      </el-select>
      <span class="spacer" />
      <span class="text-muted">
        名单与记录走「设备心跳」；开门有两种：心跳队列（云端也能用，≤一个心跳周期）与远程直连（秒级，需同网）
      </span>
    </div>

    <el-table v-loading="loading" :data="devices" border stripe>
      <el-table-column prop="deviceName" label="名称" width="140" />
      <el-table-column label="门点" width="120">
        <template #default="{ row }">
          <el-tag v-if="row.gateName === '待分配'" size="small" type="warning">{{ row.gateName }}</el-tag>
          <span v-else>{{ row.gateName || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="directionText" label="方向" width="80" />
      <el-table-column prop="deviceSn" label="SN" width="160" />
      <el-table-column prop="ip" label="IP" width="130" />
      <el-table-column prop="model" label="型号" width="140" />
      <el-table-column label="通道" width="90">
        <template #default="{ row }">
          <el-tag size="small" type="info">{{ row.channel }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.online ? 'success' : 'danger'" size="small">
            {{ row.online ? '在线' : '离线' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="人数" width="100">
        <template #default="{ row }">{{ row.personCount }} / {{ row.capacity || '-' }}</template>
      </el-table-column>
      <el-table-column prop="lastHeartbeat" label="最近心跳" width="160" />
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">档案</el-button>
          <el-button link type="primary" @click="openDoor(row)">开门</el-button>
          <el-button link type="primary" @click="openRemote(row)">远程管理</el-button>
          <el-button link type="primary" @click="openLocal(row)">本地管理</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="editVisible" title="设备档案" width="520px">
      <el-alert v-if="editingRow?.gateName === '待分配'" type="warning" :closable="false" show-icon
        style="margin-bottom: 12px"
        title="这台设备还在「待分配」门点，分配到真实门点后才会收到该门点的访客名单" />
      <el-form :model="editForm" label-width="100px">
        <el-form-item label="设备名称">
          <el-input v-model="editForm.deviceName" maxlength="64" placeholder="如 A 栋东门人脸终端" />
        </el-form-item>
        <el-form-item label="所属门点" required>
          <el-select v-model="editForm.gateId" style="width: 100%">
            <el-option v-for="g in gates" :key="g.id" :value="g.id"
              :label="g.gateName + (g.unassigned ? '（待分配）' : '')" />
          </el-select>
        </el-form-item>
        <el-form-item label="出入口方向">
          <el-radio-group v-model="editForm.direction">
            <el-radio :value="1">入口</el-radio>
            <el-radio :value="0">出口</el-radio>
            <el-radio :value="2">双向（一门一台车闸/行闸时选这个）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="型号">
          <el-input v-model="editForm.model" maxlength="64" placeholder="如 Linux 人脸识别终端" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editForm.remark" type="textarea" :rows="2" maxlength="255" show-word-limit
            placeholder="现场位置说明，便于运维定位" />
        </el-form-item>
        <el-form-item label="SN / MAC / IP">
          <span class="text-muted">{{ editingRow?.deviceSn }} / {{ editingRow?.mac }} /
            {{ editingRow?.ip || '-' }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 远程管理：平台直连设备 9090 调下行接口 -->
    <el-dialog v-model="remoteVisible" :title="`远程管理 · ${remoteRow?.deviceName || ''}`" width="760px">
      <el-alert v-if="remoteInfo && !remoteInfo.reachable" type="warning" :closable="false" show-icon
        style="margin-bottom: 12px" :title="`当前连不上设备：${remoteInfo.unreachableReason || '未知原因'}`">
        <template #default>
          <div class="text-muted" style="margin-top: 4px">
            远程管理要求平台与设备在同一网络（云端部署时连不通是正常的）。
            现场调试请用下面的「本地管理」直接打开设备自己的后台。
          </div>
        </template>
      </el-alert>

      <el-descriptions :column="2" border size="small" style="margin-bottom: 12px">
        <el-descriptions-item label="设备 SN">{{ remoteRow?.deviceSn }}</el-descriptions-item>
        <el-descriptions-item label="设备 IP">{{ remoteInfo?.ip || '-' }}</el-descriptions-item>
        <el-descriptions-item label="本地后台" :span="2">
          <span class="mono">{{ remoteInfo?.localAdminUrl }}</span>
          <el-button link type="primary" style="margin-left: 8px" @click="openLocal(remoteRow!)">
            打开
          </el-button>
          <span class="text-muted" style="margin-left: 8px">账号 admin / 设备密码</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-tabs v-model="remoteTab">
        <el-tab-pane label="常用操作" name="actions">
          <div class="remote-grid">
            <el-button type="primary" :loading="remoteBusy === 'open'" @click="doRemoteOpen">
              立即开闸
            </el-button>
            <el-button :loading="remoteBusy === 'time'" @click="doSyncTime">同步设备时间</el-button>
            <el-button :loading="remoteBusy === 'snapshot'" @click="doSnapshot">抓拍现场</el-button>
            <el-button :loading="remoteBusy === 'reboot'" type="warning" @click="doReboot">重启设备</el-button>
          </div>

          <el-divider content-position="left">屏幕弹框</el-divider>
          <div class="tip-row">
            <el-input v-model="tipForm.msg" maxlength="32" placeholder="提示文字，建议不超过 16 字" />
            <el-select v-model="tipForm.color" style="width: 100px">
              <el-option :value="0" label="绿色" />
              <el-option :value="1" label="红色" />
            </el-select>
            <el-button :loading="remoteBusy === 'tip'" @click="doTip">发送</el-button>
          </div>

          <el-divider content-position="left">闸机常开</el-divider>
          <div class="tip-row">
            <el-input-number v-model="holdMinutes" :min="1" :max="1440" controls-position="right"
              style="width: 130px" />
            <span class="text-muted">分钟</span>
            <el-button :loading="remoteBusy === 'hold'" @click="doGateHold(true)">开启常开</el-button>
            <el-button :loading="remoteBusy === 'hold'" @click="doGateHold(false)">关闭常开</el-button>
          </div>

          <el-divider content-position="left">危险操作</el-divider>
          <el-button type="danger" plain :loading="remoteBusy === 'restore'" @click="doRestore">
            恢复设备出厂参数
          </el-button>
          <span class="text-muted" style="margin-left: 8px">
            会清空设备侧全部参数（含平台地址），需要重新配置设备才能再接入
          </span>
        </el-tab-pane>

        <el-tab-pane label="设备状态" name="status">
          <div class="toolbar">
            <el-button size="small" :icon="Refresh" @click="loadRemoteStatus">重新读取</el-button>
          </div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="应用版本">{{ statusOf('appver') }}</el-descriptions-item>
            <el-descriptions-item label="系统版本">{{ statusOf('sysver') }}</el-descriptions-item>
            <el-descriptions-item label="本地人数">{{ statusOf('curCount') }}</el-descriptions-item>
            <el-descriptions-item label="人脸容量">{{ statusOf('faceCountMax') }}</el-descriptions-item>
            <el-descriptions-item label="MAC">{{ statusOf('macaddr') }}</el-descriptions-item>
            <el-descriptions-item label="硬件版本">{{ statusOf('hardware') }}</el-descriptions-item>
          </el-descriptions>
          <pre class="remote-pre">{{ prettyRunInfo }}</pre>
        </el-tab-pane>

        <el-tab-pane label="设备参数" name="params">
          <div class="toolbar">
            <el-button size="small" :icon="Refresh" @click="loadRemoteParams">读取参数</el-button>
            <span class="text-muted">只读展示；修改请在「本地管理」里操作，避免误改导致设备失联</span>
          </div>
          <pre class="remote-pre">{{ prettyParams }}</pre>
        </el-tab-pane>
      </el-tabs>

      <div v-if="snapshotSrc" style="margin-top: 12px">
        <div class="text-muted" style="margin-bottom: 6px">抓拍结果</div>
        <img :src="snapshotSrc" alt="设备抓拍" style="max-width: 100%; border-radius: 6px" />
      </div>

      <template #footer>
        <el-button @click="remoteVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  deviceRemoteGateHold,
  deviceRemoteInfo,
  deviceRemoteOpen,
  deviceRemoteParameters,
  deviceRemoteReboot,
  deviceRemoteRestore,
  deviceRemoteSnapshot,
  deviceRemoteSyncTime,
  deviceRemoteTip,
  listDevices,
  listGates,
  openDeviceDoor,
  updateDevice,
  type DeviceRemoteInfo
} from '@/api'
import type { DeviceView, GateRow } from '@/api/types'

const route = useRoute()

const loading = ref(false)
const devices = ref<DeviceView[]>([])
const gates = ref<GateRow[]>([])
const filterGateId = ref<number | undefined>(
  route.query.gateId ? Number(route.query.gateId) : undefined
)

const editVisible = ref(false)
const editSaving = ref(false)
const editingRow = ref<DeviceView | null>(null)
const editForm = ref({ deviceName: '', model: '', direction: 1, gateId: 0, remark: '' })

async function load() {
  loading.value = true
  try {
    devices.value = await listDevices(filterGateId.value)
  } finally {
    loading.value = false
  }
}

async function loadGates() {
  gates.value = await listGates()
}

function openEdit(row: DeviceView) {
  editingRow.value = row
  editForm.value = {
    deviceName: row.deviceName,
    model: row.model,
    direction: row.direction,
    gateId: row.gateId,
    remark: row.remark
  }
  editVisible.value = true
}

async function submitEdit() {
  if (!editingRow.value) return
  if (!editForm.value.gateId) {
    ElMessage.warning('请选择所属门点')
    return
  }
  editSaving.value = true
  try {
    await updateDevice(editingRow.value.id, editForm.value)
    ElMessage.success('设备档案已保存')
    editVisible.value = false
    await load()
  } finally {
    editSaving.value = false
  }
}

async function openDoor(row: DeviceView) {
  await ElMessageBox.confirm(
    `确认远程打开「${row.deviceName}」？指令随设备下一次心跳生效（当前心跳间隔 5 秒，最长等一个间隔）。`,
    '远程开门',
    { type: 'warning' }
  )
  await openDeviceDoor(row.id)
  ElMessage.success('开门指令已排队')
}

/* ---------------- 远程管理 / 本地管理 ---------------- */

const remoteVisible = ref(false)
const remoteRow = ref<DeviceView | null>(null)
const remoteInfo = ref<DeviceRemoteInfo | null>(null)
const remoteTab = ref('actions')
const remoteBusy = ref('')
const snapshotSrc = ref('')
const holdMinutes = ref(10)
const tipForm = ref({ msg: '', color: 0 })
const remoteVersion = ref<Record<string, unknown>>({})
const remoteParams = ref<Record<string, unknown>>({})

const statusOf = (key: string) => {
  const value = remoteVersion.value[key]
  return value === undefined || value === null || value === '' ? '-' : String(value)
}
const prettyRunInfo = computed(() =>
  String(remoteVersion.value.runInfo || '（无运行信息）').replace(/\\n/g, '\n')
)
const prettyParams = computed(() =>
  Object.keys(remoteParams.value).length
    ? JSON.stringify(remoteParams.value, null, 2)
    : '（尚未读取，点上面的「读取参数」）'
)

/** 打开远程管理：先探测可达性，连不上也照样进界面（引导走本地管理） */
async function openRemote(row: DeviceView) {
  remoteRow.value = row
  remoteVisible.value = true
  remoteTab.value = 'actions'
  remoteInfo.value = null
  remoteVersion.value = {}
  remoteParams.value = {}
  snapshotSrc.value = ''
  await loadRemoteStatus()
}

/** 本地管理：直接跳设备自己的 Web 后台（同网即可，不依赖平台） */
function openLocal(row: DeviceView) {
  const url = remoteInfo.value?.localAdminUrl
    || `http://${row.ip}:9090/home.html`
  window.open(url, '_blank', 'noopener')
}

async function loadRemoteStatus() {
  if (!remoteRow.value) return
  try {
    remoteInfo.value = await deviceRemoteInfo(remoteRow.value.id)
    remoteVersion.value = (remoteInfo.value.version || {}) as Record<string, unknown>
  } catch (error) {
    remoteVersion.value = {}
    showRemoteError(error)
  }
}

async function loadRemoteParams() {
  if (!remoteRow.value) return
  remoteBusy.value = 'params'
  try {
    remoteParams.value = await deviceRemoteParameters(remoteRow.value.id)
  } catch (error) {
    showRemoteError(error)
  } finally {
    remoteBusy.value = ''
  }
}

async function doRemoteOpen() {
  if (!remoteRow.value) return
  remoteBusy.value = 'open'
  try {
    await deviceRemoteOpen(remoteRow.value.id)
    ElMessage.success('开闸指令已下发（直连，秒级生效）')
  } catch (error) {
    showRemoteError(error)
  } finally {
    remoteBusy.value = ''
  }
}

async function doSyncTime() {
  if (!remoteRow.value) return
  remoteBusy.value = 'time'
  try {
    await deviceRemoteSyncTime(remoteRow.value.id)
    ElMessage.success('设备时间已同步')
    await loadRemoteStatus()
  } catch (error) {
    showRemoteError(error)
  } finally {
    remoteBusy.value = ''
  }
}

async function doSnapshot() {
  if (!remoteRow.value) return
  remoteBusy.value = 'snapshot'
  try {
    const result = await deviceRemoteSnapshot(remoteRow.value.id)
    if (result.image) {
      snapshotSrc.value = result.image.startsWith('data:')
        ? result.image
        : `data:image/jpeg;base64,${result.image}`
      ElMessage.success('已抓拍')
    } else {
      ElMessage.warning('设备没有返回图片，可能不支持抓拍')
    }
  } catch (error) {
    showRemoteError(error)
  } finally {
    remoteBusy.value = ''
  }
}

async function doTip() {
  if (!remoteRow.value) return
  if (!tipForm.value.msg.trim()) {
    ElMessage.warning('请填写提示文字')
    return
  }
  remoteBusy.value = 'tip'
  try {
    await deviceRemoteTip(remoteRow.value.id, {
      msg: tipForm.value.msg.trim(),
      color: tipForm.value.color,
      playId: 0
    })
    ElMessage.success('已下发弹框')
  } catch (error) {
    showRemoteError(error)
  } finally {
    remoteBusy.value = ''
  }
}

async function doGateHold(enabled: boolean) {
  if (!remoteRow.value) return
  if (enabled) {
    await ElMessageBox.confirm(
      `开启后闸机将常开 ${holdMinutes.value} 分钟（无需刷卡/刷证即可通行），确认？`,
      '闸机常开',
      { type: 'warning' }
    )
  }
  remoteBusy.value = 'hold'
  try {
    await deviceRemoteGateHold(remoteRow.value.id, { enabled, minutes: holdMinutes.value })
    ElMessage.success(enabled ? '闸机常开已开启' : '闸机常开已关闭')
  } catch (error) {
    showRemoteError(error)
  } finally {
    remoteBusy.value = ''
  }
}

async function doReboot() {
  if (!remoteRow.value) return
  await ElMessageBox.confirm('重启期间设备会离线约 1 分钟，确认重启？', '重启设备', { type: 'warning' })
  remoteBusy.value = 'reboot'
  try {
    await deviceRemoteReboot(remoteRow.value.id)
    ElMessage.success('重启指令已下发，稍后刷新查看在线状态')
  } catch (error) {
    showRemoteError(error)
  } finally {
    remoteBusy.value = ''
  }
}

async function doRestore() {
  if (!remoteRow.value) return
  await ElMessageBox.confirm(
    '这会清空设备侧全部参数（含第三方平台地址与 Key），恢复后设备将无法再接入平台，必须到现场重新配置。确认继续？',
    '恢复出厂参数',
    { type: 'error', confirmButtonText: '我已确认，恢复出厂' }
  )
  remoteBusy.value = 'restore'
  try {
    await deviceRemoteRestore(remoteRow.value.id)
    ElMessage.success('恢复指令已下发')
  } catch (error) {
    showRemoteError(error)
  } finally {
    remoteBusy.value = ''
  }
}

/** 远程调用失败大多是因为「平台与设备不同网」，把后端原文提示出来，避免被当成平台故障 */
function showRemoteError(error: unknown) {
  const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  ElMessage.error(message || '设备远程调用失败')
}

onMounted(async () => {
  await loadGates()
  await load()
})
</script>
<style scoped>
.remote-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
}

.tip-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.mono {
  font-family: Consolas, Monaco, monospace;
  font-size: 13px;
}

.remote-pre {
  max-height: 320px;
  overflow: auto;
  margin: 0;
  padding: 10px;
  background: #f5f7fa;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>