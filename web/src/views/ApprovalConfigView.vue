<template>
  <div class="page-card">
    <el-form ref="formRef" :model="form" label-width="150px" style="max-width: 720px">
      <el-form-item label="审批方式">
        <el-tag type="success" size="large">单级 · 管理端审核</el-tag>
        <div class="tip">
          预约提交后进入「待审核」，由登录管理端的<b>保安 / 管理员</b>审核后下发门禁授权。
          员工不参与审批（被访人只作为"拜访对象"记录）。
        </div>
      </el-form-item>

      <el-form-item label="审核角色">
        <el-tag type="success" size="large">保安（GUARD）</el-tag>
        <div class="tip">该角色的<b>任一后台账号</b>都可审核（保安轮班，不指定到人）。</div>
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
import { getApprovalConfig, updateApprovalConfig } from '@/api'

const saving = ref(false)

const form = reactive({
  reminderIntervalMin: 30,
  reminderMaxPerTask: 3
})

async function load() {
  const config = await getApprovalConfig()
  form.reminderIntervalMin = config.reminderIntervalMin
  form.reminderMaxPerTask = config.reminderMaxPerTask
}

async function save() {
  saving.value = true
  try {
    await updateApprovalConfig({
      // 级数固定 1：后端会强制归一，这里带上以兼容旧契约
      approvalLevel: 1,
      l2Role: 'GUARD',
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
