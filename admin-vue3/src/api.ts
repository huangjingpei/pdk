import axios from 'axios';
import { authState, clearSession } from './auth';

export interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 15000,
});

api.interceptors.request.use((config) => {
  const session = authState.session;
  if (session) {
    config.headers[session.tokenName || 'satoken'] = session.tokenValue;
  }
  return config;
});

api.interceptors.response.use((response) => {
  const body = response.data as ApiResult<unknown>;
  if (body && typeof body.code === 'number' && body.code !== 200) {
    if (body.code === 40100 || body.code === 40110) {
      clearSession();
    }
    return Promise.reject(new Error(body.message));
  }
  return response;
});
