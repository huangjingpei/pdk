import { computed, reactive } from 'vue';

export interface AdminSession {
  tokenName: string;
  tokenValue: string;
  id: number;
  username: string;
  displayName: string;
  role: string;
  permissions: string[];
  invitationCode?: string;
}

const STORAGE_KEY = 'pdk-admin-session';

function loadSession(): AdminSession | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) as AdminSession : null;
  } catch {
    return null;
  }
}

export const authState = reactive<{ session: AdminSession | null }>({
  session: loadSession(),
});

export const isLoggedIn = computed(() => Boolean(authState.session?.tokenValue));

export function setSession(session: AdminSession): void {
  authState.session = session;
  localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
}

export function clearSession(): void {
  authState.session = null;
  localStorage.removeItem(STORAGE_KEY);
}

export function hasPermission(permission?: string): boolean {
  return !permission || Boolean(authState.session?.permissions.includes(permission));
}
