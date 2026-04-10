FROM node:22 as builder

WORKDIR /app

COPY package*.json ./

RUN npm install -ci --legacy-peer-deps

COPY . .

RUN NODE_OPTIONS=--openssl-legacy-provider node_modules/.bin/ng build --configuration production


FROM nginx

COPY nginx.conf /etc/nginx/nginx.conf

RUN mkdir /app

COPY --from=builder /app/build/sql-explorer-ui /app/sql-explorer-ui
