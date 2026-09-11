#!/usr/bin/env bash
#
# Runs the whole example on a developer machine and opens the documentation it generates.
#
#   ./start.sh                  build, start everything, generate the site, open a browser
#   ./start.sh --skip-build     the same without the Maven build, for a repeat start
#   ./start.sh --no-browser     do not open a browser
#   ./start.sh --keep-running   return to the prompt and leave the services running
#
# What it does, in the order a documentation site comes into being: it checks the machine can do all of it,
# builds the example, starts the database and the object storage, starts the OAuth mock server, the upstream
# stub and the doc service, asks for the architecture model to be imported, waits until every part of the site
# is published, and opens the site in a browser.
#
# Every step either succeeds or stops the script, and a step that stops it says what failed and where to look -
# the services log into target/local/.
#
# By default the script stays in the foreground and stops the three services again on Ctrl-C. The containers
# of docker/docker-compose.yml are left running: they hold the database and the object storage, and starting
# them again is free. Take them down with
#
#   docker compose -f docker/docker-compose.yml down
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE_FILE="docker/docker-compose.yml"
LOG_DIR="target/local"

# The one site this instance publishes - see jme-doc-service/src/main/resources/application.yml. Its four
# environments all read the one upstream stub, so nothing below has to name any of them.
SITE="default"

# How long each phase may take before the script gives up on it.
SERVICE_TIMEOUT=240
IMPORT_TIMEOUT=300
BUILD_TIMEOUT=1800

# Node 24 is a floor rather than a preference: the site template declares it, and the doc service compares
# what it finds while it starts. See docs/local-development.md.
NODE_MINIMUM=24

# How often this run's doc service looks whether a build has been asked for. Far below the 30 seconds of a
# real instance, because everything below waits for a build.
POLL_INTERVAL="PT5S"

# --------------------------------------------------------------------------------- output

if [[ -t 1 ]]; then
    BOLD=$'\033[1m'; DIM=$'\033[2m'; RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'
    BLUE=$'\033[34m'; OFF=$'\033[0m'
else
    BOLD=''; DIM=''; RED=''; GREEN=''; YELLOW=''; BLUE=''; OFF=''
fi

step()     { clear_progress; printf '\n%s%s  %s%s\n' "$BOLD" "$1" "$2" "$OFF"; }
ok()       { clear_progress; printf '   %s✓%s %s\n' "$GREEN" "$OFF" "$1"; }
note()     { clear_progress; printf '   %s·%s %s%s%s\n' "$DIM" "$OFF" "$DIM" "$1" "$OFF"; }
progress() { [[ -t 1 ]] && printf '\r   %s⏳ %-66s%s' "$DIM" "$1" "$OFF" || true; }
clear_progress() { [[ -t 1 ]] && printf '\r%-72s\r' '' || true; }

# Stops the script. Everything that can fail says what failed, and - where the reason is in a log rather than
# in the message - which log holds it, with its last lines.
die() {
    clear_progress
    printf '\n   %s✗ %s%s\n' "$RED" "$1" "$OFF" >&2
    [[ $# -ge 2 ]] && printf '     %s\n' "$2" >&2
    if [[ $# -ge 3 && -f "$3" ]]; then
        printf '\n     %slast lines of %s:%s\n' "$DIM" "$3" "$OFF" >&2
        sed -e 's/^/     | /' <(tail -n 25 "$3") >&2
    fi
    exit 1
}

elapsed() { printf '%ds' "$(($(date +%s) - $1))"; }

# --------------------------------------------------------------------------------- arguments

SKIP_BUILD=0
OPEN_BROWSER=1
KEEP_RUNNING=0

for arg in "$@"; do
    case "$arg" in
        --skip-build)   SKIP_BUILD=1 ;;
        --no-browser)   OPEN_BROWSER=0 ;;
        --keep-running) KEEP_RUNNING=1 ;;
        -h|--help)      sed -n '2,24p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)              echo "unknown option: $arg (try --help)" >&2; exit 1 ;;
    esac
done

# --------------------------------------------------------------------------------- shutting down again

SERVICE_PIDS=()
SERVICE_NAMES=()
BUILD_PID=""
USE_SETSID=0
command -v setsid >/dev/null 2>&1 && USE_SETSID=1

# A service is started through the Maven wrapper, which forks the JVM that is the service - so stopping it is
# stopping a tree and not a process. A service started into a session of its own is signalled as that session;
# anything else has its children signalled before itself. Whichever of the three applies, the other two are a
# no-op, so all of them are tried.
stop_tree() {
    local pid="$1"
    kill -TERM -- -"$pid" 2>/dev/null || true
    pkill -TERM -P "$pid" 2>/dev/null || true
    kill -TERM "$pid" 2>/dev/null || true
}

cleanup() {
    [[ -n "$BUILD_PID" ]] && stop_tree "$BUILD_PID"
    if [[ "$KEEP_RUNNING" -eq 1 && ${#SERVICE_PIDS[@]} -gt 0 ]]; then
        return
    fi
    if [[ ${#SERVICE_PIDS[@]} -gt 0 ]]; then
        clear_progress
        printf '\n%s🛑  Stopping the services%s\n' "$BOLD" "$OFF"
        local i
        for i in "${!SERVICE_PIDS[@]}"; do
            stop_tree "${SERVICE_PIDS[$i]}"
            ok "${SERVICE_NAMES[$i]} stopped"
        done
        note "the database and the object storage are still up: docker compose -f $COMPOSE_FILE down"
    fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# --------------------------------------------------------------------------------- HTTP helpers

# The token of whoever operates the instance: it may ask for the model to be imported, ask for a site to be
# published and read what the generator did. It is taken again now and then, because generating a site takes
# longer than a token lives - and only in the shell the script itself runs in, so that the one it took is the
# one every command substitution below sees.
OPERATOR_TOKEN=""
OPERATOR_TOKEN_AT=0

refresh_operator_token() {
    local now response
    now=$(date +%s)
    [[ -n "$OPERATOR_TOKEN" && $((now - OPERATOR_TOKEN_AT)) -lt 120 ]] && return 0
    response=$(curl -sf --max-time 10 -X POST "$AUTH_BASE_URL/oauth2/token" \
        -d grant_type=client_credentials -d client_id=jme-doc-operator -d client_secret=secret) \
        || die "The OAuth mock server issued no token for jme-doc-operator." \
               "Is $AUTH_BASE_URL still up?" "$LOG_DIR/jme-doc-auth-scs.log"
    OPERATOR_TOKEN=$(jq -er '.access_token' <<<"$response") \
        || die "The answer of the OAuth mock server carries no access token." "$response"
    OPERATOR_TOKEN_AT="$now"
}

api_get() {
    curl -sf --max-time 20 -H "Authorization: Bearer $OPERATOR_TOKEN" "$DOC_BASE_URL$1"
}

api_post() {
    curl -s --max-time 30 -o "$2" -w '%{http_code}' -X POST \
        -H "Authorization: Bearer $OPERATOR_TOKEN" "$DOC_BASE_URL$1"
}

http_status() { curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$1"; }

# --------------------------------------------------------------------------------- 1. the machine

step "🔎" "Checking this machine"

command -v curl >/dev/null || die "curl is not installed." "It is what this script talks to the services with."
command -v jq   >/dev/null || die "jq is not installed." "It is what this script reads the API answers with."

JAVA_VERSION=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1) \
    || die "No Java on the PATH." "This example builds and runs on JDK 25."
[[ -n "$JAVA_VERSION" && "$JAVA_VERSION" -ge 25 ]] \
    || die "Java ${JAVA_VERSION:-?} is too old - this example builds and runs on JDK 25." \
           "Point JAVA_HOME at a JDK 25 and put its bin directory on the PATH."
ok "Java $JAVA_VERSION"

docker info >/dev/null 2>&1 \
    || die "Docker is not running." "The database and the object storage of the example are containers."
ok "Docker is running"

# The Node the site generator runs on. A Node from nvm, fnm, asdf or volta is not on /usr/bin, and the
# generator is a child process whose PATH is derived from jeap.doc.build.node-command - so the binary is
# resolved here and named to the service below. See docs/local-development.md.
resolve_node() {
    local candidate major
    if candidate=$(command -v node 2>/dev/null); then
        major=$("$candidate" --version | sed -n 's/^v\([0-9]*\).*/\1/p')
        if [[ -n "$major" && "$major" -ge "$NODE_MINIMUM" ]]; then
            printf '%s' "$candidate"
            return 0
        fi
    fi
    # A Node that is installed but not the one on the PATH - the usual case on a machine with several.
    local nvm_root="${NVM_DIR:-$HOME/.nvm}/versions/node"
    if [[ -d "$nvm_root" ]]; then
        candidate=$(find "$nvm_root" -mindepth 3 -maxdepth 3 -type f -path '*/bin/node' 2>/dev/null \
            | sort -V | tail -1)
        if [[ -n "$candidate" ]]; then
            major=$("$candidate" --version | sed -n 's/^v\([0-9]*\).*/\1/p')
            [[ -n "$major" && "$major" -ge "$NODE_MINIMUM" ]] && { printf '%s' "$candidate"; return 0; }
        fi
    fi
    return 1
}

NODE_BIN=$(resolve_node) \
    || die "No Node $NODE_MINIMUM or newer on this machine." \
           "The doc service publishes by running the site generator, and refuses to start without one: nvm install $NODE_MINIMUM"
NODE_DIR=$(dirname "$NODE_BIN")
[[ -x "$NODE_DIR/npm" ]] || command -v npm >/dev/null \
    || die "No npm beside $NODE_BIN." "The build installs the site template's dependencies with npm ci."
# Ahead of the PATH, so the Maven build installs the dependencies with the very Node the service will run.
export PATH="$NODE_DIR:$PATH"
ok "Node $("$NODE_BIN" --version) ($NODE_BIN)"

# The browser is looked for now rather than after ten minutes of building.
BROWSER_CMD=()
if [[ "$OPEN_BROWSER" -eq 1 ]]; then
    if [[ "$(uname -s)" == "Darwin" ]]; then
        BROWSER_CMD=(open -a "Google Chrome")
    else
        for candidate in google-chrome google-chrome-stable chromium chromium-browser; do
            if command -v "$candidate" >/dev/null 2>&1; then BROWSER_CMD=("$candidate"); break; fi
        done
    fi
    [[ ${#BROWSER_CMD[@]} -gt 0 ]] \
        || die "Neither Chrome nor Chromium is on the PATH." \
               "Install one, or start without a browser: ./start.sh --no-browser"
    ok "Browser: ${BROWSER_CMD[*]}"
fi

# The ports the services listen on, read where they are configured so that the two cannot drift apart.
port_of() {
    sed -n 's/^[[:space:]]*port:[[:space:]]*\([0-9][0-9]*\).*/\1/p' \
        "$1/src/main/resources/application-local.yml" | head -1
}

AUTH_PORT=$(port_of jme-doc-auth-scs)
STUB_PORT=$(port_of jme-doc-upstream-stub)
DOC_PORT=$(port_of jme-doc-service)
[[ -n "$AUTH_PORT" && -n "$STUB_PORT" && -n "$DOC_PORT" ]] \
    || die "Could not read the ports out of the application-local.yml files of the modules."

AUTH_BASE_URL="http://localhost:$AUTH_PORT/jme-doc-auth-scs"
STUB_BASE_URL="http://localhost:$STUB_PORT/jme-doc-upstream-stub"
DOC_BASE_URL="http://localhost:$DOC_PORT/jme-doc-service"

port_taken() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

for spec in "$AUTH_PORT:jme-doc-auth-scs" "$STUB_PORT:jme-doc-upstream-stub" "$DOC_PORT:jme-doc-service"; do
    if port_taken "${spec%%:*}"; then
        die "Port ${spec%%:*} is in use, and ${spec##*:} needs it." \
            "Another run of the example is probably still up - stop it first."
    fi
done
ok "Ports $AUTH_PORT, $STUB_PORT and $DOC_PORT are free"

mkdir -p "$LOG_DIR"

# --------------------------------------------------------------------------------- 2. the build

step "📦" "Building the example"

# Runs a command into a log file, showing how long it has been at it, and answers its exit code.
run_logged() {
    local log="$1" label="$2"; shift 2
    "$@" >"$log" 2>&1 &
    BUILD_PID=$!
    local started status
    started=$(date +%s)
    while kill -0 "$BUILD_PID" 2>/dev/null; do
        progress "$label ($(elapsed "$started"))"
        sleep 1
    done
    status=0
    wait "$BUILD_PID" || status=$?
    BUILD_PID=""
    clear_progress
    return "$status"
}

DOC_SERVICE_VERSION=$(sed -n 's|.*<jeap-doc-service.version>\(.*\)</jeap-doc-service.version>.*|\1|p' pom.xml)
SITE_INSTALL="jme-doc-service/target/site-install"
SITE_INSTALL_STAMP="$SITE_INSTALL/.installed-for-version"

if [[ "$SKIP_BUILD" -eq 1 ]]; then
    note "skipped (--skip-build)"
    [[ -d "$SITE_INSTALL/node_modules" ]] \
        || die "The dependencies of the site template are not at $SITE_INSTALL/node_modules." \
               "The doc service does not start without them - run ./start.sh without --skip-build."
else
    MAVEN_ARGS=(./mvnw install -DskipTests)
    # npm ci takes minutes and downloads a few hundred megabytes, so it is run when it would install
    # something else than what is already there - which is decided by the doc service version, the very
    # thing the service compares its lockfile against while it starts.
    if [[ -d "$SITE_INSTALL/node_modules" && -f "$SITE_INSTALL_STAMP" ]] \
        && [[ "$(cat "$SITE_INSTALL_STAMP")" == "$DOC_SERVICE_VERSION" ]]; then
        MAVEN_ARGS+=(-DskipSiteInstall=true)
        note "the site template's dependencies are installed for doc service $DOC_SERVICE_VERSION"
    else
        note "installing the site template's dependencies for doc service $DOC_SERVICE_VERSION - a few minutes"
    fi

    run_logged "$LOG_DIR/build.log" "building" "${MAVEN_ARGS[@]}" \
        || die "The Maven build failed." "" "$LOG_DIR/build.log"
    printf '%s' "$DOC_SERVICE_VERSION" >"$SITE_INSTALL_STAMP"
    ok "built"
fi

# --------------------------------------------------------------------------------- 3. the infrastructure

step "🐘" "Starting the database and the object storage"

run_logged "$LOG_DIR/docker-compose.log" "docker compose up" \
    docker compose -f "$COMPOSE_FILE" up -d \
    || die "docker compose could not start the containers." "" "$LOG_DIR/docker-compose.log"

container_of() { docker compose -f "$COMPOSE_FILE" ps -aq "$1" 2>/dev/null | head -1; }

wait_container() {
    local service="$1" wanted="$2" started deadline id state
    started=$(date +%s)
    deadline=$((started + 180))
    while :; do
        id=$(container_of "$service")
        if [[ -n "$id" ]]; then
            state=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
                "$id" 2>/dev/null || true)
            [[ "$state" == "$wanted" ]] && return 0
        fi
        [[ $(date +%s) -gt $deadline ]] && return 1
        progress "$service is $([[ -n "${state:-}" ]] && echo "$state" || echo "starting") ($(elapsed "$started"))"
        sleep 2
    done
}

wait_container jme-doc-db healthy \
    || die "The database did not become healthy." "docker compose -f $COMPOSE_FILE logs jme-doc-db"
ok "database on port 5432"

wait_container jme-doc-objectstorage healthy \
    || die "The object storage did not become healthy." \
           "docker compose -f $COMPOSE_FILE logs jme-doc-objectstorage"
ok "object storage on port 9000"

# The bucket is created with the storage, and the doc service refuses to start without it - so whether the
# one-shot container that creates it succeeded is worth knowing here rather than in a startup failure.
wait_container jme-doc-objectstorage-init exited \
    || die "The container creating the bucket never finished." \
           "docker compose -f $COMPOSE_FILE logs jme-doc-objectstorage-init"
INIT_EXIT=$(docker inspect -f '{{.State.ExitCode}}' "$(container_of jme-doc-objectstorage-init)")
[[ "$INIT_EXIT" == "0" ]] \
    || die "The bucket of the documentation was not created (exit code $INIT_EXIT)." \
           "docker compose -f $COMPOSE_FILE logs jme-doc-objectstorage-init"
ok "bucket jme-doc-service-documents with its lifecycle rule"

# --------------------------------------------------------------------------------- 4. the services

step "🚀" "Starting the services"

start_service() {
    local module="$1" base_url="$2"; shift 2
    local log="$LOG_DIR/$module.log" pid started deadline
    : >"$log"

    if [[ "$USE_SETSID" -eq 1 ]]; then
        setsid ./mvnw --quiet --projects "$module" spring-boot:run \
            -Dspring-boot.run.profiles=local "$@" >"$log" 2>&1 &
    else
        ./mvnw --quiet --projects "$module" spring-boot:run \
            -Dspring-boot.run.profiles=local "$@" >"$log" 2>&1 &
    fi
    pid=$!
    SERVICE_PIDS+=("$pid")
    SERVICE_NAMES+=("$module")

    started=$(date +%s)
    deadline=$((started + SERVICE_TIMEOUT))
    while :; do
        kill -0 "$pid" 2>/dev/null \
            || die "$module stopped while it was starting." "" "$log"
        [[ "$(http_status "$base_url/actuator/health/readiness")" == "200" ]] && return 0
        [[ $(date +%s) -gt $deadline ]] \
            && die "$module was not ready within ${SERVICE_TIMEOUT}s." \
                   "docs/local-development.md lists what each startup failure means." "$log"
        progress "$module is starting ($(elapsed "$started"))"
        sleep 2
    done
}

start_service jme-doc-auth-scs "$AUTH_BASE_URL"
ok "OAuth mock server on $AUTH_BASE_URL"

start_service jme-doc-upstream-stub "$STUB_BASE_URL"
ok "upstream stub on $STUB_BASE_URL"

# Four settings are this script's rather than the instance's, and each one makes the outcome below the outcome
# of something this script did: the Node of this machine, no import on startup so that the only import is the
# one asked for here, and a poll interval that starts a build seconds after it was asked for.
start_service jme-doc-service "$DOC_BASE_URL" \
    -Dspring-boot.run.jvmArguments="-Djeap.doc.build.node-command=$NODE_BIN -Djeap.doc.archrepo.import.on-startup=false -Djeap.doc.build.poll-interval=$POLL_INTERVAL"
ok "doc service on $DOC_BASE_URL"

# --------------------------------------------------------------------------------- 5. the documentation

step "🏗" "Generating the documentation site"

PARTS_PATH="/api/sites/$SITE/parts"
BUILDS_PATH="/api/sites/$SITE/builds"
IMPORTS_PATH="/api/architecture/imports"
IMPORT_STATE_PATH="/api/architecture/environments"

# Where the import of every environment stands, as one line: when each step of each of them last succeeded,
# and why any of them last failed.
#
# <b>Every step, not only the model.</b> The reactions are steps of the same import and they run after it, and
# the documentation is asked for once the whole chain of an environment has run - so a site read as soon as
# the model is in is a site read before its runtime views exist.
import_state() {
    api_get "$IMPORT_STATE_PATH" \
        | jq -r '[.[] | .imports[]]
                 | "\([.[].lastSuccessAt // "never"] | join(","))\t\([.[].failureReason // empty] | join("; "))"' \
        || die "The doc service did not answer where its imports stand." "" \
               "$LOG_DIR/jme-doc-service.log"
}

refresh_operator_token

BASELINE_BUILD_ID=$(api_get "$BUILDS_PATH" | jq -r '[.[].id] | max // 0') \
    || die "The doc service did not answer the build history." "" "$LOG_DIR/jme-doc-service.log"
IMPORTED_BEFORE=$(import_state | cut -f1)

RESPONSE="$LOG_DIR/api-response.json"
STATUS=$(api_post "$IMPORTS_PATH" "$RESPONSE")
[[ "$STATUS" == "202" ]] \
    || die "Asking for the architecture model to be imported answered $STATUS." "$(cat "$RESPONSE")"
note "every environment reads its model and its reactions from $STUB_BASE_URL"

IMPORT_STARTED=$(date +%s)
while :; do
    refresh_operator_token
    IMPORT_STATE=$(import_state)
    IMPORTED_NOW=${IMPORT_STATE%%$'\t'*}
    IMPORT_FAILURE=${IMPORT_STATE#*$'\t'}
    [[ "$IMPORTED_NOW" != "$IMPORTED_BEFORE" && "$IMPORTED_NOW" != *never* ]] && break
    [[ -n "$IMPORT_FAILURE" ]] \
        && die "An architecture import failed." "$IMPORT_FAILURE" "$LOG_DIR/jme-doc-service.log"
    [[ $(($(date +%s) - IMPORT_STARTED)) -gt $IMPORT_TIMEOUT ]] \
        && die "The architecture repository was not imported within ${IMPORT_TIMEOUT}s." "" \
               "$LOG_DIR/jme-doc-service.log"
    progress "importing the model and the reactions of every environment ($(elapsed "$IMPORT_STARTED"))"
    sleep 2
done

PARTS=$(api_get "$PARTS_PATH") \
    || die "The doc service did not answer the parts of the site." "" "$LOG_DIR/jme-doc-service.log"
ok "model and reactions imported: $(jq -r 'length' <<<"$PARTS") parts - $(jq -r '[.[].part] | join(", ")' <<<"$PARTS")"

# The import is also the trigger: having stored a landscape that is not the one already there, it asks for
# every part of the site. When it stored the same landscape it asked for nothing - and then the site is asked
# for here, which is the forcing kind of ask and publishes every part whether its content moved or not.
NOTHING_UNDERWAY=$(jq -r 'all(.owedABuild == false and .publishedAt != null)' <<<"$PARTS")
RUNNING=$(api_get "/api/sites/$SITE" | jq -r '.running | length') \
    || die "The doc service did not answer where the site stands." "" "$LOG_DIR/jme-doc-service.log"
if [[ "$NOTHING_UNDERWAY" == "true" && "$RUNNING" == "0" ]]; then
    STATUS=$(api_post "$BUILDS_PATH" "$RESPONSE")
    [[ "$STATUS" == "202" ]] \
        || die "Asking for the site to be published answered $STATUS." "$(cat "$RESPONSE")"
    note "the model had not moved, so every part was asked for: $(jq -r '.partsRequested' "$RESPONSE") part(s)"
else
    note "the import asked for every part of the site"
fi

WANTED=$(jq -r 'length' <<<"$PARTS")
BUILD_STARTED=$(date +%s)
REPORTED=""

while :; do
    refresh_operator_token

    # Only the builds this run led to: the containers keep their data, so a second run reads the history of
    # the first one too.
    BUILDS=$(api_get "$BUILDS_PATH" | jq -c --argjson since "$BASELINE_BUILD_ID" '[.[] | select(.id > $since)]') \
        || die "The doc service did not answer the build history." "" "$LOG_DIR/jme-doc-service.log"

    FAILED=$(jq -c '[.[] | select(.state == "FAILED" or .state == "ABANDONED" or .state == "ABORTED")][0] // empty' \
        <<<"$BUILDS")
    if [[ -n "$FAILED" ]]; then
        die "The build of the part '$(jq -r '.part' <<<"$FAILED")' ended as $(jq -r '.state' <<<"$FAILED")." \
            "$(jq -r '.failureReason // "The doc service logged what happened."' <<<"$FAILED")" \
            "$LOG_DIR/jme-doc-service.log"
    fi

    # Each part as it is done, rather than one line once all of them are: a part is twenty to thirty seconds
    # of site generator, and they are generated one after another here.
    while read -r line; do
        [[ -z "$line" ]] && continue
        part=${line%% *}
        if [[ ",$REPORTED," != *",$part,"* ]]; then
            REPORTED="$REPORTED,$part"
            ok "$line"
        fi
    done < <(jq -r '.[] | select(.state == "SUCCEEDED" or .state == "SKIPPED")
                  | "\(.part) - \(if .state == "SKIPPED" then "unchanged" else "\(.pageCount) pages" end), \((.durationMillis / 1000) | floor)s"' \
             <<<"$BUILDS")

    PARTS=$(api_get "$PARTS_PATH") \
        || die "The doc service did not answer the parts of the site." "" "$LOG_DIR/jme-doc-service.log"
    PUBLISHED=$(jq -r 'all(.publishedAt != null and .owedABuild == false)' <<<"$PARTS")
    BUILT=$(jq -r '[.[] | select(.state == "SUCCEEDED" or .state == "SKIPPED") | .part] | unique | length' \
        <<<"$BUILDS")
    [[ "$PUBLISHED" == "true" && "$BUILT" -ge "$WANTED" ]] && break

    [[ $(($(date +%s) - BUILD_STARTED)) -gt $BUILD_TIMEOUT ]] \
        && die "The site was not published within ${BUILD_TIMEOUT}s: $BUILT of $WANTED parts are done." \
               "" "$LOG_DIR/jme-doc-service.log"
    progress "generating part $((BUILT + 1)) of $WANTED ($(elapsed "$BUILD_STARTED"))"
    sleep 2
done

PAGES=$(jq -r '[.[].pageCount] | add // 0' <<<"$BUILDS")
ok "the site is published: $WANTED parts, $PAGES pages, in $(elapsed "$BUILD_STARTED")"

SITE_URL="$DOC_BASE_URL/"
[[ "$(http_status "$SITE_URL")" == "200" ]] \
    || die "The site is not being served at $SITE_URL." "" "$LOG_DIR/jme-doc-service.log"

# --------------------------------------------------------------------------------- 6. the browser

if [[ "$OPEN_BROWSER" -eq 1 ]]; then
    step "🌐" "Opening the documentation"
    "${BROWSER_CMD[@]}" "$SITE_URL" >/dev/null 2>&1 &
    disown 2>/dev/null || true
    ok "${BROWSER_CMD[0]} is showing $SITE_URL"
fi

# --------------------------------------------------------------------------------- what is up

clear_progress
printf '\n%s✨  The example is up%s\n\n' "$BOLD$GREEN" "$OFF"
link() { printf '   %s%-24s%s %s\n' "$BLUE" "$1" "$OFF" "$2"; }

link "The documentation"        "$SITE_URL"
link "Generated from the model" "$DOC_BASE_URL/systems/jme/"
link "The search"               "$DOC_BASE_URL/search/"
link "The API"                  "$DOC_BASE_URL/swagger-ui.html"
link "The OAuth mock server"    "$AUTH_BASE_URL"
link "The upstream stub"        "$STUB_BASE_URL"
link "The logs"                 "$LOG_DIR/"
printf '\n'

if [[ "$KEEP_RUNNING" -eq 1 ]]; then
    printf '   %sThe services keep running. Stop them with:%s\n' "$DIM" "$OFF"
    # Where the services were started into a session of their own, the whole session is signalled - the
    # Maven wrapper forked the JVM that is the service, so its process id alone would leave that JVM behind.
    if [[ "$USE_SETSID" -eq 1 ]]; then
        printf '     kill --'; printf ' -%s' "${SERVICE_PIDS[@]}"; printf '\n'
    else
        printf '     kill %s\n' "${SERVICE_PIDS[*]}"
    fi
    printf '     docker compose -f %s down\n\n' "$COMPOSE_FILE"
    exit 0
fi

printf '   %sPress Ctrl-C to stop the services again.%s\n' "$DIM" "$OFF"

# Waiting on the services rather than sleeping: a service that dies takes the example with it, and saying so
# is better than a site that stops answering without a word.
while :; do
    for i in "${!SERVICE_PIDS[@]}"; do
        kill -0 "${SERVICE_PIDS[$i]}" 2>/dev/null \
            || die "${SERVICE_NAMES[$i]} stopped." "" "$LOG_DIR/${SERVICE_NAMES[$i]}.log"
    done
    sleep 5
done
