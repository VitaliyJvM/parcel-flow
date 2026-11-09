# ParcelFlow web UI: Angular build served by an unprivileged nginx that also reverse-proxies the
# public REST API, so the browser talks to one origin and the backend needs no CORS.
#
#   docker compose up -d frontend     ->  http://localhost:8081
#
# Build context is frontend/ (see frontend/.dockerignore).

# --- Build stage -------------------------------------------------------------
FROM node:24.22.2-alpine AS build
WORKDIR /app

# Dependencies first, so a source-only change reuses this layer.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY angular.json tsconfig.json tsconfig.app.json ./
COPY public public
COPY src src
RUN npm run build

# --- Runtime stage -----------------------------------------------------------
# nginx-unprivileged runs as uid 101 and listens on 8080; nothing in this image needs root.
FROM nginxinc/nginx-unprivileged:1.29.3-alpine AS runtime

COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY nginx/proxy-to-tracking-service.inc /etc/nginx/conf.d/proxy-to-tracking-service.inc
# Rendered by the image's entrypoint (envsubst) into /etc/nginx/conf.d/security-headers.inc at
# start-up. The filter limits substitution to our own variable, so nginx's $variables survive.
COPY nginx/security-headers.inc.template /etc/nginx/templates/security-headers.inc.template
COPY --from=build /app/dist/parcelflow-web/browser /usr/share/nginx/html

# The identity provider's browser-facing origin, allowed as a CSP form-action target (logout
# redirects there). Compose overrides it; the default matches the local Keycloak.
ENV OIDC_PROVIDER_ORIGIN=http://localhost:8180 \
    NGINX_ENVSUBST_FILTER=^OIDC_PROVIDER_ORIGIN$

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --retries=6 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
