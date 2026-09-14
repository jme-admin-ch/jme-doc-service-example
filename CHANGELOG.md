# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [6.1.0] - 2026-09-14

### Added
- Uploaded HTML microsites: the upload rules, serving from the bucket with the sandbox, and the files on the object storage.
- The microsite in the browser: framed in the site's navigation, opaque origin, storage through the shim, `?path=` and the height message.
- Raw HTML in an uploaded page shown as text, and an uploaded SVG served sandboxed.
- System and library documentation uploaded and published beside the component documentation.
- Search over uploaded Markdown and microsite content, narrowed by the source chips.
- Removing a microsite: its files gone at once, its page with the next build.

### Changed
- Runs against a 3.1.0 snapshot of the doc service, which publishes uploaded HTML as microsites.

## [6.0.0] - 2026-09-13

### Dependencies
- **ch.admin.bit.jeap:jeap-oauth-mock-server**: 10.10.0 → 11.0.0 (major)
- **ch.admin.bit.jeap.jme:jme-spring-boot-integration-test**: 6.5.0 → 6.6.0 (minor)

## [5.1.0] - 2026-09-12

### Dependencies
- **ch.admin.bit.jeap.jme:jme-spring-boot-integration-test**: 6.4.1 → 6.5.0 (minor)

## [5.0.0] - 2026-09-11

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.13.0 → 41.1.0 (major)
- **ch.admin.bit.jeap.jme:jme-spring-boot-integration-test**: 6.4.0 → 6.4.1 (patch)

## [4.3.0] - 2026-09-11

### Added

- **The documentation a team uploads is published, and the integration test reads it off the site.** The whole
  way is driven end to end: the pipeline uploads a set, the part carrying its system is built, and the page is
  then read at its route in every environment tree - with its body unchanged and the provenance the doc service
  generated under it. It is verified here rather than against a deployed instance because this example publishes
  the stubbed landscape alone, so a build takes seconds and can be waited for.
- **A component the architecture model does not hold is published from its upload alone**, which is what lets a
  team document something before anything of it is deployed. The second set the test uploads documents
  `jme-doc-upstream-stub` - a module of this example that is deployed nowhere, so no importer can see it.
- **The uploaded set carries a picture beside its page**, and the test follows it from the published page to the
  file the site serves and compares the bytes. It is over the 10 KB below which Docusaurus inlines an image into
  the page, so it is published as a file of its own - the way a screenshot goes.
- **An upload whose set would not be published is refused** with `422` and `STRUCTURE_INVALID`, carrying the
  same findings the advisory validation endpoint answers with. The structure endpoint now saves a round trip
  rather than being the only thing that keeps a misfiled page off the site.

### Changed

- The lifecycle rule of the bucket expires an upload's bundle after **21 days** instead of 15, following the
  retention the doc service documents. And it still selects on `jeap-doc-content=upload` alone: the current
  documentation carries `jeap-doc-content=current` and **may not be expired by age at any value** - it is the
  only copy of what a team wrote, and a set that is a year old is a component nobody has had to touch.

### Dependencies
- **ch.admin.bit.jeap:jeap-doc-service**: 2.1.0 → 2.2.0 (minor)

## [4.2.0] - 2026-09-11

### Added

- **`./start.sh` runs the whole example**: it checks the machine, builds, starts the containers and the three
  services, imports the architecture repository, waits until every part of the site is published and opens the
  documentation in a browser. Every step either succeeds or stops with what failed and where to look.
- **The runtime views have something in them.** The upstream stub answers for a reaction observer as well, out
  of the same landscape: two reactions forming a chain across the two systems, projected onto the three
  replication indexes and the graphs behind them, behind the `jme_@reactions_#read` role the real observer
  requires. Configured under `jeap.doc.reactions` with a client registration of its own.

### Changed

- **All four environments of the site read a model**, each of them from the one upstream stub, so every tree
  carries the landscape instead of only `dev` - and the main environment at the site root is no longer empty.
- The stubbed landscape has a second event, `OrdersOrderPlacedEvent`, which is what the first of the two
  reactions answers with the second.

### Dependencies
- **ch.admin.bit.jeap:jeap-doc-service**: 2.0.0 → 2.1.0 (minor)

## [4.1.0] - 2026-09-10

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.11.0 → 40.13.0 (minor)
- **ch.admin.bit.jeap.jme:jme-spring-boot-integration-test**: 6.3.0 → 6.4.0 (minor)

## [4.0.1] - 2026-09-10

### Added

- **The example generates documentation.** A new module, `jme-doc-upstream-stub`, answers the `/docs-api` of an
  architecture repository over a landscape of two systems of two components each, so the doc service imports a
  model and publishes the arc42 trees generated from it - the context views, the messages, the REST APIs.
- The integration test drives the whole chain: an operator asks for the import, the import asks for every part
  of the site, the parts are published, and the pages are then read **in a real browser** - the one place a
  rendered diagram and the search can be seen at all.

### Changed

- One part of the site is generated at a time (`jeap.doc.build.max-concurrent-parts`), and
  `jeap.doc.build.node-modules-directory` is absolute: the search indexer symlinks it, so a relative path
  leaves the index without pagefind.

## [4.0.0] - 2026-09-10

### Changed

- The site is published as **one build per part** - the shell plus one per system - so it is asked for and read
  per part; this example configures no architecture repository, so its site is the shell alone.
- The bucket has **no lifecycle rule over the generated sites** any more: what a part serves keeps the date of
  the build that last changed it, so an age rule would expire live documentation.

### Added

- The integration test drives `POST /api/uploads/docs/validation`, which says whether a path tree would be
  accepted before the ZIP exists.
- The site has a search again, in the navbar and at `/search`, scoped to the environment the reader is in.

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.10.1 → 40.11.0 (minor)
- **ch.admin.bit.jeap:jeap-doc-service-instance**: 1.3.0 → 2.0.0 (major)
- **ch.admin.bit.jeap:jeap-doc-site**: 1.3.0 → 2.0.0 (major)
- **ch.admin.bit.jeap:jeap-oauth-mock-server**: 10.9.0 → 10.10.0 (minor)

## [3.3.1] - 2026-09-08

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.10.0 → 40.10.1 (patch)

## [3.3.0] - 2026-09-07

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.9.2 → 40.10.0 (minor)
- **ch.admin.bit.jeap:jeap-doc-service-instance**: 1.2.0 → 1.3.0 (minor)
- **ch.admin.bit.jeap:jeap-doc-site**: 1.2.0 → 1.3.0 (minor)
- **ch.admin.bit.jeap:jeap-oauth-mock-server**: 10.8.0 → 10.9.0 (minor)
- **ch.admin.bit.jeap.jme:jme-spring-boot-integration-test**: 6.2.2 → 6.3.0 (minor)

## [3.2.0] - 2026-09-04

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.9.0 → 40.9.2 (patch)
- **ch.admin.bit.jeap:jeap-doc-service-instance**: 1.1.1 → 1.2.0 (minor)
- **ch.admin.bit.jeap:jeap-doc-site**: 1.1.1 → 1.2.0 (minor)
- **ch.admin.bit.jeap.jme:jme-spring-boot-integration-test**: 6.2.1 → 6.2.2 (patch)

## [3.1.0] - 2026-09-03

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.7.0 → 40.9.0 (minor)
- **ch.admin.bit.jeap:jeap-doc-service-instance**: 1.1.0 → 1.1.1 (patch)
- **ch.admin.bit.jeap:jeap-doc-site**: 1.1.0 → 1.1.1 (patch)
- **ch.admin.bit.jeap:jeap-oauth-mock-server**: 10.7.0 → 10.8.0 (minor)
- **ch.admin.bit.jeap.jme:jme-spring-boot-integration-test**: 6.2.0 → 6.2.1 (patch)

## [3.0.0] - 2026-09-02

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.6.0 → 40.7.0 (minor)
- **ch.admin.bit.jeap:jeap-doc-service-instance**: 0.5.0 → 1.1.0 (major)
- **ch.admin.bit.jeap:jeap-doc-site**: 0.5.0 → 1.1.0 (major)
- **ch.admin.bit.jeap:jeap-oauth-mock-server**: 10.5.0 → 10.7.0 (minor)

## [2.10.0] - 2026-09-01

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.5.1 → 40.6.0 (minor)
- **ch.admin.bit.jeap:jeap-oauth-mock-server**: 10.4.0 → 10.5.0 (minor)

## [2.9.1] - 2026-08-31

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.5.0 → 40.5.1 (patch)

## [2.9.0] - 2026-08-30

### Dependencies
- **ch.admin.bit.jeap.jme:jme-spring-boot-integration-test**: 6.1.0 → 6.2.0 (minor)

## [2.8.0] - 2026-08-28

### Dependencies
- **ch.admin.bit.jeap:jeap-oauth-mock-server**: 10.3.0 → 10.4.0 (minor)

## [2.7.0] - 2026-08-28

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.4.0 → 40.5.0 (minor)
- **ch.admin.bit.jeap:jeap-oauth-mock-server**: 10.2.0 → 10.3.0 (minor)
- **ch.admin.bit.jeap.jme:jme-spring-boot-integration-test**: 6.0.1 → 6.1.0 (minor)

## [2.6.0] - 2026-08-28

### Added

- The example generates and serves the documentation site. This needs **Node 24** on the machine: the Maven build
  installs the site template's dependencies with `npm ci`, and the doc service does not start without them - see
  [Running the example on a developer machine](docs/local-development.md).
- A `jme-doc-operator` client holding `jme_@sites_#admin` and `jme_@sites_#read`, which may ask for a site to be
  published and read what the generator has been doing.
- The site the instance publishes, `jeap.doc.sites.default.title`, and the origin it is published under,
  `jeap.doc.publication.url`.
- A lifecycle rule expiring the generated sites, on the tag `jeap-doc-content=site`, next to the one for the
  uploaded bundles.

### Dependencies
- **ch.admin.bit.jeap:jeap-doc-service-instance**: 0.4.0 → 0.5.0 (minor)

## [2.5.0] - 2026-08-27

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.2.0 → 40.4.0 (minor)

## [2.4.0] - 2026-08-26

### Dependencies
- **ch.admin.bit.jeap:jeap-doc-service-instance**: 0.3.0 → 0.4.0 (minor)
- **ch.admin.bit.jeap:jeap-oauth-mock-server**: 10.1.0 → 10.2.0 (minor)

## [2.3.0] - 2026-08-25

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.1.0 → 40.2.0 (minor)

## [2.2.0] - 2026-08-25

### Changed

- The example follows the upload API of the doc service 0.3.0: the endpoint is
  **`PUT /api/uploads/docs/{uploadId}`**, a stored bundle is answered with `201`, `Content-Length` is mandatory,
  and the upload id is the idempotency key - repeating a request under it answers `200` with the same upload.
- `DocServiceExampleIT` covers the repetition of an upload, a different documentation set under a used upload
  id, an upload with a mistyped parameter, an upload announcing no size, reading the state of an upload back
  with `GET /api/uploads/docs/{uploadId}`, and the bundle lying in the object storage under the id of the
  upload, tagged for the lifecycle rule.
- The compose setup creates the lifecycle rule expiring the uploaded bundles together with the bucket, and the
  instance spells out the housekeeping of the uploads that the rule belongs to.

### Dependencies
- **ch.admin.bit.jeap:jeap-doc-service-instance**: 0.2.0 → 0.3.0 (minor)

## [2.1.0] - 2026-08-24

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.0.0 → 40.1.0 (minor)
- **ch.admin.bit.jeap:jeap-doc-service-instance**: 0.1.0 → 0.2.0 (minor)
- **ch.admin.bit.jeap:jeap-oauth-mock-server**: 10.0.0 → 10.1.0 (minor)
- **ch.admin.bit.jeap.jme:jme-spring-boot-integration-test**: 6.0.0 → 6.0.1 (patch)

## [2.0.0] - 2026-08-21

### Dependencies
- **ch.admin.bit.jeap:jeap-oauth-mock-server**: 9.1.0 → 10.0.0 (major)
- **ch.admin.bit.jeap.jme:jme-spring-boot-integration-test**: 5.15.0 → 6.0.0 (major)

## [1.0.0] - 2026-08-21

### Added

- Initial version of the JME doc service example: an instance of the jEAP Doc Service, an instance of the jEAP
  OAuth mock server issuing the tokens of the doc pipelines, a docker compose setup with the database and the
  object storage, and an integration test uploading a documentation set end to end.
