
Create an application-override.yml file in src/main/docker-local/config/

```yaml
datasources:
  metadata:
    username: <your username>
    password: <your password>

x-statement-analysis:
  hosts:
    staging-db-primary:
      username: ${datasources.metadata.username}
      password: ${datasources.metadata.password}

```

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
