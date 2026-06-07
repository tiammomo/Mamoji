# syntax=docker/dockerfile:1.7

FROM node:24-bookworm-slim AS deps
WORKDIR /app

RUN npm config set registry https://registry.npmmirror.com
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci

FROM node:24-bookworm-slim AS build
WORKDIR /app

ARG NEXT_PUBLIC_API_BASE_URL=http://localhost:38080/api/v1
ARG NEXT_PUBLIC_SHOW_DEMO_CREDENTIALS=true
ENV NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL}
ENV NEXT_PUBLIC_SHOW_DEMO_CREDENTIALS=${NEXT_PUBLIC_SHOW_DEMO_CREDENTIALS}
ENV NEXT_TELEMETRY_DISABLED=1

COPY --from=deps /app/node_modules ./node_modules
COPY frontend ./
RUN npm run build

FROM node:24-bookworm-slim AS runner
WORKDIR /app

ARG NEXT_PUBLIC_SHOW_DEMO_CREDENTIALS=true
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV NEXT_PUBLIC_SHOW_DEMO_CREDENTIALS=${NEXT_PUBLIC_SHOW_DEMO_CREDENTIALS}
ENV HOSTNAME=0.0.0.0
ENV PORT=33000

COPY --from=build /app/public ./public
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static

USER node

EXPOSE 33000
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=12 \
  CMD node -e "fetch('http://localhost:33000/login').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "server.js"]
