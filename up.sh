#!/usr/bin/env bash

set -e

unamestr=$(uname)
if [ "$unamestr" = 'Linux' ]; then
  export $(grep -v '^#' .env | xargs -d '\n')
elif [ "$unamestr" = 'FreeBSD' ] || [ "$unamestr" = 'Darwin' ]; then
  export $(grep -v '^#' .env | xargs -0)
fi

PIDFILE=.ssh.pid

if [ -f $PIDFILE ]
then
  PID=$(cat $PIDFILE)
  ps -p $PID > /dev/null 2>&1
  if [ $? -eq 0 ]
  then
    echo "Killing pre-existing ssh session $PID"
    kill "$PID"
  fi
fi


docker compose up -d explorer-mysql

#
ssh -N -L 3336:"$DB_HOST":3306 "$SSH_URL" &

echo $! > $PIDFILE

docker compose up -d sql-explorer explorer-ui
docker compose logs -f sql-explorer
