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
| `jme-doc-test`     | The integration test: it starts both services, uploads a documentation set and looks into the bucket it landed in                              |
| `docker/`          | The database and the object storage the doc service needs, with its bucket and the lifecycle rule expiring the uploaded bundles                |

## Roles: a system may only upload its own documentation

The doc service authorizes an upload with a semantic role that carries the system it is granted for in its
**tenant** part. The mock server therefore issues tokens with `jme_%jme_@uploads_#write` for the doc pipeline of
the system `jme`, and the doc service accepts uploads of that pipeline for the system `jme` only. Reading the
documentation is a separate resource, `docs`. The clients are configured in
[`jme-doc-auth-scs/src/main/resources/application-local.yml`](jme-doc-auth-scs/src/main/resources/application-local.yml):

| Client                      | Secret   | Role                               | May                                          |
| --------------------------- | -------- | ---------------------------------- | -------------------------------------------- |
| `jme-doc-pipeline`          | `secret` | `jme_%jme_@uploads_#write`         | upload the documentation of the system `jme` |
| `other-system-doc-pipeline` | `secret` | `jme_%othersystem_@uploads_#write` | upload the documentation of another system   |
| `jme-doc-reader`            | `secret` | `jme_@docs_#read`                  | read the doc service API                     |

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
configuration a repository writes, so a pipeline passes its configuration through, and the upload id in the path
is a UUID the pipeline chooses:

```shell
mkdir -p docs/1-intro && echo "# Why we built this" > docs/1-intro/why-we-built-this.md
(cd docs && zip -r ../docs.zip .)

UPLOAD_ID=$(uuidgen)

curl -i -X PUT "http://localhost:8080/jme-doc-service/api/uploads/docs/$UPLOAD_ID\
?type=component-docs&system=jme&component=jme-doc-service&template=arc42&source-format=markdown&version=1.0.0\
&source-repository=ssh://git@bitbucket.example.ch/bit_jme/jme-doc-service-example.git\
&source-revision=9a1c2f8&source-ref=main&source-timestamp=2026-08-21T09:12:00%2B02:00" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/zip" \
  --data-binary @docs.zip
```

The bundle is stored and the upload is answered with `201` and the state it ended in - `PENDING` means it is
waiting for the documentation generator:

```json
{
  "uploadId": "8f1c9a2e-6a1a-4a5f-9a5e-2b0f9a3c1d77",
  "id": 1,
  "state": "PENDING",
  "sizeInBytes": 184,
  "receivedAt": "2026-08-25T07:12:00.123Z"
}
```

**The size of the bundle has to be announced in `Content-Length`** - a request without it answers `411`. Every
client that uploads a file sends it, `curl --data-binary @docs.zip` and `curl -T docs.zip` included.

**The upload id is the idempotency key.** Repeating the very same call under `$UPLOAD_ID` publishes nothing a
second time: it answers `200` with the same `id`, so a pipeline may retry without asking whether its previous
attempt got through. The same id with different parameters is a different documentation set and answers `409`
`UPLOAD_ID_CONFLICT` - a re-run of a workflow uses a new upload id.

What became of an upload can be read back with the same token, which is what a pipeline whose answer never
arrived does:

```shell
curl -s "http://localhost:8080/jme-doc-service/api/uploads/docs/$UPLOAD_ID?system=jme" \
  -H "Authorization: Bearer $TOKEN"
```

The same upload with a token of `other-system-doc-pipeline` answers `403`: that pipeline may upload the
documentation of its own system only, and a token of `jme-doc-reader` answers `403` as well - reading the
documentation does not let a client change it. An upload that does not describe a documentation set - here
without the version of the component - answers `400` with a problem document naming the reason:

```json
{
  "type": "https://jeap.admin.ch/problems/docs/invalid-upload",
  "title": "The upload does not describe a documentation set",
  "status": 400,
  "detail": "The parameter 'version' is required for component documentation.",
  "code": "MISSING_PARAMETER"
}
```

A parameter the doc service does not know is rejected the same way, with `UNKNOWN_PARAMETER`: a typo in a doc
workflow configuration has to fail loudly instead of silently publishing something else than the repository
intended.

See the [API documentation of the doc service](https://github.com/jeap-admin-ch/jeap-doc-service/blob/main/docs/api.md)
for all parameters.

### How long an upload is kept

An upload is removed in two places, and the compose setup shows both. The doc service forgets the upload in its
database after `jeap.doc.upload.housekeeping.retention` - 14 days - and the bundle in the object storage is
expired by a **lifecycle rule of the bucket**, set a little longer so that an upload never points at a bundle
that is already gone. The rule is created with the bucket in
[`docker/docker-compose.yml`](docker/docker-compose.yml) and selects on the tag `jeap-doc-content=upload` that
every uploaded bundle carries. Wherever this instance is deployed for real, that rule belongs to the
infrastructure code creating the bucket - and the service needs `s3:PutObject` **and `s3:PutObjectTagging`** on
it, because the tag travels with the object.

### Run the integration test

```shell
./mvnw verify
```

`DocServiceExampleIT` starts the database and the object storage with docker compose, starts the OAuth mock
server and the doc service on free ports, and uploads a documentation set with a token of the mock server. It
covers the stored upload (`201`, `PENDING`), the repetition under the same upload id (`200`, the same `id`) and a
different documentation set under a used one (`409`), the upload for another system and the one with the read
role only (`403`), the upload without a token (`401`), an upload that does not describe a documentation set and
one with a mistyped parameter (`400`), an upload that announces no size (`411`), reading the state of an upload
back, and the bundle lying in the object storage under the id of the upload - tagged, so the lifecycle rule of
the bucket expires it.

## Configuration of the instance

Everything this example configures is in three files:

- [`jme-doc-service/src/main/resources/application.yml`](jme-doc-service/src/main/resources/application.yml) -
  the name of the system the semantic roles are issued for (`jme`), the bucket of the documentation, the size
  limit of an upload and how long an upload is kept
- [`application-local.yml`](jme-doc-service/src/main/resources/application-local.yml) - database, object storage
  and OAuth issuer of the developer machine
- [`application-ci.yml`](jme-doc-service/src/main/resources/application-ci.yml) - the same, with the containers
  reached under their compose service names

See the [configuration documentation of the doc service](https://github.com/jeap-admin-ch/jeap-doc-service/blob/main/docs/configuration.md)
for the properties an instance can set.

## Version of the doc service

The version of the doc service the example runs is the property `jeap-doc-service.version` in the root
[`pom.xml`](./pom.xml).

## Changes

This example is versioned using [Semantic Versioning](http://semver.org/) and all changes are documented in
[CHANGELOG.md](./CHANGELOG.md) following the format defined in [Keep a Changelog](http://keepachangelog.com/).

## License

This repository is Open Source Software licensed under the [Apache License 2.0](./LICENSE).
