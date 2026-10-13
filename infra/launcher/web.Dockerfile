# @find: launcher web image, web client build, vite build, prerender public pages, SITE_URL build argument, nginx image, pnpm install, production bundle, pinned node and nginx
# @what: Dockerfile that builds the web client and serves it with nginx for the one-click launcher.
# @flow: Used by infra/launcher/docker-compose.yml; copies infra/launcher/nginx.conf
# The web client for the one-click launcher: a production build served by nginx, which also
# proxies each API prefix to the service that owns it (see nginx.conf).

# The newest Node 22 release when this was pinned. The tag was chosen and confirmed with docker
# manifest inspect, not read from a local image: none is kept after a build.
# Stage build: installs the web client's dependencies from the lock file and produces the
# production bundle in /web/dist, with the public pages prerendered as HTML.
FROM node:22.23.3-alpine AS build
WORKDIR /web
RUN corepack enable
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
# Empty for the launcher; the AWS server caps the Node heap here (infra/aws/docker-compose.prod.yml).
ARG NODE_OPTIONS=""
# The public address the prerendered pages name in their canonical links, link previews and
# structured data, and robots.txt, sitemap.xml and the llms files name too. The AWS server passes
# AIWOS_SITE_URL (infra/aws/docker-compose.prod.yml); the launcher keeps the default.
ARG SITE_URL="https://ai-workforce.bitecodes.com"
# A YYYY-MM-DD date the public pages' content last changed, for the sitemap's lastmod; left out
# when empty, rather than stamping every build with a date nothing changed on.
ARG SITE_LASTMOD=""
ENV VITE_SITE_URL=${SITE_URL} SITE_LASTMOD=${SITE_LASTMOD}
# Type checking is CI's job; the launcher only needs the bundle. build:public is the client build,
# the server build of src/entry-server.tsx and scripts/prerender.mjs, which writes the public pages
# as HTML for crawlers; all of it runs on Node alone, with no browser. dist-ssr stays in this stage.
RUN pnpm run build:public

# Pinned to the exact nginx release the security headers in nginx.conf were checked against.
# Stage serve: the final image. It holds only the built files and the nginx configuration; Node
# and the sources stay in the build stage.
FROM nginx:1.27.5-alpine
COPY --from=build /web/dist /usr/share/nginx/html
COPY --from=launcher nginx.conf /etc/nginx/conf.d/default.conf
