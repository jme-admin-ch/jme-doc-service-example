# Running the example on a developer machine

The doc service does two things, and the second one is why this page exists: it **receives** documentation over
its upload API, and it **publishes** it by running a site generator - Docusaurus - as a child process. Receiving
needs a JVM, a database and an object storage. Publishing needs Node.

So a machine that runs this example needs one thing more than a jEAP service usually does, and the service is
deliberately unforgiving about it: **it refuses to start when it could not generate a site**, rather than
failing fifteen minutes into the first build, deep inside a bundler, with a message that names none of this.

## Prerequisites

| | |
| ---------------- | ------------------------------------------------------------------------------------------------ |
| **JDK 25**       | The example builds and runs on Java 25 |
| **Docker**       | For the PostgreSQL database and the S3-compatible object storage, see [`docker/docker-compose.yml`](../docker/docker-compose.yml) |
| **Node 24, with npm** | The runtime the site generator runs on. **24 is a floor, not a preference**: the site template declares `engines: node >= 24`, and the doc service compares the version it gets while it starts |
| **Google Chrome** | For the integration test only. The published documentation is driven in a real browser, because a diagram and the search exist only there - the browser comes from the machine and nothing is downloaded |

Anything that puts a Node 24 on the machine will do - `nvm`, `fnm`, `asdf`, `volta` or the distribution's own
package:

```shell
nvm install 24 && nvm use 24
node --version          # v24.x
```

## Everything at once

Everything this page describes is also a script:

```shell
./start.sh
```

It checks the four prerequisites before it does anything - including which Node it will hand the service, so
that the one failure this page exists for cannot happen silently - then builds, starts the containers and the
three services, imports the model and the reactions of every environment, waits until every part of the site
is published and opens the documentation. What follows is the same thing by hand, and what to read when the script stops at one of the
steps.

## What the Maven build does with npm

```shell
./mvnw install
```

Besides the usual, the build of `jme-doc-service` installs the site template's dependencies:

1. `maven-dependency-plugin` unpacks `package.json` and `package-lock.json` out of the ordinary `jeap-doc-site`
   artifact into `jme-doc-service/target/site-install`;
2. `exec-maven-plugin` runs `npm ci --omit=dev` over them there.

Both files come out of the artifact rather than being kept in this repository, so the dependencies are **by
definition** the ones this version of the doc service expects - which is what the service checks while it starts,
by comparing the installed lockfile with the one on its classpath.

It takes a few minutes and a few hundred megabytes the first time. A repeat build can skip it:

```shell
./mvnw install -DskipSiteInstall=true
```

**Skip it only while `jeap-doc-service.version` stays the same.** Bumping the doc service and keeping the old
`node_modules` is exactly the mistake the lockfile check exists to catch, and the service will refuse to start
until the install is run again.

An instance that ships a container does none of this: it installs the dependencies into its image, which is what
[the site image](https://github.com/jeap-admin-ch/jeap-doc-service/blob/main/docs/site-image.md) of the doc
service documents. This example runs on a developer machine, so it installs them into `target/` and points
`jeap.doc.build.node-modules-directory` there.

## Where the architecture model and the reactions come from

The doc service generates its documentation out of an architecture model and draws its runtime views out of
what a reaction observer saw happening, and this example runs neither of those services. It has
[`jme-doc-upstream-stub`](../jme-doc-upstream-stub) instead: one service of the example answering for both -
the `/docs-api` of an architecture repository and the graph API of a reaction observer - over a fixed landscape
of two systems, each behind the semantic role the real thing requires. Start it beside the other two:

```shell
./mvnw spring-boot:run -pl jme-doc-upstream-stub -Dspring-boot.run.profiles=local
```

The doc service reads it as all four environments of the site - see `jeap.doc.archrepo.environments` and
`jeap.doc.reactions.environments` in
[`application-local.yml`](../jme-doc-service/src/main/resources/application-local.yml), where each environment
names this one stub twice, so that every tree of the site carries the landscape and its runtime views. The
reactions are steps of the architecture import rather than a job of their own, and they run after the model:
an observed name means nothing until it has been resolved against the model. Nothing is imported on its own
schedule while developing: ask for it, and the import asks for every part of the site in turn.

## Telling the service where Node is

```yaml
jeap:
  doc:
    build:
      node-command: node
```

The default, `node`, is looked up on the `PATH` - **but not on yours**. The site generator is started as a child
process with an environment built from nothing, so that no secret of the service can reach it, and its `PATH` is
derived from this property: with a bare command name it is `/usr/bin:/bin` and nothing else.

That is the right thing in a container, and it means a machine whose Node is **not** in `/usr/bin` has to name
it. A Node from `nvm`, `fnm`, `asdf`, `volta` or Homebrew is such a Node. Find it and set the property:

```shell
which node                                   # e.g. /home/me/.nvm/versions/node/v24.16.0/bin/node
```

```shell
# either as an environment variable of the service
JEAP_DOC_BUILD_NODECOMMAND=$(which node) ./mvnw spring-boot:run -pl jme-doc-service -Dspring-boot.run.profiles=local

# or on the command line
./mvnw spring-boot:run -pl jme-doc-service -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.jvmArguments="-Djeap.doc.build.node-command=$(which node)"
```

The property names the **binary**, not its directory - and the directory it lives in is what goes on the child's
`PATH`, ahead of `/usr/bin`, so `npx` and everything else beside that Node are found with it.

## Asking for a site to be published

An upload asks for a build of the part of the site that carries its system, and the instance picks that request
up within `jeap.doc.build.poll-interval` - 30 seconds. The default site is also published by every architecture
import that stores a changed landscape. A site no architecture import feeds - the `handbook` of this example, whose
one environment has no architecture repository - is asked for on `jeap.doc.build.reconcile-cron` instead, every
four hours through the working day. None of it is a feedback loop anybody wants while developing, so ask for a
build directly with the operator client, at `/api/sites/default/builds` or `/api/sites/handbook/builds` - see
[Publish the site](https://github.com/jme-admin-ch/jme-doc-service-example#publish-the-site-and-read-what-the-generator-did)
in the README.

A build works in `jeap.doc.build.workspace-directory`, which defaults to the temporary directory of the JVM, and
deletes the workspace afterwards. To keep it and look at what the generator was given:

```yaml
jeap:
  doc:
    build:
      keep-workspace: true
```

It is a disk leak with a purpose - the service warns while it is on - and it is for reproducing a failure, not
for leaving on.

## When the service does not start

`jeap.doc.build.*` is checked while the context is built, so all of these are startup failures with a message
naming the property. The order below is the order to read them in.

| The service says | What it means |
| ---------------- | ------------- |
| *The dependencies of the site template are not at …* | `./mvnw install` has not run, or ran with `-DskipSiteInstall=true` on a clean `target/`. Run it without the flag |
| *Cannot find package 'pagefind'* in a build's log | `jeap.doc.build.node-modules-directory` is a relative path. The search indexer symlinks that directory into a workspace of its own, so a relative path is resolved against the workspace and the link dangles. The instance uses `${user.dir}/target/site-install/node_modules` for that reason. The site is published all the same, without a search index |
| *… were installed from a different `package-lock.json` than the one this doc service carries* | `jeap-doc-service.version` moved and the dependencies did not. Run `./mvnw install` again, without `-DskipSiteInstall=true` |
| *Node … or newer is needed* | The Node the service found is too old. `node --version`, then `nvm use 24` - and remember the service looks it up on **its** `PATH`, not yours, so check `jeap.doc.build.node-command` |
| *Cannot run program "node"* | There is no Node where the service looked. Same property |
| *The configured object storage bucket … is not available* | The object storage is not up, or its bucket was never created. `docker compose -f docker/docker-compose.yml up -d` creates both |

The integration test hits all of this too: if
[`DocSiteExampleIT`](../jme-doc-test/src/test/java/ch/admin/bit/jeap/jme/doc/DocSiteExampleIT.java) hangs waiting
for the doc service to become ready, the reason is in the output of the service it started, and it is one of the
rows above.
