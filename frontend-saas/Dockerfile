FROM node:22-alpine AS build
WORKDIR /workspace
COPY frontend-saas/package*.json ./frontend-saas/
RUN npm --prefix frontend-saas ci
COPY frontend-saas ./frontend-saas
RUN npm --prefix frontend-saas run build

FROM nginx:1.29-alpine
COPY frontend-saas/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/frontend-saas/dist /usr/share/nginx/html
EXPOSE 80
