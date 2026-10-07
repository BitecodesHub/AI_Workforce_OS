# The web client for the one-click launcher: a production build served by nginx, which also
# proxies each API prefix to the service that owns it (see nginx.conf).

# The newest Node 22 release when this was pinned. The tag was chosen and confirmed with docker
# manifest inspect, not read from a local image: none is kept after a build.
FROM node:22.23.3-alpine AS build
WORKDIR /web
RUN corepack enable
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
# Type checking is CI's job; the launcher only needs the bundle.
RUN pnpm exec vite build

# Pinned to the exact nginx release the security headers in nginx.conf were checked against.
FROM nginx:1.27.5-alpine
COPY --from=build /web/dist /usr/share/nginx/html
COPY --from=launcher nginx.conf /etc/nginx/conf.d/default.conf
