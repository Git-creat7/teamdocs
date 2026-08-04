FROM node:22-alpine AS build

WORKDIR /app

ARG NPM_REGISTRY=https://registry.npmjs.org

COPY package.json package-lock.json ./
RUN npm config set registry "$NPM_REGISTRY" \
    && npm config set fetch-retries 5 \
    && npm config set fetch-retry-mintimeout 10000 \
    && npm config set fetch-retry-maxtimeout 60000 \
    && npm config set fetch-timeout 300000 \
    && npm ci --no-audit --no-fund

COPY . .
RUN npm run build

FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
