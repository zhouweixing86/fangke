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
      <el-table-column label="车牌" width="110">
        <template #default="{ row }">{{ row.plateNo || '-' }}</template>
      </el-table-column>
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
          <el-button v-if="row.status === 3" link type="success" @click="enable(row)">恢复在职</el-button>
          <el-button v-else link type="danger" @click="stop(row)">停用</el-button>
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
        <el-form-item label="车牌号">
          <el-input v-model="form.plateNo" maxlength="16" placeholder="如 京A12345（二期车辆识别用）" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" placeholder="11 位手机号，留空表示不修改" />
        </el-form-item>
        <el-form-item label="身份证号" prop="idCard">
          <el-input v-model="form.idCard" placeholder="18 位，留空表示不修改" />
        </el-form-item>
        <el-form-item label="有效期">
          <div class="date-range">
            <el-date-picker v-model="validFrom" type="date" value-format="YYYY-MM-DD"
              placeholder="开始日期（可不填）" style="width: 46%" />
            <span class="date-sep">至</span>
            <el-date-picker v-model="validTo" type="date" value-format="YYYY-MM-DD"
              placeholder="结束日期（可不填）" style="width: 46%" />
          </div>
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
        title="≤2MB 直接上传；超过 2MB 自动压缩。分辨率不低于 400×400，单人正面。上传即生效并进入待下发"
        style="margin-bottom: 12px" />
      <label class="face-drop" :class="{ 'is-over': dragOver }" for="face-file-input"
        @dragover.prevent="dragOver = true" @dragleave.prevent="dragOver = false" @drop.prevent="onFaceDrop">
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">将照片拖到此处，或<em>点击选择</em></div>
      </label>
      <input id="face-file-input" ref="faceInput" class="face-file-input" type="file"
        accept="image/jpeg,image/png" @change="onFacePicked" />

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
  enableEmployee,
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
const validFrom = ref<string | null>(null)
const validTo = ref<string | null>(null)
const form = reactive<Record<string, any>>({
  id: null,
  empNo: '',
  name: '',
  deptId: null,
  title: '',
  plateNo: '',
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
  Object.assign(form, { id: null, empNo: '', name: '', deptId: null, title: '', plateNo: '', phone: '', idCard: '', remark: '' })
  validFrom.value = null
  validTo.value = null
  formVisible.value = true
}

function openEdit(row: EmployeeView) {
  Object.assign(form, {
    id: row.id,
    empNo: row.empNo,
    name: row.name,
    deptId: row.deptId,
    title: row.title,
    plateNo: row.plateNo,
    phone: '',
    idCard: '',
    remark: row.remark
  })
  validFrom.value = row.validFrom || null
  validTo.value = row.validTo || null
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
    validFrom: validFrom.value || null,
    validTo: validTo.value || null,
    remark: form.remark,
    plateNo: form.plateNo || undefined
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

/** 恢复在职：等于重新新增一遍，授权与设备名单会重新下发 */
async function enable(row: EmployeeView) {
  await ElMessageBox.confirm(
    `恢复后「${row.name}」会重新下发到设备，可再次刷脸通行。确认？`,
    '恢复在职',
    { type: 'info' }
  )
  try {
    await enableEmployee(row.id)
    ElMessage.success('已恢复在职，名单正在下发')
    await load()
  } catch (error) {
    const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(message || '恢复失败')
  }
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

const faceInput = ref<HTMLInputElement | null>(null)
const dragOver = ref(false)

function onFacePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = '' // 允许连续选同一个文件
  if (file) void uploadFaceFile(file)
}

function onFaceDrop(event: DragEvent) {
  dragOver.value = false
  const file = event.dataTransfer?.files?.[0]
  if (file) void uploadFaceFile(file)
}

async function uploadFaceFile(file: File) {
  if (!faceEmployee.value) return
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请选择图片文件（JPG / PNG）')
    return
  }
  try {
    await uploadEmployeeFace(faceEmployee.value.id, file)
    ElMessage.success('照片已上传，等待下发')
    faces.value = await listEmployeeFaces(faceEmployee.value.id)
    await load()
  } catch (error) {
    const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(message || '照片上传失败')
  }
}

onMounted(async () => {
  depts.value = await listDepts()
  await load()
})
</script>

<style scoped>
.date-range {
  display: flex;
  align-items: center;
  width: 100%;
}

.date-sep {
  width: 8%;
  text-align: center;
  color: #909399;
}

.face-drop {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 28px 16px;
  border: 1px dashed #c0c4cc;
  border-radius: 6px;
  background: #fafafa;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
  user-select: none;
}

.face-drop:hover,
.face-drop.is-over {
  border-color: #409eff;
  background: #ecf5ff;
}

.face-drop .el-icon--upload {
  font-size: 48px;
  color: #a8abb2;
  margin-bottom: 8px;
}

.face-drop .el-upload__text {
  font-size: 14px;
  color: #606266;
}

.face-drop .el-upload__text em {
  color: #409eff;
  font-style: normal;
}

/* 视觉隐藏但仍可被 label 触发；不要用 display:none，个别浏览器会拦截 */
.face-file-input {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  border: 0;
}
</style>
