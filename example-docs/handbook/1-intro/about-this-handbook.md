---
title: About This Handbook
description: What the JME handbook is for, and how it differs from the documentation of the JME system.
---

# About This Handbook

The handbook describes **how the JME team works** - how a change becomes a release, how the team reviews it and
which decisions it has taken about its own way of working. How the system itself is built is described on the
documentation site of JME, which is generated from the architecture model.

This handbook is a site of its own. No architecture model feeds it, so everything on it was written by hand and
uploaded from a Git repository - the pages say which one, and at which revision.

## Where the pages live

Each page is a Markdown file in the `example-docs/handbook/` folder of `jme-doc-service-example`, filed under the arc42
chapter it belongs to. `start.sh` uploads that folder to the handbook every time it starts the example, the way a
doc pipeline would on every push.

To change a page, edit the file and start the example again.
