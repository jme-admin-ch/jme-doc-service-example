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
| `docker/`          | The database and the object storage the doc service needs, with its bucket and the lifecycle rules expiring the uploaded bundles and the generated sites |
| `docs/`            | [Running the example on a developer machine](docs/local-development.md) - the prerequisites in full, and what to do when the service does not start |

## Roles: a system may only upload its own documentation

The doc service authorizes an upload with a semantic role that carries the system it is granted for in its
**tenant** part. The mock server therefore issues tokens with `jme_%jme_@uploads_#write` for the doc pipeline of
the system `jme`, and the doc service accepts uploads of that pipeline for the system `jme` only. Reading the
documentation is a separate resource, `docs`. The clients are configured in
[`jme-doc-auth-scs/src/main/resources/application-local.yml`](jme-doc-auth-scs/src/main/resources/application-local.yml):

| Client                      | Secret   | Role                                    | May                                                     |
| --------------------------- | -------- | --------------------------------------- | ------------------------------------------------------- |
| `jme-doc-pipeline`          | `secret` | `jme_%jme_@uploads_#write`              | upload the documentation of the system `jme`            |
| `other-system-doc-pipeline` | `secret` | `jme_%othersystem_@uploads_#write`      | upload the documentation of another system              |
| `jme-doc-reader`            | `secret` | `jme_@docs_#read`                       | read the doc service API                                |
| `jme-doc-operator`          | `secret` | `jme_@sites_#admin`, `jme_@sites_#read` | ask for a site to be published, and read what was built |

**The `sites` roles carry no tenant part**, and that is the difference that matters: an upload role is granted
per system so that a pipeline can only change its own documentation, while a build regenerates the whole site
with the documentation of every system on it. Administering a site is therefore its own resource, granted to
whoever operates the instance rather than to the pipelines that fill it.

## Prerequisites

1. **Java Development Kit (JDK)**: version 25
2. **Docker**: for the database and the object storage
3. **Node 24 and npm**: the doc service generates the documentation site by running the site generator as a
   child process, so this example needs a Node runtime - and it **refuses to start** without one it can run and
   without the site template's dependencies installed

The third one is the one that is new and the one that bites, so it has a page of its own:
**[Running the example on a developer machine](docs/local-development.md)** - which Node, where the service looks
for it, and what each startup failure means.

Use the provided Maven wrapper to build and run the project.

## Getting started

### Build

```shell
./mvnw install
```

Besides the usual, this runs `npm ci` into `jme-doc-service/target/site-install` - the dependencies of the site
template, installed from the very `package-lock.json` the doc service carries, which is what the service checks
while it starts. It takes a few minutes the first time; a repeat build can skip it with `-DskipSiteInstall=true`
as long as the doc service version does not move. An instance that ships a container installs them into its image
instead, see [the site image](https://github.com/jeap-admin-ch/jeap-doc-service/blob/main/docs/site-image.md).

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

### Publish the site, and read what the generator did

An upload asks for the site to be published, and an instance picks that request up within
`jeap.doc.build.poll-interval` - 30 seconds by default. The site is then served at
http://localhost:8080/jme-doc-service/. On top of that the site is regenerated on the site's own schedule,
hourly through the working day.

Waiting for either is not what a developer wants, so ask for a build directly. That is what the operator client
is for:

```shell
ADMIN=$(curl -s -X POST http://localhost:8081/jme-doc-auth-scs/oauth2/token \
  -d grant_type=client_credentials -d client_id=jme-doc-operator -d client_secret=secret \
  | sed -E 's/.*"access_token":"([^"]+)".*/\1/')

curl -i -X POST http://localhost:8080/jme-doc-service/api/sites/default/builds \
  -H "Authorization: Bearer $ADMIN"
```

```json
{
  "site": "default",
  "requested": true,
  "trigger": "MANUAL",
  "pendingSince": "2026-08-28T09:12:03Z",
  "picksUpWithinSeconds": 30
}
```

**Asking is not building.** The answer is `202`: the ask leaves the same request an upload leaves, and an
instance claims it on its next poll - which is why the answer says how long that takes at most. Asking again
while one is pending answers `requested: false` and joins it, because however often it is asked for, the site is
built once.

What the generator has been doing is read with the same token:

```shell
curl -s http://localhost:8080/jme-doc-service/api/sites/default -H "Authorization: Bearer $ADMIN"
curl -s http://localhost:8080/jme-doc-service/api/sites/default/builds -H "Authorization: Bearer $ADMIN"
```

The first answers what the site is configured to do next to what has actually happened - the schedule, whether
it is published on upload, what is pending, what is running, what is published and what was built last. It is
the answer to *why is this site not updating* without reading a log. The second is the history: every run with
its trigger, its state, how long it took, how much of that was the site generator, what it produced, and the
reason it failed if it did.

A token of `jme-doc-pipeline` answers `403` on all of them: uploading the documentation of one system is not a
licence to republish everybody's.

Once a build has succeeded, the site is at http://localhost:8080/jme-doc-service/ and needs no token at all - the
documentation is open, the API is not. Each environment of the site is a tree of its own, and the `main` one is
the one at the root:

| | |
| --- | --- |
| `/jme-doc-service/` | the `main` environment, `prod` - it is served here and has no path of its own |
| `/jme-doc-service/dev/`, `/ref/`, `/abn/` | the other environments |

Before the first build, all of them answer `503` with a page saying the documentation is on its way, and a
`Retry-After` - a site that has not been generated yet is not a wrong URL.

### What the bucket keeps, and for how long

Nothing in the object storage is kept indefinitely, and the compose setup shows both halves. Each object the doc
service writes carries a `jeap-doc-content` tag saying what it *is*, and
[`docker/docker-compose.yml`](docker/docker-compose.yml) creates a lifecycle rule per value with the bucket:

| Tag | What expires it | After |
| --- | --------------- | ----- |
| `upload` | The doc service forgets the upload in its database after `jeap.doc.upload.housekeeping.retention` - 14 days - and the rule expires its bundle a day later, so an upload never points at a bundle that is already gone | 15 days |
| `site` | The doc service keeps the last `jeap.doc.build.retention` published sites per site and deletes the rest after every successful build; the rule is the fallback for what it never gets to delete. A site regenerated several times a day has nothing worth keeping for longer - and nothing under the site prefix is a source of truth | 2 days |

The rules select on the tag rather than on a prefix, because `jeap.doc.storage.upload-prefix` and `.site-prefix`
are configured per instance while the tag is the same everywhere. Wherever this instance is deployed for real,
both rules belong to the infrastructure code creating the bucket - and the service needs `s3:PutObject` **and
`s3:PutObjectTagging`** on it, because the tag travels with the object.

### Run the integration test

```shell
./mvnw verify
```

Both suites start the database and the object storage with docker compose, and start the OAuth mock server and
the doc service on free ports. They take about two minutes together, most of it the two runs of the site
generator.

[`DocServiceExampleIT`](jme-doc-test/src/test/java/ch/admin/bit/jeap/jme/doc/DocServiceExampleIT.java) uploads a
documentation set with a token of the mock server. It covers the stored upload (`201`, `PENDING`), the repetition
under the same upload id (`200`, the same `id`) and a different documentation set under a used one (`409`), the
upload for another system and the one with the read role only (`403`), the upload without a token (`401`), an
upload that does not describe a documentation set and one with a mistyped parameter (`400`), an upload that
announces no size (`411`), reading the state of an upload back, and the bundle lying in the object storage under
the id of the upload - tagged, so the lifecycle rule of the bucket expires it.

[`DocSiteExampleIT`](jme-doc-test/src/test/java/ch/admin/bit/jeap/jme/doc/DocSiteExampleIT.java) is the other
half: it generates the site and reads what the generator did. **It really runs the site generator**, so it is the
suite that fails when Node is missing or too old - see
[Running the example on a developer machine](docs/local-development.md). It covers an upload asking for a build of
its site and that build succeeding, an operator asking for one (`202`, `MANUAL`, `picksUpWithinSeconds`) and the
site being published, the site then being served to anyone without a token - with the title this instance
configures - each environment under its own path, the form of a route without its trailing slash (`301`), the
site's own not-found page (`404`), the build history with what the run produced and how much of it was Docusaurus,
the generated files lying in the object storage under the prefix of their build and tagged so the second
lifecycle rule expires them, and the role matrix in both directions: a pipeline may not publish the site and an
operator may not upload documentation (`403`), no token is `401`, and a site this instance does not configure is
`404`.

## Configuration of the instance

Everything this example configures is in three files:

- [`jme-doc-service/src/main/resources/application.yml`](jme-doc-service/src/main/resources/application.yml) -
  the name of the system the semantic roles are issued for (`jme`), the bucket of the documentation, the size
  limit of an upload, how long an upload is kept, the one documentation site this instance publishes and what it
  is called, and where the site generator finds Node and the site template's dependencies
- [`application-local.yml`](jme-doc-service/src/main/resources/application-local.yml) - database, object storage,
  OAuth issuer and the origin the site is published under, all of the developer machine
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
