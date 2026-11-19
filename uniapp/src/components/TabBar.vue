<template>
  <view class="tabbar">
    <view v-for="item in items" :key="item.path" class="tab-item" :class="{ active: current === item.path }"
      @click="go(item.path)">
      <text class="tab-text">{{ item.text }}</text>
      <text v-if="item.badge && item.badge > 0" class="tab-badge">{{ item.badge > 99 ? '99+' : item.badge }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

const props = defineProps<{ items: { text: string; path: string; badge?: number }[] }>()
const current = ref('')

onShow(() => {
  const pages = getCurrentPages()
  const page = pages[pages.length - 1]
  current.value = '/' + (page?.route ?? '')
})

function go(path: string) {
  if (current.value === path) return
  uni.reLaunch({ url: path })
}
</script>

<style scoped>
.tabbar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  height: 56px;
  padding-bottom: env(safe-area-inset-bottom);
  display: flex;
  background: #fff;
  border-top: 1px solid #ebedf0;
  z-index: 99;
}

.tab-item {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  color: #969799;
  font-size: 13px;
}

.tab-item.active {
  color: #1989fa;
  font-weight: 600;
}

.tab-badge {
  position: absolute;
  top: 8px;
  right: 24%;
  min-width: 16px;
  height: 16px;
  line-height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: #ee0a24;
  color: #fff;
  font-size: 10px;
  text-align: center;
}
</style>
