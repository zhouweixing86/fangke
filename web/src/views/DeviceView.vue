<template>
  <div class="page-card">
    <div class="toolbar">
      <el-radio-group v-model="deviceKind" @change="load">
        <el-radio-button value="PULL">人行终端</el-radio-button>
        <el-radio-button value="VEHICLE">车闸相机</el-radio-button>
        <el-radio-button value="">全部</el-radio-button>
      </el-radio-group>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-select v-model="filterGateId" clearable placeholder="全部门点" style="width: 180px"
        @change="load">
        <el-option v-for="g in gates" :key="g.id" :value="g.id" :label="g.gateName" />
      </el-select>
      <el-button v-if="deviceKind === 'VEHICLE'" type="primary" plain @click="openPlates()">
        车牌名单 / 下发
      </el-button>
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
      <el-table-column prop="ip" label="内网 IP" width="130" />
      <el-table-column prop="model" label="型号" width="140" />
      <el-table-column label="通道" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="row.channel === 'VEHICLE' ? 'warning' : 'info'">
            {{ row.channelText || row.channel }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.online ? 'success' : 'danger'" size="small">
            {{ row.online ? '在线' : '离线' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="人数" width="120">
        <template #default="{ row }">
          <el-tooltip v-if="row.channel !== 'VEHICLE'"
                      :content="`设备自报 ${row.personCount} 人；平台应下发 ${row.expectedPersonCount} 人`"
                      placement="top">
            <span>{{ row.expectedPersonCount }} / {{ row.capacity || '-' }}</span>
          </el-tooltip>
          <span v-else>{{ row.personCount }} / {{ row.capacity || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="lastHeartbeat" label="最近心跳" width="160" />
      <el-table-column label="操作" width="320" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">档案</el-button>
          <template v-if="row.channel === 'VEHICLE'">
            <el-button link type="primary" @click="openVehicleGate(row)">开闸</el-button>
            <el-button link type="primary" @click="openPlates(row)">车牌名单</el-button>
          </template>
          <template v-else>
            <el-button link type="primary" @click="openDoor(row)">开门</el-button>
            <el-button link type="primary" @click="openRemote(row)">远程管理</el-button>
            <el-button link type="primary" @click="openLocal(row)">本地管理</el-button>
          </template>
          <el-popconfirm title="删除这台设备的档案？历史通行记录会保留" confirm-button-text="删除"
            @confirm="doDelete(row)">
            <template #reference>
              <el-button link type="danger">删除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="editVisible" title="设备档案" width="520px">
      <el-alert v-if="editingRow?.gateName === '待分配'" type="warning" :closable="false" show-icon
        style="margin-bottom: 12px"
        title="这台设备还在「待分配」门点，分配到真实门点后才会收到该门点的访客名单" />
      <el-form :model="editForm" label-width="100px">
        <el-form-item label="设备类型">
          <el-radio-group v-model="editForm.channel">
            <el-radio value="PULL">人行终端（人脸/刷证/扫码）</el-radio>
            <el-radio value="VEHICLE">车闸相机（车牌识别）</el-radio>
          </el-radio-group>
        </el-form-item>
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
        <el-form-item label="内网 IP">
          <el-input v-model="editForm.ip" maxlength="64" placeholder="如 192.168.0.103（「本地管理」用它打开设备后台）" />
        </el-form-item>
        <el-form-item label="SN / MAC">
          <span class="text-muted">{{ editingRow?.deviceSn }} / {{ editingRow?.mac }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 远程管理：默认走「心跳下发」（云端可用）；直连 9090 只用于同网现场调试 -->
    <el-dialog v-model="remoteVisible" :title="`远程管理 · ${remoteRow?.deviceName || ''}`" width="820px">
      <el-descriptions :column="4" border size="small" style="margin-bottom: 12px">
        <el-descriptions-item label="设备 SN">{{ remoteRow?.deviceSn }}</el-descriptions-item>
        <el-descriptions-item label="内网 IP">{{ remoteRow?.ip || '-' }}</el-descriptions-item>
        <el-descriptions-item label="心跳">
          <el-tag size="small" :type="cmdStatus?.online ? 'success' : 'danger'">
            {{ cmdStatus?.online ? '在线' : '无心跳' }}
          </el-tag>
          <span class="text-muted" style="margin-left: 6px">{{ cmdStatus?.lastHeartbeat || '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="待执行指令">{{ cmdStatus?.pending ?? 0 }}</el-descriptions-item>
      </el-descriptions>

      <el-alert v-if="cmdStatus && !cmdStatus.online" type="warning" :closable="false" show-icon
        style="margin-bottom: 12px"
        title="设备当前没有心跳：心跳下发的指令会留在队列里，设备联网后自动取走执行。" />

      <el-tabs v-model="remoteTab">
        <el-tab-pane label="心跳下发" name="queue">
          <div class="text-muted" style="margin-bottom: 8px">
            指令入队 → 设备下一次心跳（≤{{ cmdStatus?.heartSeconds || 5 }} 秒）取走执行。
            这条通道不要求平台能连到设备，云端部署、设备在客户内网都能用。
          </div>
          <div class="remote-grid">
            <el-button type="primary" :loading="cmdBusy === 'open'" @click="doCmdOpen">远程开门</el-button>
            <el-button :loading="cmdBusy === 'time'" @click="doCmdSyncTime">同步设备时间</el-button>
            <el-button :loading="cmdBusy === 'params'" @click="doCmdSyncParams">下发参数</el-button>
            <el-button :loading="cmdBusy === 'qr'" @click="doCmdQrConfig">下发二维码核验地址</el-button>
          </div>

          <el-divider content-position="left">设备开关（平台期望值）</el-divider>
          <div v-for="(label, key) in switchDefs" :key="key" class="switch-row">
            <el-switch :model-value="Boolean(overrideOf(key))" :loading="cmdBusy === 'switch'"
              @change="(v: any) => toggleSwitch(key, Boolean(v))" />
            <span class="switch-label">{{ label }}</span>
            <span class="text-muted mono">{{ key }}</span>
            <span v-if="!hasOverride(key)" class="text-muted">（未设置，跟随平台默认）</span>
          </div>
          <div class="text-muted" style="margin-top: 4px">
            期望值存在平台上，设备每次来拉参数都会套用；设备重启或恢复出厂后重新拉参数依然生效。
            访客用二维码进闸需要「摄像头扫码」为开启状态。
          </div>

          <el-divider content-position="left">二维码核验接线</el-divider>
          <div class="tip-row">
            <el-button :loading="cmdBusy === 'qr'" @click="doCmdQrConfig">排队下发 thirdQrUrl</el-button>
            <span class="text-muted mono">{{ cmdStatus?.qrPushUrl || '（未配置平台公网地址）' }}</span>
          </div>
          <div class="text-muted" style="margin-top: 4px">
            未接线时设备扫到平台码只会在本地判定，一律提示「二维码未授权」。
          </div>

          <el-divider content-position="left">最近下发</el-divider>
          <el-table :data="cmdStatus?.commands || []" size="small" border max-height="220">
            <el-table-column prop="createdAt" label="入队时间" width="160" />
            <el-table-column prop="cmdText" label="指令" width="110" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 2 ? 'success'
                  : row.status === 3 || row.status === 4 ? 'danger' : 'info'">
                  {{ row.statusText }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="retryCount" label="重试" width="70" />
            <el-table-column prop="errorMsg" label="错误" show-overflow-tooltip />
          </el-table>
          <div class="toolbar" style="margin-top: 8px">
            <el-button size="small" :icon="Refresh" @click="loadCmdStatus">刷新队列</el-button>
            <span class="spacer" />
            <span class="text-muted mono">本地后台 {{ remoteInfo?.localAdminUrl || '（先填设备内网 IP）' }}</span>
            <el-button link type="primary" @click="openLocal(remoteRow!)">打开本地管理</el-button>
          </div>
        </el-tab-pane>

        <el-tab-pane label="直连调试（需同网）" name="direct">
          <el-alert v-if="remoteInfo && !remoteInfo.reachable" type="warning" :closable="false" show-icon
            style="margin-bottom: 12px" :title="`当前连不上设备：${remoteInfo.unreachableReason || '未知原因'}`">
            <template #default>
              <div class="text-muted" style="margin-top: 4px">
                直连是「平台主动 POST 设备 9090」，要求平台与设备同网——云端部署必然不通，这是网络位置决定的。
                云端可用的功能都在「心跳下发」页签。
              </div>
            </template>
          </el-alert>
          <div class="remote-grid">
            <el-button :loading="remoteBusy === 'open'" @click="doRemoteOpen">立即开闸（直连）</el-button>
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

          <el-divider content-position="left">名单清理</el-divider>
          <div class="tip-row">
            <el-input v-model="cleanIdno" placeholder="身份证号 / 人员编号" maxlength="18" style="width: 230px" />
            <el-select v-model="cleanType" style="width: 110px">
              <el-option value="white" label="白名单" />
              <el-option value="black" label="黑名单" />
            </el-select>
            <el-button :loading="remoteBusy === 'clean'" @click="doDeletePerson">从设备删除</el-button>
          </div>
          <div class="text-muted" style="margin-top: 4px">
            用于清理「平台已删、设备还留着」的孤儿名单。正常增删请走员工停用/黑名单失效。
          </div>

          <el-divider content-position="left">危险操作</el-divider>
          <el-button type="danger" plain :loading="remoteBusy === 'restore'" @click="doRestore">
            恢复设备出厂参数
          </el-button>
          <span class="text-muted" style="margin-left: 8px">
            会清空设备侧全部参数（含平台地址），需要重新配置设备才能再接入
          </span>

          <el-alert type="info" :closable="false" show-icon style="margin-top: 12px"
            title="这一页的能力设备没有上行通道，只能直连 9090；云端部署请到现场用「本地管理」。开关、二维码接线、校时、开门都在「心跳下发」页签。" />
        </el-tab-pane>

        <el-tab-pane label="设备状态（直连）" name="status">
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

        <el-tab-pane label="设备参数（直连）" name="params">
          <div class="toolbar">
            <el-button size="small" :icon="Refresh" @click="loadRemoteParams">读取参数</el-button>
            <span class="text-muted">只读展示；改开关请回「心跳下发」，其余参数请在「本地管理」里操作</span>
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

    <!-- 车闸相机：车牌名单（来源=员工档案 + 访客预约，docs/18） -->
    <el-dialog v-model="platesVisible" :title="`车牌名单 · ${platesRow?.deviceName || '全部车闸'}`" width="720px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px"
        title="车牌不用单独维护：员工档案/访客预约填的车牌会汇总到这里，平台每 20 秒自动对账并通过 MQTT 下发到车闸相机（新增/更新/删除）。下面的按钮只是立即触发一次。" />
      <div class="toolbar">
        <el-button :loading="platesLoading" :icon="Refresh" @click="loadPlates">刷新清单</el-button>
        <el-button type="primary" :loading="platesSyncing" @click="doSyncPlates">下发到车闸相机</el-button>
        <span class="spacer" />
        <span class="text-muted">
          员工 {{ plates?.summary.employee || 0 }} 辆 · 访客 {{ plates?.summary.visitor || 0 }} 辆 ·
          共 {{ plates?.summary.total || 0 }} 辆
        </span>
      </div>
      <el-alert v-if="syncResult" :type="syncAlertType" :closable="false" show-icon
        style="margin-bottom: 8px" :title="syncAlertText" />
      <div v-if="syncResult?.cloud?.errors?.length" class="text-muted" style="margin-bottom: 8px">
        <div v-for="(err, idx) in syncResult.cloud.errors" :key="idx" class="mono">{{ err }}</div>
      </div>
      <el-table :data="plates?.plates || []" border size="small" max-height="360">
        <el-table-column prop="plateNo" label="车牌" width="120" />
        <el-table-column label="来源" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.ownerType === 'EMPLOYEE' ? 'primary' : 'success'">
              {{ row.ownerTypeText }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ownerName" label="姓名" width="100" />
        <el-table-column prop="personNo" label="人员编号" width="120" />
        <el-table-column prop="validFrom" label="生效时间" width="160" />
        <el-table-column prop="validToText" label="有效期至" width="110" />
      </el-table>
      <div v-if="syncResult?.devices.length" style="margin-top: 10px">
        <div class="text-muted" style="margin-bottom: 4px">下发结果</div>
        <div v-for="d in syncResult.devices" :key="d.deviceId" class="tip-row">
          <el-tag size="small" :type="d.status === 'PUSHED' ? 'success'
            : d.status === 'PENDING_VENDOR' ? 'warning' : 'danger'">{{ d.status }}</el-tag>
          <span class="mono">{{ d.deviceSn }}</span>
          <span class="text-muted">{{ d.message }}</span>
        </div>
      </div>
      <div v-if="pushState" style="margin-top: 12px">
        <div class="toolbar">
          <span class="text-muted">
            车场接口：{{ pushState.enabled ? '已启用' : '未启用' }} ·
            {{ pushState.configured ? '凭据已配置' : '缺少 base-url / park-id / secret' }} ·
            每 {{ pushState.syncSeconds }} 秒自动对账 · 已下发 {{ pushState.rows.length }} 条
          </span>
          <span class="spacer" />
          <el-button size="small" :icon="Refresh" @click="loadPushState">刷新下发状态</el-button>
        </div>
        <el-table v-if="pushState.rows.length" :data="pushState.rows" size="small" border max-height="200">
          <el-table-column prop="plateNo" label="车牌" width="110" />
          <el-table-column prop="personName" label="姓名" width="100" />
          <el-table-column prop="beginTime" label="生效" width="150" />
          <el-table-column prop="endTime" label="到期" width="150" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.state === 'PUSHED' ? 'success' : 'danger'">{{ row.state }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="lastError" label="错误" show-overflow-tooltip />
        </el-table>
        <div v-else class="text-muted" style="margin-top: 4px">
          还没有下发记录：点上方「下发到车闸」可立即对账一次。
        </div>
      </div>
      <template #footer>
        <el-button @click="platesVisible = false">关闭</el-button>
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
  deleteDevice,
  deviceCmdOpen,
  deviceCmdQrConfig,
  deviceCmdSetParams,
  deviceCmdStatus,
  deviceCmdSyncParams,
  deviceCmdSyncTime,
  deviceRemoteDeletePerson,
  deviceRemoteGateHold,
  deviceRemoteInfo,
  deviceRemoteOpen,
  deviceRemoteParameters,
  deviceRemoteReboot,
  deviceRemoteRestore,
  deviceRemoteSnapshot,
  deviceRemoteSwitches,
  deviceRemoteTip,
  getVehiclePlates,
  getVehiclePlatePushState,
  listDevices,
  listGates,
  openDeviceDoor,
  syncVehiclePlates,
  updateDevice,
  vehicleMqttOpen,
  type DeviceCommandStatus,
  type DeviceRemoteInfo
} from '@/api'
import type {
  DeviceView,
  GateRow,
  VehiclePlatePushState,
  VehiclePlateSnapshot,
  VehiclePlateSyncResult
} from '@/api/types'

const route = useRoute()

/** 人行终端 / 车闸相机分开管理（docs/18）；空字符串=全部（默认全看，避免“装了车闸却看不到”） */
const deviceKind = ref<string>('')

const loading = ref(false)
const devices = ref<DeviceView[]>([])
const gates = ref<GateRow[]>([])
const filterGateId = ref<number | undefined>(
  route.query.gateId ? Number(route.query.gateId) : undefined
)

const editVisible = ref(false)
const editSaving = ref(false)
const editingRow = ref<DeviceView | null>(null)
const editForm = ref({
  deviceName: '',
  model: '',
  direction: 1,
  gateId: 0,
  remark: '',
  channel: 'PULL',
  ip: ''
})

/* ---------------- 车闸相机：车牌名单 ---------------- */
const platesVisible = ref(false)
const platesLoading = ref(false)
const platesSyncing = ref(false)
const platesRow = ref<DeviceView | null>(null)
const plates = ref<VehiclePlateSnapshot | null>(null)
const syncResult = ref<VehiclePlateSyncResult | null>(null)
/** 车场侧已收到的车牌快照（云车场 visitor_sync） */
const pushState = ref<VehiclePlatePushState | null>(null)

const syncAlertType = computed(() => {
  const result = syncResult.value
  const cloud = result?.cloud
  if (!cloud) {
    const devices = result?.devices || []
    if (devices.some((d) => d.status === 'FAILED')) return 'error'
    if (devices.some((d) => d.status === 'PENDING_VENDOR')) return 'warning'
    return 'success'
  }
  if (cloud.failed > 0) return 'error'
  return cloud.dryRun ? 'warning' : 'success'
})

const syncAlertText = computed(() => {
  const result = syncResult.value
  if (!result) return ''
  const cloud = result.cloud
  const devices = result.devices || []
  const pushed = devices.filter((d) => d.status === 'PUSHED').length
  const failed = devices.filter((d) => d.status === 'FAILED').length
  const pending = devices.filter((d) => d.status === 'PENDING_VENDOR').length
  const detail = devices.filter((d) => d.status === 'PUSHED').map((d) => d.message).join('；')
  const deviceText = `车牌 ${result.plateTotal} 条：已下发 ${pushed} 台`
    + (failed ? ` / 失败 ${failed} 台` : '')
    + (pending ? ` / 待接入 ${pending} 台` : '')
    + (detail ? `（${detail}）` : '')
  if (!cloud) return deviceText
  const cloudDetail = `车牌 ${result.plateTotal} 条：新增 ${cloud.added} / 修改 ${cloud.modified} / ` +
    `删除 ${cloud.removed} / 无变化 ${cloud.unchanged} / 失败 ${cloud.failed}`
  if (!cloud.dryRun) return cloudDetail
  // 车场接口没启用时车闸走的是 MQTT：有相机结果就报相机，别让「未发送」盖住实际已下发
  return (pushed || failed || pending) ? deviceText : `${cloud.message}（${cloudDetail}）`
})

async function load() {
  loading.value = true
  try {
    devices.value = await listDevices(filterGateId.value, deviceKind.value || undefined)
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
    remark: row.remark,
  channel: row.channel || 'PULL',
  ip: row.ip || ''
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

/** 删除档案：登记错/换下来的旧机器；最近 5 分钟有心跳的会被后端拒绝 */
async function doDelete(row: DeviceView) {
  try {
    await deleteDevice(row.id)
    ElMessage.success(`已删除「${row.deviceName || row.deviceSn}」`)
    await load()
  } catch (error) {
    showRemoteError(error)
  }
}

/* ---------------- 车闸相机：车牌名单 ---------------- */

/** 打开车牌名单；不传设备时按「全部车闸」查看/下发（云车场按车场下发，不依赖某台相机） */
async function openPlates(row?: DeviceView) {
  platesRow.value = row ?? null
  platesVisible.value = true
  syncResult.value = null
  plates.value = null
  pushState.value = null
  await Promise.all([loadPlates(), loadPushState()])
}

/** 车场侧已下发的车牌（含失败原因），用于判断「业务改了车牌后有没有真到车场」 */
async function loadPushState() {
  try {
    pushState.value = await getVehiclePlatePushState()
  } catch {
    pushState.value = null
  }
}

async function loadPlates() {
  platesLoading.value = true
  try {
    plates.value = await getVehiclePlates(platesRow.value?.gateId || undefined)
  } catch (error) {
    showRemoteError(error)
  } finally {
    platesLoading.value = false
  }
}

/** 下发车牌名单：云车场启用时走 visitor_sync 差分；未接入时才返回 PENDING_VENDOR */
async function doSyncPlates() {
  platesSyncing.value = true
  try {
    syncResult.value = await syncVehiclePlates(platesRow.value?.gateId || undefined)
    const cloud = syncResult.value.cloud
    const devices = syncResult.value.devices || []
    const pushed = devices.filter((d) => d.status === 'PUSHED').length
    const failed = devices.filter((d) => d.status === 'FAILED')
    if (cloud && !cloud.dryRun) {
      if (cloud.failed > 0) {
        ElMessage.warning(`已下发，但有 ${cloud.failed} 条失败，详见下方错误`)
      } else {
        ElMessage.success(`已同步到车场：新增 ${cloud.added} / 修改 ${cloud.modified} / 删除 ${cloud.removed}`)
      }
    } else if (failed.length > 0) {
      ElMessage.warning(`MQTT 下发失败：${failed[0].message}`)
    } else if (pushed > 0) {
      ElMessage.success(`已通过 MQTT 下发 ${syncResult.value.plateTotal} 条车牌到 ${pushed} 台车闸相机`)
    } else if (syncResult.value.pendingDevices > 0) {
      ElMessage.warning('车牌清单已备好，但车闸相机厂商协议还没接入，暂时投不到相机本地')
    } else if (cloud && cloud.dryRun) {
      ElMessage.warning(cloud.message)
    } else {
      ElMessage.success(`已下发 ${syncResult.value.plateTotal} 条车牌`)
    }
    await Promise.all([loadPlates(), loadPushState()])
  } catch (error) {
    showRemoteError(error)
  } finally {
    platesSyncing.value = false
  }
}

/** 车闸相机：人工远程开闸（走 MQTT，不依赖局域网） */
async function openVehicleGate(row: DeviceView) {
  const result = await ElMessageBox.prompt(
    `确认远程打开「${row.deviceName}」的道闸？\n可选：填车牌会按车牌判定并记进该人的通行记录（留空只记一条人工开闸）。`,
    '远程开闸',
    {
      type: 'warning',
      inputPlaceholder: '车牌号，可留空',
      inputValue: '',
      inputValidator: (value: string) =>
        !value || /^[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼A-Z][A-Z][A-Z0-9]{4,6}$/.test(
          value.trim().toUpperCase()
        ) || '车牌格式不对（如 苏A12345）'
    }
  )
  const plate = String((result as { value?: string }).value || '').trim()
  try {
    await vehicleMqttOpen(row.deviceSn, plate)
    ElMessage.success('开闸指令已下发（相机在线即刻抬杆）')
  } catch (error) {
    showRemoteError(error)
  }
}

async function openDoor(row: DeviceView) {  await ElMessageBox.confirm(
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
const remoteTab = ref('queue')
const remoteBusy = ref('')
/** 心跳下发（设备来取指令）：队列状态 + 平台期望参数 */
const cmdStatus = ref<DeviceCommandStatus | null>(null)
const cmdBusy = ref('')
const snapshotSrc = ref('')
const holdMinutes = ref(10)
const tipForm = ref({ msg: '', color: 0 })
const remoteVersion = ref<Record<string, unknown>>({})
const remoteParams = ref<Record<string, unknown>>({})
/** 可远程切换的开关：key → 中文说明 */
const switchDefs = ref<Record<string, string>>({})
/** 名单清理：证件号 + 类型 */
const cleanIdno = ref('')
const cleanType = ref('white')

/** 平台期望的开关值（不是从设备读回的实时值） */
const overrideOf = (key: string) => (cmdStatus.value?.overrides || {})[key] === 1
const hasOverride = (key: string) =>
  Object.prototype.hasOwnProperty.call(cmdStatus.value?.overrides || {}, key)

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
  remoteTab.value = 'queue'
  remoteInfo.value = null
  remoteVersion.value = {}
  remoteParams.value = {}
  snapshotSrc.value = ''
  cmdStatus.value = null
  if (cmdRefreshTimer) {
    window.clearTimeout(cmdRefreshTimer)
    cmdRefreshTimer = undefined
  }
  await Promise.all([loadCmdStatus(), loadSwitchDefs(), loadRemoteStatus()])
}

/** 心跳下发队列：在线状态、待执行指令、平台期望参数、最近下发结果 */
async function loadCmdStatus() {
  if (!remoteRow.value) return
  try {
    cmdStatus.value = await deviceCmdStatus(remoteRow.value.id)
  } catch (error) {
    showRemoteError(error)
  }
}

/** 指令要等设备下一次心跳才执行；下发后自动再刷一次队列，避免界面停在「待执行」 */
let cmdRefreshTimer: number | undefined
function scheduleCmdRefresh() {
  if (cmdRefreshTimer) window.clearTimeout(cmdRefreshTimer)
  const delay = ((cmdStatus.value?.heartSeconds || 5) + 2) * 1000
  cmdRefreshTimer = window.setTimeout(() => { void loadCmdStatus() }, delay)
}

/** 可切换开关清单（服务端常量，不碰设备） */
async function loadSwitchDefs() {
  if (!remoteRow.value) return
  try {
    switchDefs.value = await deviceRemoteSwitches(remoteRow.value.id)
  } catch (error) {
    switchDefs.value = {}
    showRemoteError(error)
  }
}

/* ---------------- 心跳下发（云端可用） ---------------- */

async function doCmdOpen() {
  if (!remoteRow.value) return
  cmdBusy.value = 'open'
  try {
    const res = await deviceCmdOpen(remoteRow.value.id)
    ElMessage.success(res.hint)
    await loadCmdStatus()
    scheduleCmdRefresh()
  } catch (error) {
    showRemoteError(error)
  } finally {
    cmdBusy.value = ''
  }
}

async function doCmdSyncTime() {
  if (!remoteRow.value) return
  cmdBusy.value = 'time'
  try {
    const res = await deviceCmdSyncTime(remoteRow.value.id)
    ElMessage.success(res.hint)
    await loadCmdStatus()
    scheduleCmdRefresh()
  } catch (error) {
    showRemoteError(error)
  } finally {
    cmdBusy.value = ''
  }
}

async function doCmdSyncParams() {
  if (!remoteRow.value) return
  cmdBusy.value = 'params'
  try {
    const res = await deviceCmdSyncParams(remoteRow.value.id)
    ElMessage.success(res.hint)
    await loadCmdStatus()
    scheduleCmdRefresh()
  } catch (error) {
    showRemoteError(error)
  } finally {
    cmdBusy.value = ''
  }
}

async function doCmdQrConfig() {
  if (!remoteRow.value) return
  cmdBusy.value = 'qr'
  try {
    const res = await deviceCmdQrConfig(remoteRow.value.id)
    ElMessage.success(res.hint)
    await loadCmdStatus()
    scheduleCmdRefresh()
  } catch (error) {
    showRemoteError(error)
  } finally {
    cmdBusy.value = ''
  }
}

/** 开关：先存平台期望值，再排队一次参数下发（设备来拉时套用） */
async function toggleSwitch(key: string, value: boolean) {
  if (!remoteRow.value) return
  cmdBusy.value = 'switch'
  try {
    const res = await deviceCmdSetParams(remoteRow.value.id, { [key]: value })
    ElMessage.success(`${switchDefs.value[key] || key} 已${value ? '开启' : '关闭'}，${res.hint}`)
    await loadCmdStatus()
    scheduleCmdRefresh()
  } catch (error) {
    showRemoteError(error)
  } finally {
    cmdBusy.value = ''
  }
}

/** 本地管理：直接跳设备自己的 Web 后台（同网即可，不依赖平台） */function openLocal(row: DeviceView) {
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

/** 从设备本地名单删掉一条（清理孤儿名单） */
async function doDeletePerson() {
  if (!remoteRow.value) return
  const idno = cleanIdno.value.trim()
  if (!idno) {
    ElMessage.warning('请填写要删除的证件号')
    return
  }
  await ElMessageBox.confirm(
    `从设备本地${cleanType.value === 'black' ? '黑名单' : '白名单'}删除「${idno}」？\n` +
      `仅清理设备侧数据，不影响平台档案。`,
    '名单清理',
    { type: 'warning' }
  )
  remoteBusy.value = 'clean'
  try {
    await deviceRemoteDeletePerson(remoteRow.value.id, { idno, usertype: cleanType.value })
    ElMessage.success('已从设备删除')
    cleanIdno.value = ''
    await loadRemoteStatus()
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
.switch-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.switch-label {
  font-size: 13px;
  color: #303133;
}
</style>