<template>
  <div class="page-card">
    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="工号 / 姓名 / 手机号后四位" clearable
        style="width: 240px" @keyup.enter="search" />
      <el-select v-model="query.status" placeholder="状态" clearable style="width: 120px">
        <el-option label="在职" :value="1" />
        <el-option label="待复核" :value="2" />
        <el-option label="停用" :value="3" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <span class="spacer" />
      <el-button type="primary" :icon="Plus" @click="openCreate">新增员工</el-button>
      <el-button :icon="Refresh" @click="syncAll">同步名单</el-button>
      <el-upload :show-file-list="false" :auto-upload="false" accept=".xlsx,.xls" :on-change="onImportFile">
        <el-button :icon="Upload">Excel 导入</el-button>
      </el-upload>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="empNo" label="工号" width="100" />
      <el-table-column prop="name" label="姓名" width="110" />
      <el-table-column prop="deptName" label="部门" width="110" />
      <el-table-column prop="title" label="职务" width="110" />
      <el-table-column prop="phoneMask" label="手机号" width="130" />
      <el-table-column prop="idCardMask" label="身份证" width="180" />
      <el-table-column label="有效期" width="200">
        <template #default="{ row }">
          <span class="text-muted">{{ row.validFrom || '-' }} ~ {{ row.validTo || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : row.status === 2 ? 'warning' : 'info'" size="small">
            {{ row.statusText }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="照片" width="100">
        <template #default="{ row }">
          <el-tag :type="row.faceStatus >= 1 ? 'success' : 'info'" size="small">
            {{ row.faceStatusText }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="openFace(row)">照片</el-button>
          <el-button link type="primary" @click="syncOne(row)">下发</el-button>
          <el-button link type="danger" :disabled="row.status === 3" @click="stop(row)">停用</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
        layout="total, prev, pager, next" @current-change="load" />
    </div>

    <el-dialog v-model="formVisible" :title="form.id ? '编辑员工' : '新增员工'" width="620px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="工号" prop="empNo">
          <el-input v-model="form.empNo" :disabled="!!form.id" placeholder="1~16 位字母数字" />
        </el-form-item>
        <el-form-item label="姓名" prop="name">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="部门">
          <el-select v-model="form.deptId" clearable placeholder="选择部门" style="width: 100%">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="职务">
          <el-input v-model="form.title" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" placeholder="11 位手机号，留空表示不修改" />
        </el-form-item>
        <el-form-item label="身份证号" prop="idCard">
          <el-input v-model="form.idCard" placeholder="18 位，留空表示不修改" />
        </el-form-item>
        <el-form-item label="有效期">
          <el-date-picker v-model="validRange" type="daterange" value-format="YYYY-MM-DD"
            start-placeholder="开始日期" end-placeholder="结束日期" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="faceVisible" title="员工人脸照片" width="640px">
      <el-alert type="info" :closable="false" show-icon
        title="要求：≤500KB、400×400 ~ 1080×1920、单人正面；上传即生效并进入待下发" style="margin-bottom: 12px" />
      <el-upload drag :show-file-list="false" :auto-upload="false" accept="image/jpeg,image/png"
        :on-change="onFaceFile">
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">将照片拖到此处，或<em>点击选择</em></div>
      </el-upload>

      <el-table :data="faces" border stripe style="margin-top: 16px" size="small">
        <el-table-column label="上传时间" width="160">
          <template #default="{ row }">{{ row.createdAt || '-' }}</template>
        </el-table-column>
        <el-table-column label="尺寸" width="110">
          <template #default="{ row }">{{ row.width }}×{{ row.height }}</template>
        </el-table-column>
        <el-table-column label="大小" width="90">
          <template #default="{ row }">{{ Math.round((row.sizeBytes || 0) / 1024) }} KB</template>
        </el-table-column>
        <el-table-column label="当前" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.current" type="success" size="small">生效</el-tag>
            <span v-else class="text-muted">历史</span>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules, type UploadFile } from 'element-plus'
import { Plus, Refresh, Upload, UploadFilled } from '@element-plus/icons-vue'
import {
  createEmployee,
  importEmployees,
  listDepts,
  listEmployeeFaces,
  listEmployees,
  stopEmployee,
  syncAllEmployees,
  syncEmployee,
  updateEmployee,
  uploadEmployeeFace
} from '@/api'
import type { DeptView, EmployeeFace, EmployeeView } from '@/api/types'

const rows = ref<EmployeeView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const query = reactive<{ keyword: string; status: number | undefined }>({ keyword: '', status: undefined })

const depts = ref<DeptView[]>([])
const formVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const validRange = ref<[string, string] | null>(null)
const form = reactive<Record<string, any>>({
  id: null,
  empNo: '',
  name: '',
  deptId: null,
  title: '',
  phone: '',
  idCard: '',
  remark: ''
})

const rules: FormRules = {
  empNo: [{ required: true, message: '请输入工号', trigger: 'blur' }],
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }]
}

const faceVisible = ref(false)
const faces = ref<EmployeeFace[]>([])
const faceEmployee = ref<EmployeeView | null>(null)

async function load() {
  loading.value = true
  try {
    const result = await listEmployees({
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
  Object.assign(form, { id: null, empNo: '', name: '', deptId: null, title: '', phone: '', idCard: '', remark: '' })
  validRange.value = null
  formVisible.value = true
}

function openEdit(row: EmployeeView) {
  Object.assign(form, {
    id: row.id,
    empNo: row.empNo,
    name: row.name,
    deptId: row.deptId,
    title: row.title,
    phone: '',
    idCard: '',
    remark: row.remark
  })
  validRange.value = row.validFrom && row.validTo ? [row.validFrom, row.validTo] : null
  formVisible.value = true
}

async function save() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  const payload: Record<string, unknown> = {
    empNo: form.empNo,
    name: form.name,
    deptId: form.deptId,
    title: form.title,
    phone: form.phone || undefined,
    idCard: form.idCard || undefined,
    validFrom: validRange.value?.[0] ?? null,
    validTo: validRange.value?.[1] ?? null,
    remark: form.remark
  }
  saving.value = true
  try {
    if (form.id) {
      await updateEmployee(form.id, payload)
      ElMessage.success('已保存')
    } else {
      await createEmployee(payload)
      ElMessage.success('已创建')
    }
    formVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function stop(row: EmployeeView) {
  await ElMessageBox.confirm(
    `停用后「${row.name}」将不再能刷脸通行，并从设备名单移除。确认？`,
    '停用员工',
    { type: 'warning' }
  )
  await stopEmployee(row.id)
  ElMessage.success('已停用')
  await load()
}

/** 单个员工重新下发（入职/换照片已自动下发，这里是补偿） */
async function syncOne(row: EmployeeView) {
  const result = await syncEmployee(row.id)
  ElMessage.success(`已同步「${row.name}」：${result.devices} 台设备，成功 ${result.success}，失败 ${result.fail}`)
}

/** 一键全量：在岗全部下发 + 停用全部撤销 */
async function syncAll() {
  await ElMessageBox.confirm(
    '将把所有在岗员工下发到全部启用门点设备，并撤销停用员工的名单。设备较多时可能需要几十秒。',
    '同步员工名单',
    { type: 'warning' }
  )
  const result = await syncAllEmployees()
  ElMessageBox.alert(
    `${result.message}；授权 ${result.grants} 条，设备 ${result.devices} 台次，成功 ${result.success}，失败 ${result.fail}`,
    '同步结果',
    { type: result.fail > 0 ? 'warning' : 'success' }
  )
  await load()
}

function onImportFile(file: UploadFile) {
  if (!file.raw) return
  ElMessageBox.confirm(`导入 ${file.name}？`, '批量导入', { type: 'info' }).then(async () => {
    const result = await importEmployees(file.raw as File)
    const errorText = result.errors.length
      ? '\n\n错误：\n' + result.errors.map((e) => `第 ${e.row} 行：${e.message}`).join('\n')
      : ''
    ElMessageBox.alert(`共 ${result.total} 行，成功 ${result.success} 行${errorText}`, '导入结果', {
      type: result.errors.length ? 'warning' : 'success'
    })
    await load()
  })
}

async function openFace(row: EmployeeView) {
  faceEmployee.value = row
  faces.value = await listEmployeeFaces(row.id)
  faceVisible.value = true
}

function onFaceFile(file: UploadFile) {
  if (!file.raw || !faceEmployee.value) return
  uploadEmployeeFace(faceEmployee.value.id, file.raw as File).then(async () => {
    ElMessage.success('照片已上传，等待下发')
    faces.value = await listEmployeeFaces(faceEmployee.value!.id)
    await load()
  })
}

onMounted(async () => {
  depts.value = await listDepts()
  await load()
})
</script>
