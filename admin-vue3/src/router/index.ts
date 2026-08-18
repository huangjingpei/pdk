import { createRouter, createWebHistory } from 'vue-router';
import Dashboard from '../views/dashboard/Dashboard.vue';
import IncomeAudit from '../views/finance/IncomeAudit.vue';
import ExpenseAudit from '../views/finance/ExpenseAudit.vue';
import CardGenerator from '../views/card/CardGenerator.vue';
import TokenPoolManager from '../views/token/TokenPoolManager.vue';
import TestingWorkbench from '../views/testing/TestingWorkbench.vue';
import Login from '../views/auth/Login.vue';
import UserManager from '../views/user/UserManager.vue';
import PackageManager from '../views/package/PackageManager.vue';
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
      meta: { title: '运营全景', permission: 'dashboard:view' },
    },
    {
      path: '/finance/income',
      name: 'IncomeAudit',
      component: IncomeAudit,
      meta: { title: '实收收入对账流水', permission: 'finance:view' },
    },
    {
      path: '/finance/expense',
      name: 'ExpenseAudit',
      component: ExpenseAudit,
      meta: { title: 'Token采购支出管理', permission: 'finance:view' },
    },
    {
      path: '/sales',
      name: 'SalesRecords',
      component: IncomeAudit,
      meta: { title: '销售与续费记录', permission: 'sales:view' },
    },
    {
      path: '/card/generator',
      name: 'CardGenerator',
      component: CardGenerator,
      meta: { title: '卡密批量制卡与凭证池', permission: 'card:view' },
    },
    {
      path: '/token/pool',
      name: 'TokenPoolManager',
      component: TokenPoolManager,
      meta: { title: '拼多多Token调度池', permission: 'token:view' },
    },
    {
      path: '/testing/workbench',
      name: 'TestingWorkbench',
      component: TestingWorkbench,
      meta: { title: '全链路人工测试工作台', permission: 'dispatch:view' },
    },
    {
      path: '/package/manager',
      name: 'PackageManager',
      component: PackageManager,
      meta: { title: '不可变套餐版本', permission: 'package:view' },
    },
    {
      path: '/user/manager',
      name: 'UserManager',
      component: UserManager,
      meta: { title: '客户端用户与电脑绑定', permission: 'user:view' },
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
