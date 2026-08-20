<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <div class="brand">PDK</div>
      <h1>云控管理后台</h1>
      <p>多角色任务与权限控制</p>
      <el-form :model="form" label-position="top" @keyup.enter="submit">
        <el-form-item label="管理员账号">
          <el-input v-model="form.username" size="large" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" size="large" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-button type="primary" size="large" :loading="loading" style="width: 100%" @click="submit">登录</el-button>
      </el-form>
      <el-alert class="demo-tip" type="info" :closable="false" title="本地超级管理员：13454118762 / admin123；合伙人（代理商）账号由超级管理员在「管理员与合伙人」中创建，使用其登录账号与密码进入同一套后台" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { api, type ApiResult } from '../../api';
import { setSession, type AdminSession } from '../../auth';

const router = useRouter();
const loading = ref(false);
const form = reactive({ username: '13454118762', password: 'admin123' });

async function submit(): Promise<void> {
  loading.value = true;
  try {
    const response = await api.post<ApiResult<AdminSession>>('/api/v1/admin/auth/login', form);
    setSession(response.data.data);
    ElMessage.success('登录成功');
    await router.replace('/dashboard');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '登录失败');
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.login-page { min-height: 100vh; display: grid; place-items: center; background: linear-gradient(135deg, #0f172a, #1e3a8a); }
.login-card { width: 410px; border: 0; border-radius: 16px; }
.brand { width: 52px; height: 52px; margin: 0 auto; display: grid; place-items: center; border-radius: 14px; background: #2563eb; color: white; font-weight: 800; }
h1 { margin: 16px 0 4px; text-align: center; color: #172033; }
p { margin: 0 0 24px; text-align: center; color: #64748b; }
.demo-tip { margin-top: 20px; }
</style>
