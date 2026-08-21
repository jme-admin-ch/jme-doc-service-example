# JME Doc Service Example

This example shows how to run an instance of the
[jEAP Doc Service](https://github.com/jeap-admin-ch/jeap-doc-service): the service that receives the
documentation of systems, components and libraries from their build pipelines, stores it and serves it as a
documentation site.

An instance consists of configuration only - the REST API, its security, the object storage and the persistence
come from the service template. This repository shows that configuration, together with everything needed to run
and test it locally: an OAuth mock server issuing the tokens a doc pipeline would hold, a docker compose setup
with the database and the object storage, and an integration test that uploads a documentation set end to end.

## The modules

| Module             | What it is                                                                                                                                     |
| ------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| `jme-doc-service`  | The doc service instance: it depends on `jeap-doc-service-instance` and adds its configuration                                                 |
| `jme-doc-auth-scs` | An instance of the [jEAP OAuth mock server](https://github.com/jeap-admin-ch/jeap-oauth-mock-server), issuing the tokens the doc pipelines use |
| `jme-doc-test`     | The integration test: it starts both services and uploads a documentation set                                                                  |
| `docker/`          | The database and the object storage the doc service needs                                                                                      |

## Roles: a system may only change its own documentation

The doc service authorizes an upload with a semantic role that carries the system it is granted for in its
**tenant** part. The mock server therefore issues tokens with `jmedoc_%jme_@docs_#write` for the doc pipeline of
the system `jme`, and the doc service accepts uploads of that pipeline for the system `jme` only. The clients are
configured in
[`jme-doc-auth-scs/src/main/resources/application-local.yml`](jme-doc-auth-scs/src/main/resources/application-local.yml):

| Client                      | Secret   | Role                               | May                                           |
| --------------------------- | -------- | ---------------------------------- | --------------------------------------------- |
| `jme-doc-pipeline`          | `secret` | `jmedoc_%jme_@docs_#write`         | publish the documentation of the system `jme` |
| `other-system-doc-pipeline` | `secret` | `jmedoc_%othersystem_@docs_#write` | publish the documentation of another system   |
| `jme-doc-reader`            | `secret` | `jmedoc_@docs_#read`               | read the doc service API                      |

## Prerequisites

1. **Java Development Kit (JDK)**: version 25
2. **Docker**: for the database and the object storage

Use the provided Maven wrapper to build and run the project.

## Getting started

### Build

```shell
./mvnw install
```

### Start the infrastructure

The doc service needs a PostgreSQL database and an S3-compatible object storage. The bucket has to exist -
the doc service checks it while it starts and refuses to start without it - so the compose setup creates it:

```shell
docker compose -f docker/docker-compose.yml up -d
```

### Start the services

```shell
./mvnw spring-boot:run -pl jme-doc-auth-scs -Dspring-boot.run.profiles=local
./mvnw spring-boot:run -pl jme-doc-service  -Dspring-boot.run.profiles=local
```

- the doc service listens on http://localhost:8080/jme-doc-service, with its API documentation at
  http://localhost:8080/jme-doc-service/swagger-ui.html
- the OAuth mock server listens on http://localhost:8081/jme-doc-auth-scs

### Upload a documentation set

Fetch a token for the doc pipeline of the system `jme`:

```shell
TOKEN=$(curl -s -X POST http://localhost:8081/jme-doc-auth-scs/oauth2/token \
  -d grant_type=client_credentials -d client_id=jme-doc-pipeline -d client_secret=secret \
  | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
```

Pack some documentation and upload it. The query parameters are named like the keys of the doc workflow
configuration a repository writes, so a pipeline passes its configuration through:

```shell
mkdir -p docs/1-intro && echo "# Why we built this" > docs/1-intro/why-we-built-this.md
(cd docs && zip -r ../docs.zip .)

curl -i -X PUT "http://localhost:8080/jme-doc-service/api/uploads/$(uuidgen)\
?type=component-docs&system=jme&component=jme-doc-service&template=arc42&source-format=markdown&version=1.0.0\
&source-repository=ssh://git@bitbucket.example.ch/bit_jme/jme-doc-service-example.git\
&source-revision=9a1c2f8&source-ref=main&source-timestamp=2026-08-21T09:12:00%2B02:00" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/zip" \
  --data-binary @docs.zip
```

The same upload with a token of `other-system-doc-pipeline` answers `403`: that pipeline may publish the
documentation of its own system only. An upload that does not describe a documentation set - here without the
version of the component - answers `400` with a problem document naming the reason:

```json
{
  "type": "https://jeap.admin.ch/problems/docs/invalid-upload",
  "title": "The upload does not describe a documentation set",
  "status": 400,
  "detail": "The parameter 'version' is required for component documentation.",
  "code": "MISSING_PARAMETER"
}
```

See the [API documentation of the doc service](https://github.com/jeap-admin-ch/jeap-doc-service/blob/main/docs/api.md)
for all parameters.

### Run the integration test

```shell
./mvnw verify
```

`DocServiceExampleIT` starts the database and the object storage with docker compose, starts the OAuth mock
server and the doc service on free ports, and uploads a documentation set with a token of the mock server. It
covers the accepted upload, the upload for another system (`403`), the upload without a token (`401`) and an
upload that does not describe a documentation set (`400`).

## Configuration of the instance

Everything this example configures is in three files:

- [`jme-doc-service/src/main/resources/application.yml`](jme-doc-service/src/main/resources/application.yml) -
  the name of the system the semantic roles are issued for, the bucket of the documentation and the size limit
  of an upload
- [`application-local.yml`](jme-doc-service/src/main/resources/application-local.yml) - database, object storage
  and OAuth issuer of the developer machine
- [`application-ci.yml`](jme-doc-service/src/main/resources/application-ci.yml) - the same, with the containers
  reached under their compose service names

See the [configuration documentation of the doc service](https://github.com/jeap-admin-ch/jeap-doc-service/blob/main/docs/configuration.md)
for the properties an instance can set.

## Changes

This example is versioned using [Semantic Versioning](http://semver.org/) and all changes are documented in
[CHANGELOG.md](./CHANGELOG.md) following the format defined in [Keep a Changelog](http://keepachangelog.com/).

## License

This repository is Open Source Software licensed under the [Apache License 2.0](./LICENSE).
