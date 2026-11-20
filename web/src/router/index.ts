import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true, title: '登录' }
    },
    {
      path: '/',
      component: () => import('@/layouts/MainLayout.vue'),
      children: [
        {
          path: '',
          name: 'guard',
          component: () => import('@/views/GuardView.vue'),
          meta: { title: '保安工作台', guardOnly: true }
        },
        {
          path: 'devices',
          name: 'devices',
          component: () => import('@/views/DeviceView.vue'),
          meta: { title: '设备管理', adminOnly: true }
        },
        {
          path: 'gates',
          name: 'gates',
          component: () => import('@/views/GateView.vue'),
          meta: { title: '门点管理', adminOnly: true }
        },
        {
          path: 'depts',
          name: 'depts',
          component: () => import('@/views/DeptView.vue'),
          meta: { title: '部门管理', adminOnly: true }
        },
        {
          path: 'employees',
          name: 'employees',
          component: () => import('@/views/EmployeeView.vue'),
          meta: { title: '员工管理', adminOnly: true }
        },
        {
          path: 'blacklist',
          name: 'blacklist',
          component: () => import('@/views/BlacklistView.vue'),
          meta: { title: '黑名单', adminOnly: true }
        },
        {
          path: 'users',
          name: 'users',
          component: () => import('@/views/UserView.vue'),
          meta: { title: '账号管理', adminOnly: true }
        },
        {
          path: 'audit',
          name: 'audit',
          component: () => import('@/views/AuditView.vue'),
          meta: { title: '审计日志', adminOnly: true }
        },
        {
          path: 'ops',
          name: 'ops',
          component: () => import('@/views/OpsView.vue'),
          meta: { title: '运维中心', adminOnly: true }
        },
        {
          path: 'report',
          name: 'report',
          component: () => import('@/views/ReportView.vue'),
          meta: { title: '报表统计', adminOnly: true }
        },
        {
          path: 'appointments',
          name: 'appointments',
          component: () => import('@/views/AppointmentView.vue'),
          meta: { title: '预约查询', adminOnly: true }
        },
        {
          path: 'events',
          name: 'events',
          component: () => import('@/views/AccessEventView.vue'),
          meta: { title: '通行记录', adminOnly: true }
        },
        {
          path: 'vehicles',
          name: 'vehicles',
          component: () => import('@/views/VehicleView.vue'),
          meta: { title: '车辆记录', adminOnly: true }
        },
        {
          path: 'batches',
          name: 'batches',
          component: () => import('@/views/SyncBatchView.vue'),
          meta: { title: '下发批次', adminOnly: true }
        },
        {
          path: 'approval-config',
          name: 'approval-config',
          component: () => import('@/views/ApprovalConfigView.vue'),
          meta: { title: '审批配置', adminOnly: true }
        }
      ]
    },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.public) {
    return true
  }
  if (!auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // 保安的落地页是工作台，其余角色是设备页；被拦时回到自己的落地页，避免互相重定向成环
  const home = auth.isGuard ? '/' : '/devices'
  const blocked =
    (to.meta.guardOnly && !auth.isGuard) || (to.meta.adminOnly && !auth.isAdmin)
  if (blocked && to.path !== home) {
    return { path: home }
  }
  return true
})

export default router
