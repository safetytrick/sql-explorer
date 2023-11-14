FROM node:16 as builder

WORKDIR /app

COPY package*.json ./

RUN npm install -ci --legacy-peer-deps

COPY . .

RUN node_modules/.bin/ng build --prod


FROM nginx

COPY nginx.conf /etc/nginx/nginx.conf

RUN mkdir /app

COPY --from=builder /app/build/sql-explorer-ui /app/sql-explorer-ui
