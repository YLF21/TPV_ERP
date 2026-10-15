FROM node:26.11-alpine3.23@sha256:f1394b3ffc021bc3f4aaf363e70a8d9667f7a9a63538bc824d7cc583129fffad AS build
WORKDIR /workspace
COPY frontend-saas/package*.json ./frontend-saas/
RUN npm --prefix frontend-saas ci
COPY frontend-saas ./frontend-saas
RUN npm --prefix frontend-saas run build

FROM nginxinc/nginx-unprivileged:1.29.3-alpine3.22@sha256:5aea7cc516b419e3526f47dd1531be31a56a046cfe44754d94f9383e13e2ee99
COPY --chown=101:101 frontend-saas/nginx.conf /etc/nginx/conf.d/default.conf
COPY --chown=101:101 --from=build /workspace/frontend-saas/dist /usr/share/nginx/html
USER 101:101
EXPOSE 8080
