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

| Module                  | What it is                                                                                                                                                                                          |
| ----------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `jme-doc-service`       | The doc service instance: it depends on `jeap-doc-service-instance` and adds its configuration                                                                                                      |
| `jme-doc-auth-scs`      | An instance of the [jEAP OAuth mock server](https://github.com/jeap-admin-ch/jeap-oauth-mock-server), issuing the tokens the doc pipelines use                                                      |
| `jme-doc-upstream-stub` | The upstreams the doc service reads: one small service answering both the `/docs-api` of an architecture repository and the graph API of a reaction observer, over a fixed landscape of two systems |
| `jme-doc-test`          | The integration test: it starts the three services, imports the model, publishes the site and reads it - over the API and in a browser                                                              |
| `docker/`               | The database and the object storage the doc service needs, with its bucket and the lifecycle rule expiring the uploaded bundles                                                                     |
| `docs/`                 | [Running the example on a developer machine](docs/local-development.md) - the prerequisites in full, and what to do when the service does not start                                                 |

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
| `jme-doc-archrepo-reader`   | `secret` | `jme_@architecture-model_#read`         | read the architecture model from the upstream           |
| `jme-doc-reactions-reader`  | `secret` | `jme_@reactions_#read`                  | read the observed reactions from the upstream           |

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
4. **Google Chrome**, for the integration test only: the published documentation is driven in a real browser

The third one is the one that is new and the one that bites, so it has a page of its own:
**[Running the example on a developer machine](docs/local-development.md)** - which Node, where the service looks
for it, and what each startup failure means.

Use the provided Maven wrapper to build and run the project.

## Getting started

### Everything at once

```shell
./start.sh
```

The script is every section below in one go: it checks the machine can do all of it, builds the example,
starts the database and the object storage, starts the three services, asks for the model and the reactions
of every environment to be imported, waits until every part of the site is published, and opens the
documentation in Chrome. Every step
either succeeds or stops the script with what failed and where to look - the services log into
`target/local/`. Besides the prerequisites above it needs `curl` and `jq`, and it says so before it does
anything.

| Option           | What it does                                                                              |
| ---------------- | ----------------------------------------------------------------------------------------- |
| `--skip-build`   | start without building first, for a repeat start of an example that is already built      |
| `--no-browser`   | publish the site but open nothing                                                         |
| `--keep-running` | return to the prompt and leave the services running, instead of staying in the foreground |

It stays in the foreground and stops the three services again on Ctrl-C. The containers are left running -
they hold the database and the object storage, and starting them again is free:

```shell
docker compose -f docker/docker-compose.yml down
```

What follows is the same thing by hand, which is what to read to know what the script did - and what to use
for everything the script does not do, an upload above all.

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
./mvnw spring-boot:run -pl jme-doc-auth-scs      -Dspring-boot.run.profiles=local
./mvnw spring-boot:run -pl jme-doc-upstream-stub -Dspring-boot.run.profiles=local
./mvnw spring-boot:run -pl jme-doc-service       -Dspring-boot.run.profiles=local
```

- the doc service listens on http://localhost:8080/jme-doc-service, with its API documentation at
  http://localhost:8080/jme-doc-service/swagger-ui.html
- the OAuth mock server listens on http://localhost:8081/jme-doc-auth-scs
- the upstream stub listens on http://localhost:8082/jme-doc-upstream-stub

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

The bundle is stored, the set becomes the current documentation of that component straight away, and the upload
is answered with `201` and the state it ended in - `PENDING` means the set is current and waiting for the
**publication**, which is the build the upload asked for:

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

### Where the documentation on the site comes from

Three sources, and only one of them is an upload.

**The architecture model** is imported from an architecture repository into the doc service's own database,
and a build reads that copy - so a repository that is being deployed cannot fail a documentation build. Out of
the model the generator writes an arc42 tree per system and per component: the context views, the building
block views, the messages, the REST APIs.

This example has no architecture repository, and running one beside it would mean its database, its importers
and its source data for the sake of two systems. So it has
[`jme-doc-upstream-stub`](jme-doc-upstream-stub), a small service answering the `/docs-api` of one over a
landscape of **two systems of two components each**, written down in
[`landscape.yml`](jme-doc-upstream-stub/src/main/resources/landscape.yml). Adding a system to what this example
documents is editing that file. The stub demands the same semantic role the real architecture repository does,
so what the example exercises is the whole client registration and not only a URL.

**All four environments of the site read that one stub**, so every tree carries the landscape and the
environment switcher leads somewhere. That is the one thing about this example that a real instance does
differently: there, `jeap.doc.archrepo.environments` names the architecture repository of each stage, and
those are four different services holding four different models.

Ask for the model of every environment to be imported, with the operator client:

```shell
curl -i -X POST http://localhost:8080/jme-doc-service/api/architecture/imports \
  -H "Authorization: Bearer $ADMIN"

curl -s http://localhost:8080/jme-doc-service/api/architecture/environments -H "Authorization: Bearer $ADMIN"
```

One environment on its own is asked for at `…/api/architecture/environments/dev/imports`.

**The import is also the trigger**: having stored a landscape that is not the one already there, it asks for
every part of the site, so the documentation appears seconds later - at
http://localhost:8080/jme-doc-service/systems/jme/ and under each of `/dev/`, `/ref/` and `/abn/`.

**The observed reactions** are the second, and they come from a different upstream: the
[reaction observer](https://github.com/jeap-admin-ch/jeap-reaction-observer-service) of the stage, which
watches what actually reacts to what at runtime. The doc service imports them as steps of the same
architecture import - a reaction means nothing until its names are resolved against the model - and draws them
as the runtime views of a system, of a component and of a message. `jme-doc-upstream-stub` answers for the
observer too, out of the same landscape, so what the site shows in chapter 6 cannot contradict what it shows
in chapter 5:

|                                                                                         |                                                         |
| --------------------------------------------------------------------------------------- | ------------------------------------------------------- |
| `…/systems/jme/system-architecture/runtime-view/system-reactions/`                      | which message makes a component of `jme` react          |
| `…/components/jme-doc-service/component-architecture/runtime-view/component-reactions/` | what that one component reacts to                       |
| the page of a message                                                                   | what answers it, as a section of the message's own page |

The reactions of this example are a chain across its two systems: an order is placed, `orders-payment-scs`
reacts and publishes what became of the payment, and `jme-doc-service` reacts to that. They are written down
in [`landscape.yml`](jme-doc-upstream-stub/src/main/resources/landscape.yml) beside the model, and **every
name in them is a name the model holds** - the doc service resolves what an observer offers against the
imported model and leaves out what the model does not have, so a reaction naming something else would document
nothing. It is configured under `jeap.doc.reactions` and is **off by default** in the doc service, because a
platform may run no observer at all.

**The uploaded documentation** is the third source - the custom chapters a repository writes next to its code -
and it is **published beside the generated pages**. The folder inside the ZIP is the chapter and nothing else
decides: `1-intro/why-we-built-this.md` of the component `jme-doc-service` is served at

```text
…/systems/jme/system-architecture/building-block-view/components/jme-doc-service/component-architecture/intro/why-we-built-this/
```

The number of the chapter is in the folder and not in the URL - the numbers belong to arc42, and a link should
survive a methodology that numbers its chapters differently. The page body is written through unchanged;
what the doc service adds is the provenance under it, out of the parameters of the upload.

**A subject the architecture model does not hold is published all the same**, from the upload alone - a team can
document a component before anything of it is deployed, and a library is always in that position. Chapter 1 of
such a subject additionally carries a generated page saying that the model does not hold it and what would make
it appear.

### Ask whether a tree would be accepted, before packing it

Where each file sits in the folder is what decides where it is published, so a misfiled tree is a build that
fails or a page that appears in the wrong chapter. A pipeline can ask before it builds the ZIP, with the same
token and the parameters the *structure* depends on - no version, no commit, no site, because a path tree does
not depend on any of them:

```shell
curl -i -X POST "http://localhost:8080/jme-doc-service/api/uploads/docs/validation\
?type=component-docs&system=jme&component=jme-doc-service&template=arc42&source-format=markdown" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"paths": ["1-intro/why-we-built-this.md", "4-runtime-view/how-an-upload-travels.md"]}'
```

**The verdict is the status line**, so a pipeline branches three ways: `200` publish, `422` print the findings
and stop, anything else fail loudly because the endpoint or the token is wrong. arc42 has no chapter
`4-runtime-view`, so this one is `422`, and the body is the problem document the upload API answers with,
carrying the report as extension members:

```json
{
  "type": "https://jeap.admin.ch/problems/docs/structure-invalid",
  "title": "The documentation structure is invalid",
  "status": 422,
  "detail": "1 problem in 2 paths.",
  "template": "arc42",
  "pathsChecked": 2,
  "pathsIgnored": 0,
  "findings": [
    {
      "code": "UNKNOWN_CHAPTER",
      "path": "4-runtime-view/how-an-upload-travels.md",
      "message": "'4-runtime-view' is not a chapter of arc42. Did you mean '6-runtime-view'?"
    }
  ],
  "findingsOmitted": 0
}
```

Nothing is uploaded, stored or read by this: the tree arrives as a list of paths and no file's bytes are sent -
what is *in* the files is the doc workflow's own half of the validation. The rules it applies are in
[what an upload is validated against](https://github.com/jeap-admin-ch/jeap-doc-service/blob/main/docs/upload-validation.md).

**This endpoint saves a round trip; it is not what keeps a misfiled page off the site.** The upload applies the
same rules to the same paths and refuses a set that breaks one with `422` and the same findings, so a pipeline
that skipped the check gets the same answer one step later. Nothing is stored for such an upload - the list of
paths is read off the archive before the bundle is put anywhere - and the upload is left in a state the same
upload id can be retried under once the tree is fixed.

### Publish the site, and read what the generator did

**A site is published as one build per part.** A part is a set of whole URL subtrees, and the partition cuts a
site into one part per system plus the *shell* - the part carrying the site's own pages and everything no system
claims. Which systems a site has is what an architecture repository tells it, so this example has three parts:
the shell and the two systems of the stubbed landscape. They are built **one after another** here -
`jeap.doc.build.max-concurrent-parts` is 1, because every concurrent build holds a site generator run in the
same container and that is a memory decision rather than a parallelism one.

An upload asks for a build of the part that carries its system, and an instance picks that request up within
`jeap.doc.build.poll-interval` - 30 seconds by default. On top of that a site no architecture import feeds is
asked for on `jeap.doc.build.reconcile-cron`, every four hours through the working day.

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
  "partsRequested": 1,
  "picksUpWithinSeconds": 30
}
```

**Asking is not building.** The answer is `202`: the ask leaves the same request an upload leaves, one per part,
and an instance claims them on its next poll - which is why the answer says how long that takes at most. It is
the forcing kind of ask: every part is published whether its content moved or not, which is what is wanted after
a change outside the content.

One part on its own is asked for below it, which is what to use when one part is wrong and the rest of the site
is expensive to rebuild:

```shell
curl -i -X POST http://localhost:8080/jme-doc-service/api/sites/default/parts/shell/builds \
  -H "Authorization: Bearer $ADMIN"
```

Here too, asking again while a request is pending answers `requested: false` and joins it: however often a part
is asked for, it is built once.

What the generator has been doing is read with the same token:

```shell
curl -s http://localhost:8080/jme-doc-service/api/sites/default        -H "Authorization: Bearer $ADMIN"
curl -s http://localhost:8080/jme-doc-service/api/sites/default/parts  -H "Authorization: Bearer $ADMIN"
curl -s http://localhost:8080/jme-doc-service/api/sites/default/builds -H "Authorization: Bearer $ADMIN"
```

The first answers what the site is configured to do next to what has actually happened - whether it is published
on upload, what is pending, what is running, what the shell has published and what was built last. The second is
the part list: what each part carries, what is published for it, **how old that is** and whether a build of it
is owed - which is where *is this documentation up to date* is answered now that no single build is the site. A
part nobody has rebuilt for a week either has not changed for a week or has stopped being built, and the two are
told apart by whether any other part is younger. The third is the history: every run with the part it produced,
its trigger, its state, how long it took, how much of that was the site generator, what it produced, and the
reason it failed if it did. `…/parts/shell/builds` is the same history for one part.

A token of `jme-doc-pipeline` answers `403` on all of them: uploading the documentation of one system is not a
licence to republish everybody's.

Once a build has succeeded, the site is at http://localhost:8080/jme-doc-service/ and needs no token at all - the
documentation is open, the API is not. Each environment of the site is a tree of its own, and the `main` one is
the one at the root:

| | |
| ----------------------------------------- | ----------------------------------------------------------------------------- |
| `/jme-doc-service/`                       | the `main` environment, `prod` - it is served here and has no path of its own  |
| `/jme-doc-service/dev/`, `/ref/`, `/abn/` | the other environments                                                          |
| `/jme-doc-service/search/`                | the search, also reachable from the box in the navbar of every page            |

The search is scoped to the environment the reader is in, so a query answers with the tree they are reading
rather than with the same page once per environment. Its index is built at the end of the build pass that
published the site - not on a schedule, and it can never fail a publication.

Before the first build, all of them answer `503` with a page saying the documentation is on its way, and a
`Retry-After` - a site that has not been generated yet is not a wrong URL.

### What the bucket keeps, and for how long

Nothing in the object storage is kept indefinitely, and what removes it is not the same thing for both kinds.
Each object the doc service writes carries a `jeap-doc-content` tag saying what it *is*, and
[`docker/docker-compose.yml`](docker/docker-compose.yml) creates the lifecycle rule with the bucket:

| Tag       | What removes it                                                                                                                                                                                                                | After   |
| --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------- |
| `upload`  | The doc service forgets the upload in its database after `jeap.doc.upload.housekeeping.retention` - 14 days - and the lifecycle rule expires its bundle afterwards, so an upload never points at a bundle that is already gone | 21 days |
| `current` | The doc service, when a set is replaced or removed, plus a nightly sweep of the objects no set references. **There is no lifecycle rule over the current documentation, and there may not be one at any value** - see below    | -       |
| `site`    | The doc service itself, after every successful build of that part, down to `jeap.doc.build.retention` publications. **There is no lifecycle rule over the sites, and there may not be one at any value** - see below           | -       |

**An age rule over the current documentation would delete the documentation of the teams who got it right.** The
bundle of an upload is a staging copy that expires; what it became is the only copy there is, and the current
version of a set can be arbitrarily old - a component that publishes once and stays stable for a year is the
normal case. Age cannot tell an orphan from a well-kept page. What *can* be answered is whether anything still
references an object, and that is what the doc service's own nightly sweep asks.

**An age rule over the generated sites would take the site offline.** A part whose content hashes to what is
already published is not generated and uploads nothing, so the objects a part is serving keep the date of the
build that last *changed* that part - and a part nobody edits is as old as the part itself. A rule on age would
therefore expire exactly the documentation nobody has had to touch: its pages answer `503` or `404` while the
database still says the part is published, and the next build finds the digest unchanged and heals nothing. What
removes a publication a part has superseded is the doc service, which is the only thing that knows which objects
those are.

The rule selects on the tag rather than on a prefix, because `jeap.doc.storage.upload-prefix` and `.site-prefix`
are configured per instance while the tag is the same everywhere. Wherever this instance is deployed for real,
the rules belong to the infrastructure code creating the bucket - and the service needs `s3:PutObject`,
**`s3:PutObjectTagging`** and `s3:DeleteObject` on it, because the tag travels with the object and the service
does its own housekeeping. See
[operating the bucket](https://github.com/jeap-admin-ch/jeap-doc-service/blob/main/docs/operating-the-bucket.md).

### Run the integration test

```shell
./mvnw verify
```

Both suites start the database and the object storage with docker compose and start the services of the example
on free ports. They take a few minutes together, most of it the runs of the site generator.

[`DocServiceExampleIT`](jme-doc-test/src/test/java/ch/admin/bit/jeap/jme/doc/DocServiceExampleIT.java) uploads a
documentation set with a token of the mock server. It covers the stored upload (`201`, `PENDING`), the repetition
under the same upload id (`200`, the same `id`) and a different documentation set under a used one (`409`), the
upload for another system and the one with the read role only (`403`), the upload without a token (`401`), an
upload that does not describe a documentation set and one with a mistyped parameter (`400`), an upload that
announces no size (`411`), an upload whose set would not be published (`422`, `STRUCTURE_INVALID`, with the
findings and the upload left `FAILED`), reading the state of an upload back, and the bundle lying in the object
storage under the id of the upload - tagged, so the lifecycle rule of the bucket expires it. It also drives the
step before the upload: a tree that follows arc42 (`200`, with the chapters the template allows), one that does
not (`422`, `UNKNOWN_CHAPTER`), a name the generator writes into that chapter itself (`RESERVED_NAME`), the
validation for another system (`403`) and the one carrying a parameter a structure does not depend on (`400`).

[`DocSiteExampleIT`](jme-doc-test/src/test/java/ch/admin/bit/jeap/jme/doc/DocSiteExampleIT.java) is the other
half: it drives the whole chain a landscape travels - an operator asks for the architecture model, the import
stores it and asks for every part, the parts are generated and published, and the pages are then read. **It
really runs the site generator and a real browser**, so it is the suite that fails when Node or Chrome is
missing - see [Running the example on a developer machine](docs/local-development.md).

Over the API it covers where the model is read from, the import (`202`, not durable, polled until it has
succeeded) and the three parts it produces, what is published for each part and how old that is, the site
being served to anyone without a token with the title this instance configures, a page generated out of the
model, each environment under its own path, the form of a route without its trailing slash (`301`), the site's
own not-found page (`404`), the build history of the site and of one part, the generated files lying in the
object storage under the prefix of their build and tagged, asking for one part and for the whole site, a part
the site does not have (`404`), and the role matrix in both directions: a pipeline may neither publish the site
nor import the model nor read what was built, an operator may not upload documentation (`403`), no token is
`401`, and a site this instance does not configure is `404`.

It also covers **the whole way an upload travels**, which is why the publication is verified in this repository
rather than against a deployed instance: this example publishes the stubbed landscape alone, so a build takes
seconds and can be waited for. An upload asks for a build of the part that carries its system, that build
succeeds, and the page is then read at its route - in every environment tree, with its body unchanged and the
provenance the doc service generated under it. A second set documents a component the stubbed landscape does
**not** hold, and it is published from the upload alone.

It also covers the **second upstream**: that the three reaction indexes were read and their graphs stored, and
that the runtime views they are drawn into are served - chapter 6 of a system and of a component, with the
message and the component the observer saw in them.

In the browser it covers the four things no HTTP assertion can see: that a system of the model is reachable
from the index of a **different** part's build, that a component context view is really **rendered as a
picture** - the generator emits no image at all, only a fenced source block a plugin turns into one - that a
reaction graph is rendered as well, which is a **GraphViz** source block where a context view is a PlantUML
one, and that the **search** finds a generated page, which is an index downloaded and queried in the browser.

## Configuration of the instance

Everything this example configures is in three files:

- [`jme-doc-service/src/main/resources/application.yml`](jme-doc-service/src/main/resources/application.yml) -
  the name of the system the semantic roles are issued for (`jme`), the bucket of the documentation, the size
  limit of an upload, how long an upload is kept, the one documentation site this instance publishes and what it
  is called, and where the site generator finds Node and the site template's dependencies
- [`application-local.yml`](jme-doc-service/src/main/resources/application-local.yml) - database, object storage,
  OAuth issuer and the origin the site is published under, all of the developer machine, plus the two upstreams
  of each environment: the architecture repository under `jeap.doc.archrepo` and the reaction observer under
  `jeap.doc.reactions`, each with a client registration of its own
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
