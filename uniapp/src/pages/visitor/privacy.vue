<template>
  <view class="page">
    <view class="sheet">
      <view class="title">二三五处出入库登记管理 隐私政策</view>
      <view class="meta">版本 v1.0 ｜ 生效日期 2026-11-27</view>

      <view class="p">
        本小程序由国家粮食和物资储备局黑龙江局二三五处（以下简称“本单位”）用于来访人员出入库登记与门禁核验。
        我们按照最小必要原则处理您的个人信息，本政策说明我们收集哪些信息、为什么收集以及您享有的权利。
      </view>

      <view class="h">一、我们收集的信息</view>
      <view class="p">1. 姓名、手机号：用于访客身份识别、被访人与审批人联系、通行核验。属于必需信息。</view>
      <view class="p">2. 身份证号（仅访客本人）：用于厂区出入口门禁设备刷证核验。属于必需信息。</view>
      <view class="p">3. 公司名称、职务、车牌号：用于来访登记与车辆放行。属于选填信息。</view>
      <view class="p">4. 随行人员姓名、手机号：用于同行人员登记与安全核验。属于选填信息。</view>
      <view class="p">5. 出入口现场抓拍照片：用于出入口安全留痕，仅本单位内部留存。属于必需信息。</view>
      <view class="p">6. 微信 OpenID：用于识别同一微信用户并关联本人预约记录。属于必需信息。</view>

      <view class="h">二、我们如何使用信息</view>
      <view class="p">仅用于来访预约登记、审批流转、出入口通行核验、安全事件追溯与统计报表，不用于商业营销，不进行用户画像与自动化决策。</view>

      <view class="h">三、存储与保护</view>
      <view class="p">1. 手机号、身份证号等敏感信息加密存储，页面展示时脱敏处理。</view>
      <view class="p">2. 数据存放于本单位自有服务器，不出境、不向第三方出售或共享。</view>
      <view class="p">3. 留存期限按本单位出入库管理相关规定执行，到期后删除或匿名化处理。</view>

      <view class="h">四、第三方共享</view>
      <view class="p">除法律法规要求、司法机关或行政机关依法调取、以及安全事件处置所必需外，我们不向任何第三方提供您的个人信息。</view>

      <view class="h">五、您的权利</view>
      <view class="p">您可以联系门卫或系统管理员查询、更正、删除本人的个人信息，也可以撤回同意。撤回同意后，我们将不再处理相应信息，但不影响撤回前已进行的处理。</view>

      <view class="h">六、联系我们</view>
      <view class="p">如对本政策有疑问，请联系本单位值班室（电话请以现场公示为准）。</view>

      <view class="p tip">继续使用本小程序并提交预约，即表示您已阅读并同意本政策。</view>
    </view>
  </view>
</template>

<script setup lang="ts"></script>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f6f8;
  padding: 16rpx 24rpx 40rpx;
  box-sizing: border-box;
}
.sheet {
  background: #fff;
  border-radius: 16rpx;
  padding: 32rpx 28rpx;
}
.title {
  font-size: 34rpx;
  font-weight: 600;
  color: #1a1a1a;
  line-height: 1.5;
}
.meta {
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #999;
}
.h {
  margin-top: 28rpx;
  font-size: 30rpx;
  font-weight: 600;
  color: #1a1a1a;
}
.p {
  margin-top: 12rpx;
  font-size: 27rpx;
  line-height: 1.75;
  color: #444;
}
.tip {
  margin-top: 28rpx;
  color: #666;
  font-size: 25rpx;
  background: #f7f8fa;
  border-radius: 12rpx;
  padding: 16rpx 18rpx;
}
</style>
