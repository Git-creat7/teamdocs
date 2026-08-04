# syntax=docker/dockerfile:1

FROM node:22-alpine AS build

WORKDIR /app

ARG NPM_REGISTRY=https://registry.npmjs.org

COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm,sharing=locked \
    set -eu; \
    npm config set registry "$NPM_REGISTRY"; \
    npm config set fetch-retries 5; \
    npm config set fetch-retry-mintimeout 10000; \
    npm config set fetch-retry-maxtimeout 60000; \
    npm config set fetch-timeout 300000; \
    attempt=1; \
    until npm ci --prefer-offline --no-audit --no-fund; do \
      if [ "$attempt" -ge 3 ]; then exit 1; fi; \
      attempt=$((attempt + 1)); \
      echo "npm install failed; retrying ($attempt/3)" >&2; \
      sleep 5; \
    done

COPY . .
RUN npm run build

FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
