sql-explorer
======================================

SQL Explorer

How this oughta go
--------------------------------------

Quickstart with connector to mysql with a running performance_schema


Add schema connector or schema migration script
Add performance schema connector or source


cli for importing sql and metadata


SQL Source Metadata
-------------------

Each datasource has a type, this describes a general contract for data being extracted, examples of types:

- performance schema export
- performance schema connection
- slow sql log
- general sql log
- jdbc interceptor

Each datasource has a source environment, this environment can be associated with a current and historic schema and
provide context for the data, examples of environments:

- production
- staging
- reporting
- build (CI/local)
- development



Configuration:
--------------

The docker image expects spring style configuration mounted in this directory: `/etc/sql-explorer/config/`

An environment specific profile is read from this environment variable: ENVIRONMENT_PROFILE, this should be set to a
value like `ENVIRONMENT_PROFILE=deployed` and environment specific configuration should be placed in a yaml file
here: `/etc/sql-explorer//config/application-deployed.yml`

The secrets profile is enabled by default and should be used for managing secrets (passwords, keys, etc.), a sample
configuration file with required entries is located in the local dev config file:
[src/main/docker-local/config/application-secrets.yml](../src/main/docker-local/config/application-secrets.yml)

TODO
------------
