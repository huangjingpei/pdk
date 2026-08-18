import { createRouter, createWebHistory } from 'vue-router';
import Dashboard from '../views/dashboard/Dashboard.vue';
import IncomeAudit from '../views/finance/IncomeAudit.vue';
import ExpenseAudit from '../views/finance/ExpenseAudit.vue';
import CardGenerator from '../views/card/CardGenerator.vue';
import TokenPoolManager from '../views/token/TokenPoolManager.vue';
import TestingWorkbench from '../views/testing/TestingWorkbench.vue';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      redirect: '/dashboard',
    },
    {
      path: '/dashboard',
      name: 'Dashboard',
      component: Dashboard,
      meta: { title: '财务与运营全景' },
    },
    {
      path: '/finance/income',
      name: 'IncomeAudit',
      component: IncomeAudit,
      meta: { title: '实收收入对账流水' },
    },
    {
      path: '/finance/expense',
      name: 'ExpenseAudit',
      component: ExpenseAudit,
      meta: { title: 'Token采购支出管理' },
    },
    {
      path: '/card/generator',
      name: 'CardGenerator',
      component: CardGenerator,
      meta: { title: '卡密批量制卡与凭证池' },
    },
    {
      path: '/token/pool',
      name: 'TokenPoolManager',
      component: TokenPoolManager,
      meta: { title: '拼多多Token调度池' },
    },
    {
      path: '/testing/workbench',
      name: 'TestingWorkbench',
      component: TestingWorkbench,
      meta: { title: '全链路人工测试工作台' },
    },
  ],
});

export default router;
