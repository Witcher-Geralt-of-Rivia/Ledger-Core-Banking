/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Backend origin for cross-origin deployments. Unset means same-origin `/api`. */
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
