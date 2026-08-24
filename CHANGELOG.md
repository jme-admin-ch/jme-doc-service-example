# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
