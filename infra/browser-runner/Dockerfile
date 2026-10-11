# @find: browser runner image, dockerfile, headless chromium image, playwright install, debian bookworm, node 22, non-root, browser sidecar container
# @what: Builds the browser runner image: Node 22 on Debian (Chromium does not run on Alpine's musl) with Playwright's headless Chromium shell, run as a non-root user.
# @flow: Built by infra/aws/docker-compose.prod.yml (service "browser", through aiwos.sh) and the local compose files' "browser" profile
#
# Settings (environment variables):
#   BROWSER_RUNNER_SECRET            required; the orchestrator sends it in X-Runner-Secret. Without it
#                                    every request except GET /health is refused.
#   BROWSER_RUNNER_PORT              default 8090
#   BROWSER_RUNNER_MAX_SESSIONS      browser contexts (one per run) open at once, default 2
#   BROWSER_RUNNER_SESSION_IDLE_MS   an unused run's page is closed after this, default 120000 (2 min)
#   BROWSER_RUNNER_BROWSER_IDLE_MS   Chromium itself is closed after this with no pages, default 300000
#   BROWSER_RUNNER_PAGE_TIMEOUT_MS   page-load timeout, default 20000
# The orchestrator side: AIWOS_BROWSER_RUNNER_URL (for example http://browser:8090) and
# AIWOS_BROWSER_RUNNER_SECRET (the same secret).

FROM node:22.20.0-bookworm-slim

ENV NODE_ENV=production \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    BROWSER_RUNNER_PORT=8090

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund \
    && npx --no-install playwright-core install --with-deps --only-shell chromium \
    && apt-get clean && rm -rf /var/lib/apt/lists/* /root/.npm /tmp/*

COPY guard.js proxy.js server.js ./

RUN groupadd --system runner && useradd --system --gid runner --home-dir /app --no-create-home runner \
    && chown -R runner:runner /app
USER runner

EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD node -e "require('http').get('http://127.0.0.1:'+(process.env.BROWSER_RUNNER_PORT||8090)+'/health',r=>process.exit(r.statusCode===200?0:1)).on('error',()=>process.exit(1))"

CMD ["node", "server.js"]
