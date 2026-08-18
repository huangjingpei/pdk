<template>
  <router-view v-if="route.meta.public" />
  <el-container v-else class="layout-container" style="height: 100vh;">
    <!-- 侧边导航栏 -->
    <el-aside width="240px" class="bg-[#0f172a] text-white">
      <div class="p-4 border-b border-slate-700 flex items-center gap-3">
        <div class="w-8 h-8 rounded-lg bg-indigo-600 flex items-center justify-center font-bold text-white shadow-md">
          PDK
        </div>
        <div>
          <div class="font-bold text-sm text-slate-100">拼多多云控商业化</div>
          <div class="text-xs text-slate-400">Spring Boot 3 + Vue 3</div>
        </div>
      </div>

      <el-menu
        :default-active="activeRoute"
        class="el-menu-vertical-pdk"
        background-color="#0f172a"
        text-color="#94a3b8"
        active-text-color="#ffffff"
        router
      >
        <el-menu-item index="/dashboard">
          <el-icon><DataAnalysis /></el-icon>
          <span>财务与运营大盘</span>
        </el-menu-item>

        <el-sub-menu v-if="hasPermission('finance:view')" index="finance">
          <template #title>
            <el-icon><Money /></el-icon>
            <span>财务独立对账</span>
          </template>
          <el-menu-item index="/finance/income">
            <el-icon><Tickets /></el-icon>
            <span>实收流水 (独立表)</span>
          </el-menu-item>
          <el-menu-item index="/finance/expense">
            <el-icon><ShoppingCart /></el-icon>
            <span>Token 采购支出</span>
          </el-menu-item>
        </el-sub-menu>

        <el-menu-item v-if="hasPermission('sales:view') && !hasPermission('finance:view')" index="/sales">
          <el-icon><Tickets /></el-icon>
          <span>销售与续费记录</span>
        </el-menu-item>

        <el-menu-item v-if="hasPermission('card:view')" index="/card/generator">
          <el-icon><Key /></el-icon>
          <span>卡密制卡凭证池</span>
        </el-menu-item>

        <el-menu-item v-if="hasPermission('package:view')" index="/package/manager">
          <el-icon><Box /></el-icon>
          <span>套餐版本中心</span>
        </el-menu-item>

        <el-menu-item v-if="hasPermission('token:view')" index="/token/pool">
          <el-icon><Connection /></el-icon>
          <span>拼多多 Token 调度池</span>
        </el-menu-item>

        <el-menu-item v-if="hasPermission('user:view')" index="/user/manager">
          <el-icon><User /></el-icon>
          <span>客户端用户与电脑</span>
        </el-menu-item>

        <el-menu-item v-if="hasPermission('dispatch:view')" index="/testing/workbench">
          <el-icon><Aim /></el-icon>
          <span>人工测试全链路工作台</span>
        </el-menu-item>

        <el-menu-item v-if="hasPermission('system:config')" index="/settings">
          <el-icon><Setting /></el-icon>
          <span>系统设置</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <!-- 主内容区 -->
    <el-container>
      <el-header height="56px" class="bg-white border-b border-slate-200 flex items-center justify-between px-6">
        <div class="text-sm font-medium text-slate-700">
          拼多多采集分发云控管理后台 (企业安全生产版本)
        </div>
        <div class="flex items-center gap-4 text-xs text-slate-500">
          <span class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-emerald-50 text-emerald-700 font-medium border border-emerald-200">
            <span class="w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></span>
            后端集群就绪: localhost:8080
          </span>
          <div class="flex items-center gap-2">
            <el-tag v-if="authState.session?.invitationCode" type="primary">邀请码：{{ authState.session.invitationCode }}</el-tag>
            <el-avatar :size="28" src="https://cube.elemecdn.com/0/88/03b0d39583f48206768a7534e55bcpng.png" />
            <span class="font-medium text-slate-700">{{ authState.session?.displayName }} · {{ roleName }}</span>
            <el-button link type="primary" @click="logout">退出</el-button>
          </div>
        </div>
      </el-header>

      <el-main class="bg-slate-50 p-6 overflow-y-auto">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from './api';
import { authState, clearSession, hasPermission } from './auth';

const route = useRoute();
const router = useRouter();
const activeRoute = computed(() => route.path);
const roleNames: Record<string, string> = {
  SUPER_ADMIN: '超级管理员', PARTNER: '代理商',
};
const roleName = computed(() => roleNames[authState.session?.role || ''] || authState.session?.role || '');

async function logout(): Promise<void> {
  try { await api.post('/api/v1/admin/auth/logout'); } catch { /* 本地会话仍需清理 */ }
  clearSession();
  await router.replace('/login');
}
</script>

<style>
body {
  margin: 0;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
}
.bg-\[\#0f172a\] { background: #0f172a; }
.text-white { color: #fff; }
.p-4 { padding: 16px; }
.px-6 { padding-left: 24px; padding-right: 24px; }
.p-6 { padding: 24px; }
.flex { display: flex; }
.items-center { align-items: center; }
.justify-between { justify-content: space-between; }
.gap-2 { gap: 8px; }
.gap-3 { gap: 12px; }
.gap-4 { gap: 16px; }
.bg-white { background: #fff; }
.bg-slate-50 { background: #f8fafc; }
.border-b { border-bottom: 1px solid #e2e8f0; }
.overflow-y-auto { overflow-y: auto; }
.el-menu-vertical-pdk {
  border-right: none !important;
}
.el-menu-item.is-active {
  background-color: #3b82f6 !important;
  font-weight: 600;
}
</style>
