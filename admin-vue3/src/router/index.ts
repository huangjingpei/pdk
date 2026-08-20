import { createRouter, createWebHistory } from 'vue-router';
import Dashboard from '../views/dashboard/Dashboard.vue';
import IncomeAudit from '../views/finance/IncomeAudit.vue';
import ExpenseAudit from '../views/finance/ExpenseAudit.vue';
import CardGenerator from '../views/card/CardGenerator.vue';
import TokenPoolManager from '../views/token/TokenPoolManager.vue';
import TestingWorkbench from '../views/testing/TestingWorkbench.vue';
import Login from '../views/auth/Login.vue';
import UserManager from '../views/user/UserManager.vue';
import SystemConfig from '../views/settings/SystemConfig.vue';
import PackageManager from '../views/package/PackageManager.vue';
import AdminManager from '../views/admin/AdminManager.vue';
import { hasPermission, isLoggedIn } from '../auth';

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
  ],
});

router.beforeEach((to) => {
  if (to.meta.public) {
    return isLoggedIn.value && to.path === '/login' ? '/dashboard' : true;
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
