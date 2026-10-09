import axios, { AxiosError, AxiosRequestConfig } from 'axios';
import { tokenStore } from './tokenStore';

/**
 * Where the API lives. VITE_API_BASE_URL is the backend origin, for deployments that serve
 * the dashboard from a different origin than the API (e.g. a static host such as Vercel).
 * Left unset, requests stay same-origin and rely on a reverse proxy forwarding /api to the
 * backend (the Vite dev server, or nginx in the Docker image).
 */
const API_ORIGIN = (import.meta.env.VITE_API_BASE_URL ?? '').trim().replace(/\/+$/, '');
const API_BASE_URL = `${API_ORIGIN}/api/v1`;

/**
 * The API client. A request interceptor attaches the access token (Requirement 13.3); a
 * response interceptor transparently refreshes on 401 and retries once (Requirement 13.4),
 * and ends the session if the refresh token is rejected (Requirement 13.5).
 */
export const api = axios.create({
  baseURL: API_BASE_URL,
  // Generous default so a cold-started backend (free hosting can take ~60-90s to wake)
  // doesn't fail the first request. The transfer call overrides this with a 30s timeout
  // to honor the dashboard's transfer timeout requirement.
  timeout: 90_000,
});

export interface ApiErrorBody {
  error: { code: string; message: string; fields?: { field: string; issue: string }[] };
}

api.interceptors.request.use((config) => {
  const token = tokenStore.getAccessToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

let refreshing: Promise<string> | null = null;

async function refreshAccessToken(): Promise<string> {
  const refreshToken = tokenStore.getRefreshToken();
  if (!refreshToken) {
    throw new Error('no refresh token');
  }
  // Use a bare axios call to avoid recursive interceptors.
  const { data } = await axios.post(`${API_BASE_URL}/auth/refresh`, { refreshToken });
  const newAccess = data.data.accessToken as string;
  tokenStore.setAccessToken(newAccess);
  return newAccess;
}

api.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const original = error.config as (AxiosRequestConfig & { _retried?: boolean }) | undefined;
    const status = error.response?.status;

    if (status === 401 && original && !original._retried && tokenStore.getRefreshToken()) {
      original._retried = true;
      try {
        refreshing = refreshing ?? refreshAccessToken();
        const newAccess = await refreshing;
        refreshing = null;
        original.headers = original.headers ?? {};
        (original.headers as Record<string, string>).Authorization = `Bearer ${newAccess}`;
        return api.request(original);
      } catch (refreshError) {
        refreshing = null;
        tokenStore.endSession(); // Requirement 13.5
        return Promise.reject(refreshError);
      }
    }
    return Promise.reject(error);
  },
);

/** Extracts the machine-readable error code from an Axios error, if present. */
export function errorCodeOf(error: unknown): string | null {
  if (axios.isAxiosError(error)) {
    const body = error.response?.data as ApiErrorBody | undefined;
    if (body?.error?.code) return body.error.code;
    if (error.code === 'ECONNABORTED') return 'CLIENT_TIMEOUT';
  }
  return null;
}
