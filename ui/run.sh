#!/bin/bash

set -e

port=${1:-4200}

cd "${0%/*}"

# todo check if build is up to date in a faster way
if [ ! -d build/sql-explorer-ui ] || true;  then
  npm install -ci --legacy-peer-deps
  node_modules/.bin/ng build
fi

if [ ! -f node_modules/.bin/angular-http-server ]; then
  npm install -ci angular-http-server
fi


cd build/sql-explorer-ui
../../node_modules/.bin/angular-http-server -p "$port"
