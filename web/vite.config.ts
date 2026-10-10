// @find: vite config, build config, dev server, proxy, api proxy, plugins, aliases, bundle chunks, tailwind, react plugin, port
// @what: Build and dev-server settings: plugins, path aliases and the proxy to the backend gateway.
// @flow: Read by vite and pnpm dev/build
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'node:path'

/*
 * In development each API prefix goes straight to the service that owns it, mirroring the
 * gateway's own routing table. That lets the web client run against services started
 * individually - the common case on a machine without Docker - while production traffic still
 * goes through the gateway unchanged. Keep this table in step with the gateway's application.yml
 * and infra/launcher/nginx.conf; the gateway's GatewayRoutesTest compares all three.
 */
const IDENTITY = 'http://localhost:8081'
const ORGANISATION = 'http://localhost:8082'
const ORCHESTRATOR = 'http://localhost:8083'
const MEMORY = 'http://localhost:8084'
const KNOWLEDGE = 'http://localhost:8085'
const INTEGRATIONS = 'http://localhost:8086'
const ANALYTICS = 'http://localhost:8087'

const routes: Record<string, string> = {
  '/api/auth': IDENTITY,
  '/api/users': IDENTITY,
  '/api/roles': IDENTITY,
  '/.well-known': IDENTITY,
  '/api/workspaces': ORGANISATION,
  '/api/credentials': ORGANISATION,
  '/api/orgs': ORGANISATION,
  '/api/invitations': ORGANISATION,
  '/api/agents': ORCHESTRATOR,
  '/api/agent-templates': ORCHESTRATOR,
  '/api/goals': ORCHESTRATOR,
  '/api/runs': ORCHESTRATOR,
  '/api/approvals': ORCHESTRATOR,
  '/api/providers': ORCHESTRATOR,
  '/api/model-policy': ORCHESTRATOR,
  '/api/conversations': ORCHESTRATOR,
  '/api/orchestrator': ORCHESTRATOR,
  '/api/schedules': ORCHESTRATOR,
  '/api/voice': ORCHESTRATOR,
  '/api/memory': MEMORY,
  '/api/knowledge': KNOWLEDGE,
  '/api/sources': KNOWLEDGE,
  '/api/integrations': INTEGRATIONS,
  '/api/oauth': INTEGRATIONS,
  '/api/analytics': ANALYTICS,
  '/api/audit': ANALYTICS,
}

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') },
  },
  server: {
    port: 5173,
    proxy: Object.fromEntries(
      Object.entries(routes).map(([prefix, target]) => [prefix, { target, changeOrigin: true }]),
    ),
  },
})
