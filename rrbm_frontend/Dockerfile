# Serve the static frontend through nginx
FROM nginx:alpine
COPY rrbm-frontend /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
