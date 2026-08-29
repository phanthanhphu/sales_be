# syntax=docker/dockerfile:1.7

# ===== BUILD STAGE =====
FROM node:20-alpine AS build

WORKDIR /app

COPY package.json package-lock.json ./

RUN --mount=type=cache,id=sales-fe-npm,target=/root/.npm \
    npm ci --prefer-offline --no-audit --progress=false

COPY . .

ARG VITE_APP_VERSION=v2.1.0
ARG VITE_APP_BASE_NAME=/
ARG VITE_APP_PROTOCOL=http
ARG VITE_APP_HOST=10.232.100.69
ARG VITE_API_PORT=8082

ENV VITE_APP_VERSION=$VITE_APP_VERSION
ENV VITE_APP_BASE_NAME=$VITE_APP_BASE_NAME
ENV VITE_APP_PROTOCOL=$VITE_APP_PROTOCOL
ENV VITE_APP_HOST=$VITE_APP_HOST
ENV VITE_API_PORT=$VITE_API_PORT

RUN npm run build

# ===== RUN STAGE =====
FROM nginx:alpine

ARG NGINX_CONF=nginx.http.conf
ARG APP_PORT=80

COPY ${NGINX_CONF} /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE ${APP_PORT}

CMD ["nginx", "-g", "daemon off;"]