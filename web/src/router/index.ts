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
          path: 'visit',
          name: 'visit',
          component: () => import('@/views/VisitView.vue'),
          meta: { title: '来访登记', adminOnly: true }
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
          component: () => import('@/views/TrafficView.vue'),
          meta: { title: '通行记录', adminOnly: true }
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
        },
        {
          path: 'role-qr',
          name: 'role-qr',
          component: () => import('@/views/RoleQrView.vue'),
          meta: { title: '角色二维码', adminOnly: true }
        },
        {
          path: 'wx-bindings',
          name: 'wx-bindings',
          component: () => import('@/views/WxBindingView.vue'),
          meta: { title: '微信身份', adminOnly: true }
        },
        {
          path: 'dict',
          name: 'dict',
          component: () => import('@/views/DictView.vue'),
          meta: { title: '基础字典', adminOnly: true }
        }
      ]
    },
    {
      path: '/no-permission',
      name: 'no-permission',
      component: () => import('@/views/NoPermissionView.vue'),
      meta: { title: '无访问权限' }
    },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})

/** 页面权限（C-73）：路由名 → 权限码，与后端 sys_role.perms 一致 */
const ROUTE_PERMS: Record<string, string> = {
  guard: 'dashboard:read',
  visit: 'visit:read',
  devices: 'device:read',
  gates: 'gate:read',
  depts: 'dept:read',
  employees: 'employee:read',
  blacklist: 'blacklist:read',
  users: 'user:read',
  audit: 'audit:read',
  ops: 'ops:read',
  report: 'report:read',
  appointments: 'appointment:read',
  events: 'event:read',
  batches: 'batch:read',
  'approval-config': 'approval:read',
  'role-qr': 'user:read',
  'wx-bindings': 'user:read',
  dict: 'visit:read'
}

/** 落地页顺序：第一个有权限的页面 */
const HOME_PATHS: [string, string][] = [
  ['/', 'dashboard:read'],
  ['/visit', 'visit:read'],
  ['/appointments', 'appointment:read'],
  ['/devices', 'device:read'],
  ['/events', 'event:read'],
  ['/report', 'report:read'],
  ['/users', 'user:read']
]

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.public) {
    return true
  }
  if (!auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/no-permission') {
    return true
  }
  const perm = ROUTE_PERMS[String(to.name ?? '')]
  if (perm && !auth.hasPerm(perm)) {
    const home = HOME_PATHS.find(([, code]) => auth.hasPerm(code))?.[0] || '/no-permission'
    return to.path === home ? true : { path: home }
  }
  return true
})

export default router
