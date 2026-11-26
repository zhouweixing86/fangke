<template>
  <div class="page-card">
    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="证件号后四位 / 手机号后四位 / 原因" clearable
        style="width: 260px" @keyup.enter="search" />
      <el-select v-model="query.status" placeholder="状态" clearable style="width: 120px">
        <el-option label="生效" :value="1" />
        <el-option label="已失效" :value="0" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <span class="spacer" />
      <el-button type="primary" :icon="Plus" @click="openCreate">新增黑名单</el-button>
    </div>

    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px"
      title="命中黑名单的访客在提交预约时直接拒绝（BR-V-005）。列表只展示脱敏值，明文加密入库、不落日志。" />

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="targetTypeText" label="类型" width="110"  sortable />
      <el-table-column prop="targetMask" label="对象" width="200"  sortable />
      <el-table-column prop="reason" label="拉黑原因" min-width="180"  sortable />
      <el-table-column label="有效期" width="320">
        <template #default="{ row }">
          <span class="text-muted">
            {{ row.validFrom || '不限' }} ~ {{ row.validTo || '不限' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'danger' : 'info'" size="small">{{ row.statusText }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="170"  sortable />
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 1" link type="danger" @click="enforce(row)">立即生效</el-button>
          <el-button v-if="row.status === 1" link type="warning" @click="changeStatus(row, 0)">失效</el-button>
          <el-button v-else link type="primary" @click="changeStatus(row, 1)">恢复</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
        layout="total, prev, pager, next" @current-change="load" />
    </div>

    <el-dialog v-model="formVisible" title="新增黑名单" width="560px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px"
        title="身份证类型会下发到设备本地黑名单（约 5 秒生效），刷证时被拒；手机号类型只在平台侧拦截预约，闸机无法识别手机号" />
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="对象类型" prop="targetType">
          <el-radio-group v-model="form.targetType">
            <el-radio :value="1">身份证号</el-radio>
            <el-radio :value="2">手机号</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="form.targetType === 1 ? '身份证号' : '手机号'" prop="target">
          <el-input v-model="form.target"
            :placeholder="form.targetType === 1 ? '18 位身份证号（会校验校验位）' : '11 位手机号'" />
        </el-form-item>
        <el-form-item label="拉黑原因" prop="reason">
          <el-input v-model="form.reason" type="textarea" :rows="3" maxlength="255" show-word-limit />
        </el-form-item>
        <el-form-item label="有效期">
          <el-date-picker v-model="validRange" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss"
            start-placeholder="生效开始" end-placeholder="生效结束" style="width: 100%" />
          <div class="tip">留空表示长期有效。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { createBlacklist, enforceBlacklist, listBlacklist, updateBlacklistStatus } from '@/api'
import type { BlacklistRow } from '@/api/types'

const rows = ref<BlacklistRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const query = reactive<{ keyword: string; status: number | undefined }>({ keyword: '', status: undefined })

const formVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const validRange = ref<[string, string] | null>(null)
const form = reactive<{ targetType: number; target: string; reason: string }>({
  targetType: 1,
  target: '',
  reason: ''
})

const rules: FormRules = {
  targetType: [{ required: true, message: '请选择对象类型', trigger: 'change' }],
  target: [{ required: true, message: '请输入拉黑对象', trigger: 'blur' }],
  reason: [{ required: true, message: '请填写拉黑原因', trigger: 'blur' }]
}

async function load() {
  loading.value = true
  try {
    const result = await listBlacklist({
      keyword: query.keyword || undefined,
      status: query.status,
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

function openCreate() {
  form.targetType = 1
  form.target = ''
  form.reason = ''
  validRange.value = null
  formVisible.value = true
}

async function save() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    await createBlacklist({
      targetType: form.targetType,
      target: form.target.trim(),
      reason: form.reason.trim(),
      validFrom: validRange.value?.[0] ?? null,
      validTo: validRange.value?.[1] ?? null
    })
    ElMessage.success('已加入黑名单')
    formVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

/** 立即生效：下发设备黑名单 + 撤销在途预约、删设备白名单、作废通行码（BR-B-003） */
async function enforce(row: BlacklistRow) {
  await ElMessageBox.confirm(
    `立即对「${row.targetMask}」生效？\n\n` +
      `· 下发到设备本地黑名单（身份证类型，约 5 秒生效）\n` +
      `· 撤回其待审批预约、撤销已下发授权（设备白名单删掉该人）\n` +
      `· 作废未使用通行码`,
    '拉黑立即生效',
    { type: 'warning' }
  )
  const result = await enforceBlacklist(row.id)
  ElMessageBox.alert(result.message, '执行结果', {
    type: result.failed > 0 ? 'warning' : 'success'
  })
  await load()
}

async function changeStatus(row: BlacklistRow, status: number) {
  await ElMessageBox.confirm(
    status === 0
      ? `让「${row.targetMask}」恢复可预约？\n失效后会从设备本地黑名单删除该人（约 5 秒生效），不再拦截。`
      : `恢复拦截「${row.targetMask}」？\n会重新下发到设备本地黑名单，其预约将被拒绝。`,
    status === 0 ? '失效黑名单' : '恢复黑名单',
    { type: 'warning' }
  )
  await updateBlacklistStatus(row.id, status)
  ElMessage.success(status === 0 ? '已失效，正在从设备删除' : '已恢复，正在下发到设备')
  await load()
}

onMounted(load)
</script>

<style scoped>
.tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
</style>
