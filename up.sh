#!/usr/bin/env bash

set -e

unamestr=$(uname)
if [ "$unamestr" = 'Linux' ]; then
  export $(grep -v '^#' .env | xargs -d '\n')
elif [ "$unamestr" = 'FreeBSD' ] || [ "$unamestr" = 'Darwin' ]; then
  export $(grep -v '^#' .env | xargs -0)
fi


docker compose up -d explorer-mysql

docker compose up -d sql-explorer explorer-ui
docker compose logs -f sql-explorer
