

Build the application:
```bash
./gradlew assemble

docker compose build
```

Startup the application:
```bash
docker compose up -d explorer-mysql

ssh -f -N -L 3336:REDACTED.rds.amazonaws.com:3306 \
  your.user@bastion.staging.example.com
  
docker compose up -d sql-explorer explorer-ui
```
