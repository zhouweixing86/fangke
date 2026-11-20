<template>
  <view class="page">
    <view class="card head">
      <view class="avatar">{{ initial }}</view>
      <view>
        <view class="name">{{ info?.name || auth.name }}</view>
        <view class="muted">{{ roleText }} · {{ info?.phoneMask || auth.phoneMask }}</view>
      </view>
    </view>

    <view class="card">
      <view class="row"><text class="row-label">角色</text><text>{{ roleText }}</text></view>
      <view v-if="info?.personNo" class="row"><text class="row-label">人员编号</text><text>{{ info.personNo }}</text></view>
      <view v-if="info?.deptName" class="row"><text class="row-label">部门</text><text>{{ info.deptName }}</text></view>
      <view v-if="info?.company" class="row"><text class="row-label">单位</text><text>{{ info.company }}</text></view>
    </view>

    <view v-if="!isEmployee()" class="card">
      <view class="card-title">
        <text>人脸通行</text>
        <text class="tag" :class="info?.faceStatus ? 'tag--ok' : 'tag--info'">
          {{ info?.faceStatus ? '已采集' : '未采集' }}
        </text>
      </view>
      <view class="muted" style="padding-bottom: 8px">
        采集后进厂可直接刷脸；未采集也能刷身份证或出示通行码
      </view>
      <button class="btn btn--ghost" :loading="uploading" @click="pickFace">
        {{ info?.faceStatus ? '重新采集' : '采集人脸' }}
      </button>
    </view>

    <button class="btn btn--danger btn--block" @click="onLogout">退出登录</button>
    <view class="muted" style="text-align: center; margin-top: 12px">访客通行 v1.0 · 如遇问题请联系前台</view>

    <TabBar :items="tabbar" />
  </view>
</template>

<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import TabBar from '@/components/TabBar.vue'
import { appMe, uploadVisitorFace } from '@/api'
import type { AppMe } from '@/api/types'
import { auth, isEmployee, logout, requireLogin } from '@/store/auth'
import { alert, confirm } from '@/utils/ui'

const info = ref<AppMe | null>(null)
const tabbar = ref<any[]>([])
const uploading = ref(false)

const roleText = computed(() => (isEmployee() ? '员工' : '访客'))
const initial = computed(() => (info.value?.name || auth.name || '?').slice(0, 1))

/** 访客采集人脸：选图 → 上传 → 刷新状态 */
function pickFace() {
  uni.chooseImage({
    count: 1,
    sizeType: ['compressed'],
    sourceType: ['camera', 'album'],
    success: async (res: any) => {
      const path = (res.tempFilePaths as string[])[0]
      if (!path) return
      uploading.value = true
      try {
        const result = await uploadVisitorFace(path)
        await alert(
          result.devices > 0
            ? `人脸已采集，已同步到 ${result.success} 台设备`
            : '人脸已采集。审批通过后进厂可直接刷脸',
          '采集成功'
        )
        info.value = await appMe()
      } catch {
        // toast 已在 api 层提示
      } finally {
        uploading.value = false
      }
    }
  })
}

async function onLogout() {
  if (await confirm('确认退出登录？', '退出')) logout()
}

onShow(async () => {
  if (!requireLogin('APP')) return
  tabbar.value = isEmployee()
    ? [
        { text: '待我审批', path: '/pages/employee/todo' },
        { text: '消息', path: '/pages/common/messages' },
        { text: '我的', path: '/pages/common/profile' }
      ]
    : [
        { text: '消息', path: '/pages/common/messages' },
        { text: '我的', path: '/pages/common/profile' }
      ]
  try {
    info.value = await appMe()
  } catch {
    info.value = null
  }
})
</script>

<style scoped>
.head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.avatar {
  width: 46px;
  height: 46px;
  line-height: 46px;
  border-radius: 50%;
  background: #1989fa;
  color: #fff;
  text-align: center;
  font-size: 20px;
}

.name {
  font-size: 16px;
  font-weight: 600;
}
</style>
