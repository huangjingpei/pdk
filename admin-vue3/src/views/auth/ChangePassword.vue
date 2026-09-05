<template>
  <div class="change-page">
    <el-card class="change-card" shadow="always">
      <template #header>
        <div class="change-header">
          <el-icon :size="22" color="#6366f1"><Lock /></el-icon>
          <div>
            <div class="change-title">修改登录密码</div>
            <div class="change-sub">首次登录或密码被重置后，需要先设置新密码才能进入系统</div>
          </div>
        </div>
      </template>

      <el-alert
        v-if="forced"
        type="warning"
        :closable="false"
        show-icon
        title="当前使用的是系统生成的初始密码，为了账号安全必须修改后方可继续使用后台"
        style="margin-bottom: 18px"
      />

      <el-form :model="form" label-position="top" @keyup.enter="submit">
        <el-form-item label="当前密码">
          <el-input v-model="form.oldPassword" type="password" show-password placeholder="请输入当前使用的密码" autocomplete="current-password" />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="form.newPassword" type="password" show-password placeholder="至少 8 位，建议字母 + 数字组合" autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="form.confirmPassword" type="password" show-password placeholder="再次输入新密码" autocomplete="new-password" />
        </el-form-item>
        <el-button type="primary" style="width: 100%" :loading="submitting" @click="submit">
          确认修改
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { api, type ApiResult } from '../../api';
import { authState, clearSession } from '../../auth';

const router = useRouter();
const submitting = ref(false);
const forced = computed(() => Boolean(authState.session?.mustChangePassword));

const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' });

async function submit(): Promise<void> {
  if (!form.oldPassword || !form.newPassword) {
    ElMessage.warning('请填写完整');
    return;
  }
  if (form.newPassword.length < 8) {
    ElMessage.warning('新密码至少 8 位');
    return;
  }
  if (form.newPassword !== form.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致');
    return;
  }
  submitting.value = true;
  try {
    await api.post<ApiResult<string>>('/api/v1/admin/auth/change-password', {
      oldPassword: form.oldPassword,
      newPassword: form.newPassword,
    });
    ElMessage.success('密码修改成功，请使用新密码重新登录');
    clearSession();
    await router.replace('/login');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '修改失败');
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped>
.change-page { min-height: 100vh; display: grid; place-items: center; background: linear-gradient(135deg, #0f172a, #1e3a8a); }
.change-card { width: 440px; border: 0; border-radius: 16px; }
.change-header { display: flex; align-items: center; gap: 12px; }
.change-title { font-weight: 700; font-size: 17px; color: #0f172a; }
.change-sub { font-size: 12px; color: #94a3b8; margin-top: 2px; }
</style>
