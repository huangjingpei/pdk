import { createRouter, createWebHistory } from 'vue-router';
import Dashboard from '../views/dashboard/Dashboard.vue';
import IncomeAudit from '../views/finance/IncomeAudit.vue';
import ExpenseAudit from '../views/finance/ExpenseAudit.vue';
import CardGenerator from '../views/card/CardGenerator.vue';
import TokenPoolManager from '../views/token/TokenPoolManager.vue';
import TestingWorkbench from '../views/testing/TestingWorkbench.vue';
import Login from '../views/auth/Login.vue';
import ChangePassword from '../views/auth/ChangePassword.vue';
import UserManager from '../views/user/UserManager.vue';
import SystemConfig from '../views/settings/SystemConfig.vue';
import PackageManager from '../views/package/PackageManager.vue';
import AdminManager from '../views/admin/AdminManager.vue';
import BusinessManager from '../views/business/BusinessManager.vue';
import LoginLog from '../views/log/LoginLog.vue';
import AuditLog from '../views/log/AuditLog.vue';
import DeviceLicenseManager from '../views/license/DeviceLicenseManager.vue';
import ClientUpdateManager from '../views/update/ClientUpdateManager.vue';
import { hasPermission, isLoggedIn, authState } from '../auth';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: Login,
      meta: { title: '管理员登录', public: true },
    },
    {
      // bare = 不带侧边栏布局渲染（App.vue 对 bare 路由只渲染 router-view）
      path: '/change-password',
      name: 'ChangePassword',
      component: ChangePassword,
      meta: { title: '修改登录密码', bare: true },
    },
    {
      path: '/',
      redirect: '/dashboard',
    },
    {
      path: '/dashboard',
      name: 'Dashboard',
      component: Dashboard,
      meta: { title: '运营大盘', permission: 'dashboard:view' },
    },
    {
      path: '/finance/income',
      name: 'IncomeAudit',
      component: IncomeAudit,
      meta: { title: '实收流水', permission: 'finance:view' },
    },
    {
      path: '/finance/expense',
      name: 'ExpenseAudit',
      component: ExpenseAudit,
      meta: { title: '采购支出', permission: 'finance:view' },
    },
    {
      path: '/sales',
      name: 'SalesRecords',
      component: IncomeAudit,
      meta: { title: '销售记录', permission: 'sales:view' },
    },
    {
      path: '/card/generator',
      name: 'CardGenerator',
      component: CardGenerator,
      meta: { title: '激活码池', permission: 'card:view' },
    },
    {
      path: '/token/pool',
      name: 'TokenPoolManager',
      component: TokenPoolManager,
      meta: { title: '调度中心', permission: 'token:view' },
    },
    {
      path: '/testing/workbench',
      name: 'TestingWorkbench',
      component: TestingWorkbench,
      meta: { title: '测试平台', permission: 'dispatch:view' },
    },
    {
      path: '/package/manager',
      name: 'PackageManager',
      component: PackageManager,
      meta: { title: '套餐版本', permission: 'package:view' },
    },
    {
      path: '/user/manager',
      name: 'UserManager',
      component: UserManager,
      meta: { title: '用户管理', permission: 'user:view' },
    },
    {
      path: '/license/manager',
      name: 'DeviceLicenseManager',
      component: DeviceLicenseManager,
      meta: { title: '设备许可证', permission: 'card:view' },
    },
    {
      path: '/updates/client',
      name: 'ClientUpdateManager',
      component: ClientUpdateManager,
      meta: { title: '客户端升级', permission: 'client-update:view' },
    },
    {
      path: '/business/manager',
      name: 'BusinessManager',
      component: BusinessManager,
      meta: { title: '业务管理', permission: 'business:view' },
    },
    {
      path: '/settings',
      name: 'SystemConfig',
      component: SystemConfig,
      meta: { title: '系统设置', permission: 'system:config' },
    },
    {
      path: '/admin/manager',
      name: 'AdminManager',
      component: AdminManager,
      meta: { title: '账号管理', permission: 'admin:manage' },
    },
    {
      path: '/logs/login',
      name: 'LoginLog',
      component: LoginLog,
      meta: { title: '登录日志', permission: 'log:view' },
    },
    {
      path: '/logs/audit',
      name: 'AuditLog',
      component: AuditLog,
      meta: { title: '操作审计', permission: 'log:view' },
    },
  ],
});

router.beforeEach((to) => {
  // 首次登录 / 密码被重置：未改密前锁死在改密页，其他任何页面都不放行
  if (isLoggedIn.value && authState.session?.mustChangePassword && to.path !== '/change-password') {
    return '/change-password';
  }
  if (to.meta.public) {
    if (isLoggedIn.value) {
      return authState.session?.mustChangePassword ? '/change-password' : '/dashboard';
    }
    return true;
  }
  if (!isLoggedIn.value) {
    return { path: '/login', query: { redirect: to.fullPath } };
  }
  const permission = to.meta.permission as string | undefined;
  if (!hasPermission(permission)) {
    return '/dashboard';
  }
  return true;
});

export default router;
