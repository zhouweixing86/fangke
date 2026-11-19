<template>
  <div class="page-card">
    <el-form ref="formRef" :model="form" label-width="150px" style="max-width: 720px">
      <el-form-item label="审批级数">
        <el-radio-group v-model="form.approvalLevel">
          <el-radio :value="1">一级（仅被访人确认）</el-radio>
          <el-radio :value="2">两级（被访人确认 + 保安复核）</el-radio>
        </el-radio-group>
        <div class="tip">
          一级审批人为被访员工本人；若被访员工不在职，则使用下方「默认一级审批人」。
          两级时，被访人确认后还须<b>门卫复核</b>才下发门禁授权（避免员工单方放人进厂区）。
        </div>
      </el-form-item>

      <el-form-item label="默认一级审批人">
        <el-select v-model="form.defaultL1User" filterable remote clearable placeholder="工号 / 姓名搜索"
          :remote-method="searchEmployees" :loading="empLoading" style="width: 320px">
          <el-option v-for="e in employees" :key="e.id" :label="`${e.name}（${e.empNo}）`" :value="e.id" />
        </el-select>
        <div class="tip">被访员工离职或未填写时兜底使用。</div>
      </el-form-item>

      <el-form-item v-if="form.approvalLevel === 2" label="二级复核角色">
        <el-tag type="success" size="large">保安（GUARD）</el-tag>
        <div class="tip">
          该角色的<b>任一后台账号</b>都可复核（保安轮班，不指定到人）；
          复核通过才下发门禁授权，复核驳回则整单驳回。
        </div>
      </el-form-item>

      <el-form-item label="催办间隔（分钟）">
        <el-input-number v-model="form.reminderIntervalMin" :min="5" :max="1440" :step="5" />
      </el-form-item>

      <el-form-item label="单任务催办上限">
        <el-input-number v-model="form.reminderMaxPerTask" :min="1" :max="20" />
      </el-form-item>

      <el-form-item>
        <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>
        <el-button @click="load">重置</el-button>
      </el-form-item>
    </el-form>

    <el-alert type="info" :closable="false" show-icon style="max-width: 720px"
      title="配置保存后仅对新提交的预约生效，已在审批中的单据沿用提交时的配置。" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getApprovalConfig, listEmployees, updateApprovalConfig } from '@/api'
import type { EmployeeView } from '@/api/types'

const employees = ref<EmployeeView[]>([])
const empLoading = ref(false)
const saving = ref(false)

const form = reactive<{
  approvalLevel: number
  defaultL1User: number | null
  l2CandidateIds: number[]
  l2Role: string
  reminderIntervalMin: number
  reminderMaxPerTask: number
}>({
  approvalLevel: 2,
  defaultL1User: null,
  l2CandidateIds: [],
  l2Role: 'GUARD',
  reminderIntervalMin: 30,
  reminderMaxPerTask: 3
})

async function load() {
  const config = await getApprovalConfig()
  form.approvalLevel = config.approvalLevel
  form.defaultL1User = config.defaultL1User
  form.l2CandidateIds = config.l2CandidateIds ?? []
  form.l2Role = config.l2Role || 'GUARD'
  form.reminderIntervalMin = config.reminderIntervalMin
  form.reminderMaxPerTask = config.reminderMaxPerTask

  const ids = [config.defaultL1User].filter((v): v is number => !!v)
  if (ids.length) {
    const result = await listEmployees({ page: 1, size: 100 })
    employees.value = result.list
    const missing = ids.filter((id) => !employees.value.some((e) => e.id === id))
    if (missing.length) {
      ElMessage.warning(`有 ${missing.length} 名已配置审批人不在当前员工列表中，请重新选择`)
    }
  }
}

async function searchEmployees(keyword: string) {
  empLoading.value = true
  try {
    const result = await listEmployees({ keyword: keyword || undefined, page: 1, size: 20 })
    employees.value = result.list
  } finally {
    empLoading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    await updateApprovalConfig({
      approvalLevel: form.approvalLevel,
      defaultL1User: form.defaultL1User,
      l2CandidateIds: form.l2CandidateIds,
      l2Role: form.l2Role,
      reminderIntervalMin: form.reminderIntervalMin,
      reminderMaxPerTask: form.reminderMaxPerTask
    })
    ElMessage.success('已保存')
    await load()
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
  margin-top: 4px;
}
</style>
