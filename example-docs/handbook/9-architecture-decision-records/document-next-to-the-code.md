---
title: "ADR: Document Next to the Code"
description: Why the JME team keeps its documentation in Git rather than in a wiki.
---

# ADR: Document Next to the Code

## Status

Accepted

## Context

The team's documentation used to live in a wiki. It was edited apart from the code, reviewed by nobody and out of
date within weeks - and nobody could tell which of its pages still held.

## Decision

The documentation lives in Git, next to the code it describes. It is changed in the same pull request as the code,
reviewed with it, and published by the pipeline to the documentation site.

## Consequences

- A page is as current as the revision it was uploaded from, and every page says which revision that is.
- Writing documentation needs no other tool than the one the code is written with.
- What can be generated from the architecture model is not written by hand at all.
