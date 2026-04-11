FROM node:22 as builder

WORKDIR /app

COPY package*.json ./

RUN --mount=type=cache,target=/root/.npm \
    npm ci --legacy-peer-deps

COPY . .

RUN node_modules/.bin/ng build --configuration production


FROM nginx

COPY nginx.conf /etc/nginx/nginx.conf

RUN mkdir /app

COPY --from=builder /app/build/sql-explorer-ui/browser /app/sql-explorer-ui
