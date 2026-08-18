<template>
  <el-container class="layout-container" style="height: 100vh;">
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

        <el-sub-menu index="finance">
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

        <el-menu-item index="/card/generator">
          <el-icon><Key /></el-icon>
          <span>卡密制卡凭证池</span>
        </el-menu-item>

        <el-menu-item index="/token/pool">
          <el-icon><Connection /></el-icon>
          <span>拼多多 Token 调度池</span>
        </el-menu-item>

        <el-menu-item index="/testing/workbench">
          <el-icon><Aim /></el-icon>
          <span>人工测试全链路工作台</span>
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
            <el-avatar :size="28" src="https://cube.elemecdn.com/0/88/03b0d39583f48206768a7534e55bcpng.png" />
            <span class="font-medium text-slate-700">super_admin</span>
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
import { useRoute } from 'vue-router';

const route = useRoute();
const activeRoute = computed(() => route.path);
</script>

<style>
body {
  margin: 0;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
}
.el-menu-vertical-pdk {
  border-right: none !important;
}
.el-menu-item.is-active {
  background-color: #3b82f6 !important;
  font-weight: 600;
}
</style>
