<template>
  <div class="page-card">
    <el-tabs v-model="tab">
      <el-tab-pane label="审批流程" name="flow">
        <div class="toolbar">
          <el-input v-model="form.templateName" placeholder="模板名称" style="width: 220px" maxlength="64" />
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
          <span class="spacer" />
          <el-tag type="info" size="small">节点按顺序串行执行，最后一个节点通过才下发门禁授权</el-tag>
        </div>
        <el-alert v-if="!form.enabled" type="warning" :closable="false" show-icon style="margin-bottom: 10px"
          title="模板已停用：新提交的预约会退回「单级角色审批」，不会卡住" />
        <el-alert type="info" :closable="false" show-icon style="margin-bottom: 10px"
          title="保存后只影响「之后新提交的预约」：已在途的审批按提交时的层级/角色走完（提交时已快照流程），不会被新模板改变。" />

        <div v-for="(node, i) in form.nodes" :key="i" class="node-card">
          <div class="node-head">
            <span class="seq">{{ i + 1 }}</span>
            <el-input v-model="node.nodeName" placeholder="节点名称，如 保安审核" maxlength="64"
              style="width: 200px" />
            <el-select v-model="node.assigneeRole" placeholder="处理角色" style="width: 190px">
              <el-option v-for="r in roles" :key="r.roleCode" :value="r.roleCode"
                :label="`${r.roleName}（${r.roleCode}）`" />
            </el-select>
            <el-tag type="info" size="small" title="一个节点只建一条待办，该角色任意一人处理即办结">
              或签（任一人通过）
            </el-tag>
            <span class="spacer" />
            <el-button link type="primary" :disabled="i === 0" @click="move(i, -1)">上移</el-button>
            <el-button link type="primary" :disabled="i === form.nodes.length - 1"
              @click="move(i, 1)">下移</el-button>
            <el-button link type="danger" :disabled="form.nodes.length <= 1"
              @click="removeNode(i)">删除</el-button>
          </div>
          <div class="node-opts">
            <el-checkbox v-model="node.allowEditGates">允许调整开放门点</el-checkbox>
          </div>
          <div class="node-cc">
            <span class="label">办结后抄送</span>
            <el-select v-model="node.ccRoles" multiple clearable placeholder="选择抄送角色（可不选）"
              style="width: 360px">
              <el-option v-for="r in roles" :key="r.roleCode" :value="r.roleCode"
                :label="`${r.roleName}（${r.roleCode}）`" />
            </el-select>
          </div>
        </div>

        <div class="toolbar" style="margin-top: 8px">
          <el-button :icon="Plus" @click="addNode">添加节点</el-button>
          <span class="spacer" />
          <el-button type="primary" :loading="saving" @click="saveFlow">保存审批流程</el-button>
          <el-button @click="loadFlow">重置</el-button>
        </div>

        <div class="submit-cc">
          <span class="label">提交时抄送</span>
          <el-select v-model="form.submitCcRoles" multiple clearable placeholder="访客提交预约时通知这些角色"
            style="width: 420px">
            <el-option v-for="r in roles" :key="r.roleCode" :value="r.roleCode"
              :label="`${r.roleName}（${r.roleCode}）`" />
          </el-select>
        </div>

        <el-alert type="info" :closable="false" show-icon style="margin-top: 12px; max-width: 900px"
          title="抄送只通知、不阻断流程；站内通知会出现在管理端右上角的消息铃铛里。" />
      </el-tab-pane>

      <el-tab-pane label="催办设置" name="reminder">
        <el-form :model="reminder" label-width="150px" style="max-width: 620px">
          <el-form-item label="催办间隔（分钟）">
            <el-input-number v-model="reminder.reminderIntervalMin" :min="5" :max="1440" :step="5" />
          </el-form-item>
          <el-form-item label="单任务催办上限">
            <el-input-number v-model="reminder.reminderMaxPerTask" :min="1" :max="20" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="saveReminder">保存催办设置</el-button>
            <el-button @click="loadReminder">重置</el-button>
          </el-form-item>
        </el-form>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  approvalRoles,
  createApprovalTemplate,
  getApprovalConfig,
  listApprovalTemplates,
  updateApprovalConfig,
  updateApprovalTemplate
} from '@/api'
import type { ApprovalNodeView, RoleOption } from '@/api/types'

const tab = ref('flow')
const saving = ref(false)
const roles = ref<RoleOption[]>([])

interface NodeForm {
  nodeName: string
  assigneeRole: string
  approveMode: string
  allowEditGates: boolean
  ccRoles: string[]
}

const form = reactive<{
  id: number | null
  templateName: string
  enabled: boolean
  remark: string
  nodes: NodeForm[]
  submitCcRoles: string[]
}>({
  id: null,
  templateName: '默认审批流程',
  enabled: true,
  remark: '',
  nodes: [{ nodeName: '管理端审核', assigneeRole: 'GUARD', approveMode: 'ANY',
    allowEditGates: true, ccRoles: [] }],
  submitCcRoles: []
})

const reminder = reactive({ reminderIntervalMin: 30, reminderMaxPerTask: 3 })

function toFormNode(node: ApprovalNodeView): NodeForm {
  return {
    nodeName: node.nodeName,
    assigneeRole: node.assigneeRole,
    approveMode: node.approveMode || 'SINGLE',
    allowEditGates: node.allowEditGates !== false,
    ccRoles: node.ccRoles || []
  }
}

async function loadRoles() {
  roles.value = await approvalRoles()
}

async function loadFlow() {
  const list = await listApprovalTemplates()
  const template = list[0]
  if (!template) {
    form.id = null
    form.nodes = [{ nodeName: '管理端审核', assigneeRole: 'GUARD', approveMode: 'ANY',
      allowEditGates: true, ccRoles: [] }]
    form.submitCcRoles = []
    return
  }
  form.id = template.id
  form.templateName = template.templateName
  form.enabled = template.enabled
  form.remark = template.remark
  form.nodes = template.nodes.map(toFormNode)
  form.submitCcRoles = template.submitCcRoles || []
  if (form.nodes.length === 0) {
    form.nodes = [{ nodeName: '管理端审核', assigneeRole: 'GUARD', approveMode: 'ANY',
      allowEditGates: true, ccRoles: [] }]
  }
}

async function loadReminder() {
  const config = await getApprovalConfig()
  reminder.reminderIntervalMin = config.reminderIntervalMin
  reminder.reminderMaxPerTask = config.reminderMaxPerTask
}

function addNode() {
  form.nodes.push({
    nodeName: `审批节点 ${form.nodes.length + 1}`,
    assigneeRole: 'GUARD',
    approveMode: 'ANY',
    allowEditGates: true,
    ccRoles: []
  })
}

function removeNode(index: number) {
  form.nodes.splice(index, 1)
}

function move(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= form.nodes.length) return
  const [node] = form.nodes.splice(index, 1)
  form.nodes.splice(target, 0, node)
}

async function saveFlow() {
  if (form.nodes.length === 0) {
    ElMessage.warning('至少要有一个审批节点')
    return
  }
  if (form.nodes.some((n) => !n.nodeName.trim())) {
    ElMessage.warning('节点名称不能为空')
    return
  }
  if (form.nodes.some((n) => !n.assigneeRole)) {
    ElMessage.warning('每个节点都要选择处理角色')
    return
  }
  const payload = {
    templateName: form.templateName || '默认审批流程',
    enabled: form.enabled,
    remark: form.remark,
    submitCcRoles: form.submitCcRoles,
    nodes: form.nodes.map((n) => ({
      nodeName: n.nodeName.trim(),
      assigneeRole: n.assigneeRole,
      approveMode: n.approveMode,
      allowEditGates: n.allowEditGates,
      ccRoles: n.ccRoles
    }))
  }
  saving.value = true
  try {
    if (form.id) {
      await updateApprovalTemplate(form.id, payload)
    } else {
      await createApprovalTemplate(payload)
    }
    ElMessage.success('审批流程已保存')
    await loadFlow()
  } finally {
    saving.value = false
  }
}

async function saveReminder() {
  saving.value = true
  try {
    await updateApprovalConfig({
      approvalLevel: 1,
      l2Role: 'GUARD',
      reminderIntervalMin: reminder.reminderIntervalMin,
      reminderMaxPerTask: reminder.reminderMaxPerTask
    })
    ElMessage.success('催办设置已保存')
    await loadReminder()
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await loadRoles()
  await Promise.all([loadFlow(), loadReminder()])
})
</script>

<style scoped>
.node-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px 14px;
  margin-bottom: 10px;
  max-width: 980px;
  background: var(--el-fill-color-blank);
}

.node-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.seq {
  width: 22px;
  height: 22px;
  line-height: 22px;
  text-align: center;
  border-radius: 50%;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 12px;
}

.node-opts {
  margin-top: 8px;
  display: flex;
  gap: 18px;
}

.node-cc {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.submit-cc {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
