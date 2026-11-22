FROM node:26.13-alpine3.23@sha256:39d8c143e60e99ae98f014bebea1e72fc96d3c9bdf9504bc3a27af5c05bfa39f AS build
WORKDIR /workspace
COPY frontend-saas/package*.json ./frontend-saas/
RUN npm --prefix frontend-saas ci
COPY frontend-saas ./frontend-saas
# Shared login resources live outside the SaaS package in the repository.
COPY frontend-product/src/portalAccess.ts ./frontend-product/src/portalAccess.ts
COPY frontend/branding/espos-wordmark.png ./frontend/branding/espos-wordmark.png
RUN npm --prefix frontend-saas run build

FROM nginxinc/nginx-unprivileged:1.29.3-alpine3.22@sha256:5aea7cc516b419e3526f47dd1531be31a56a046cfe44754d94f9383e13e2ee99
COPY --chown=101:101 frontend-saas/nginx.conf /etc/nginx/conf.d/default.conf
COPY --chown=101:101 --from=build /workspace/frontend-saas/dist /usr/share/nginx/html
USER 101:101
EXPOSE 8080
