# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
