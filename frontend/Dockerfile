FROM node:24-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM alpine:3.24
RUN apk upgrade --no-cache && apk add --no-cache 'nginx>=1.30.7-r0'
RUN printf '%s\n' \
    'worker_processes auto;' \
    'pid /tmp/nginx.pid;' \
    'error_log /dev/stderr warn;' \
    'events { worker_connections 1024; }' \
    'http {' \
    '  include /etc/nginx/mime.types;' \
    '  access_log /dev/stdout;' \
    '  client_body_temp_path /tmp/client;' \
    '  proxy_temp_path /tmp/proxy;' \
    '  fastcgi_temp_path /tmp/fastcgi;' \
    '  uwsgi_temp_path /tmp/uwsgi;' \
    '  scgi_temp_path /tmp/scgi;' \
    '  include /etc/nginx/cloudforge.conf;' \
    '}' > /etc/nginx/nginx.conf
COPY nginx.conf /etc/nginx/cloudforge.conf
COPY --from=build /app/dist/ /usr/share/nginx/html/
USER nginx
EXPOSE 8080
ENTRYPOINT ["nginx", "-g", "daemon off;"]
