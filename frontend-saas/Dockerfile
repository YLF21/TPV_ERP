FROM node:22.16.0-alpine3.22@sha256:41e4389f3d988d2ed55392df4db1420ad048ae53324a8e2b7c6d19508288107e AS build
WORKDIR /workspace
COPY frontend-saas/package*.json ./frontend-saas/
RUN npm --prefix frontend-saas ci
COPY frontend-saas ./frontend-saas
RUN npm --prefix frontend-saas run build

FROM nginxinc/nginx-unprivileged:1.29.1-alpine3.22@sha256:27985295bdb22a1ef8f712863210bd5877c0f3006494a593e86b3fe0fa55467e
COPY --chown=101:101 frontend-saas/nginx.conf /etc/nginx/conf.d/default.conf
COPY --chown=101:101 --from=build /workspace/frontend-saas/dist /usr/share/nginx/html
USER 101:101
EXPOSE 8080
