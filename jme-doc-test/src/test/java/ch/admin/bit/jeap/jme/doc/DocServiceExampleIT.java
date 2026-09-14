package ch.admin.bit.jeap.jme.doc;

import ch.admin.bit.jeap.jme.test.BootServiceSpringIntegrationTestBase;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Uploads documentation to the doc service of this example, with a token of the OAuth mock server.
 * <p>
 * The test starts both services and shows the two rules a doc pipeline has to know: a pipeline may publish the
 * documentation of its own system and of no other system, and the upload id it chooses is the idempotency key of
 * the upload - repeating a request under it never publishes a second documentation set.
 * <p>
 * It also drives the step <b>before</b> the upload: a pipeline sends the paths it is about to pack and is told
 * whether the doc service would accept them, so a misfiled tree costs a request rather than a build. Nothing is
 * stored by that call and no file's bytes are sent - what is in the files is the doc workflow's own half of the
 * validation.
 * <p>
 * That endpoint is advisory, and the upload is not: a set that would not be published is refused by the upload
 * itself, with the same findings, so a pipeline that skipped the check cannot put a page where nothing serves
 * it.
 */
class DocServiceExampleIT extends BootServiceSpringIntegrationTestBase {

    private static final List<Integer> SERVICE_PORTS = reserveFreePorts(2);
    private static final int AUTH_PORT = SERVICE_PORTS.getFirst();
    private static final int DOC_PORT = SERVICE_PORTS.get(1);
    private static final String AUTH_BASE_URL = "http://localhost:" + AUTH_PORT + "/jme-doc-auth-scs";
    private static final String DOC_BASE_URL = "http://localhost:" + DOC_PORT + "/jme-doc-service";

    private static final String SYSTEM = DocumentationSets.SYSTEM;

    /** The default site, the one an upload that names none belongs to. */
    private static final String SITE = "default";

    /**
     * Below the upload path, because what is validated is a documentation upload - the same parameters, the
     * same role, and the same interceptor refusing a parameter the doc service does not know.
     */
    private static final String VALIDATION_PATH = "/api/uploads/docs/validation";

    /**
     * The tag every uploaded bundle carries. The lifecycle rule of the bucket selects on it rather than on the
     * prefix, because jeap.doc.storage.upload-prefix is configured per instance while the tag is the same
     * everywhere - see docker/docker-compose.yml.
     */
    private static final String UPLOAD_TAG_KEY = "jeap-doc-content";
    private static final String UPLOAD_TAG_VALUE = "upload";

    /**
     * What an accepted set becomes lies below this prefix, tagged {@code current} - which no lifecycle rule
     * selects, because it is the only copy there is.
     */
    private static final String CURRENT_PREFIX = "current/";
    private static final String CURRENT_TAG_VALUE = "current";

    /** The attribute of the one script the service adds to every page of a microsite. */
    private static final String MICROSITE_SHIM_MARKER = "data-jeap-doc-microsite-shim";

    @Value("${jme-doc-test.objectstorage.endpoint-url}")
    private URI objectStorageEndpoint;
    @Value("${jme-doc-test.objectstorage.region}")
    private String objectStorageRegion;
    @Value("${jme-doc-test.objectstorage.access-key}")
    private String objectStorageAccessKey;
    @Value("${jme-doc-test.objectstorage.secret-key}")
    private String objectStorageSecretKey;
    @Value("${jme-doc-test.objectstorage.bucket}")
    private String documentationBucket;

    @BeforeAll
    static void startServices() throws Exception {
        startService("jme-doc-auth-scs", AUTH_BASE_URL, Map.of(
                "server.port", String.valueOf(AUTH_PORT),
                "mockserver.base-url", AUTH_BASE_URL));
        startService("jme-doc-service", DOC_BASE_URL, Map.of(
                "server.port", String.valueOf(DOC_PORT),
                "jeap.security.oauth2.resourceserver.authorization-server.issuer", AUTH_BASE_URL,
                "jeap.security.oauth2.resourceserver.authorization-server.jwk-set-uri",
                AUTH_BASE_URL + "/.well-known/jwks.json",
                // This suite is about what becomes of an upload, and the publishing half is
                // DocSiteExampleIT's. Switching the three triggers off keeps a site generation from starting
                // behind every test here, and keeps the upstream stub out of a suite that has nothing to say
                // about the architecture model.
                "jeap.doc.sites." + SITE + ".publish-on-upload", "false",
                "jeap.doc.archrepo.import.on-startup", "false",
                "jeap.doc.archrepo.import.cron", "",
                "jeap.doc.build.reconcile-cron", "-"));
    }

    @Test
    void uploadWithTheWriteRoleOfTheOwnSystemIsStored() {
        UUID uploadId = UUID.randomUUID();

        upload(uploadId, uploadToken(), documentationSetParameters())
                .then()
                .statusCode(201)
                .body("uploadId", equalTo(uploadId.toString()))
                .body("id", notNullValue())
                .body("state", equalTo("PENDING"))
                .body("sizeInBytes", greaterThan(0))
                .body("receivedAt", notNullValue());
    }

    /**
     * The upload id is the idempotency key: a pipeline may retry without asking whether its previous attempt got
     * through. The repetition stores nothing a second time and answers with the result of the attempt that did.
     */
    @Test
    void repeatingAnUploadUnderTheSameUploadIdStoresNothingASecondTime() {
        UUID uploadId = UUID.randomUUID();
        String accessToken = uploadToken();

        int id = upload(uploadId, accessToken, documentationSetParameters())
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        upload(uploadId, accessToken, documentationSetParameters())
                .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("state", equalTo("PENDING"));
    }

    /**
     * An upload id that was already used for something else is a mistake - a copied configuration, or an id that
     * is not unique - and the second documentation set is refused instead of taking the first one's place.
     */
    @Test
    void anotherDocumentationSetUnderAUsedUploadIdIsRejected() {
        UUID uploadId = UUID.randomUUID();
        String accessToken = uploadToken();

        upload(uploadId, accessToken, documentationSetParameters())
                .then()
                .statusCode(201);

        Map<String, String> otherComponent = documentationSetParameters();
        otherComponent.put("component", "jme-doc-auth-scs");

        upload(uploadId, accessToken, otherComponent)
                .then()
                .statusCode(409)
                .body("code", equalTo("UPLOAD_ID_CONFLICT"));
    }

    @Test
    void uploadForAnotherSystemIsRejected() {
        String accessToken = fetchAccessToken(AUTH_BASE_URL, "other-system-doc-pipeline", "secret");

        upload(UUID.randomUUID(), accessToken, documentationSetParameters())
                .then()
                .statusCode(403);
    }

    /**
     * Reading the documentation is a resource of its own: the role that grants it does not let a client change
     * anything.
     */
    @Test
    void uploadWithTheReadRoleOnlyIsRejected() {
        String accessToken = fetchAccessToken(AUTH_BASE_URL, "jme-doc-reader", "secret");

        upload(UUID.randomUUID(), accessToken, documentationSetParameters())
                .then()
                .statusCode(403);
    }

    @Test
    void uploadWithoutTokenIsRejected() {
        given().baseUri(DOC_BASE_URL)
                .contentType("application/zip")
                .queryParams(documentationSetParameters())
                .body(documentationSet())
                .when()
                .put(uploadPath(UUID.randomUUID()))
                .then()
                .statusCode(401);
    }

    @Test
    void uploadWithoutTheVersionOfTheComponentNamesTheMissingParameter() {
        Map<String, String> withoutVersion = documentationSetParameters();
        withoutVersion.put("version", "");

        upload(UUID.randomUUID(), uploadToken(), withoutVersion)
                .then()
                .statusCode(400)
                .body("code", equalTo("MISSING_PARAMETER"));
    }

    /**
     * A parameter the doc service does not know is a typo in the doc workflow configuration of a repository, and
     * it has to fail loudly instead of silently publishing something else than the repository intended.
     */
    @Test
    void uploadWithAnUnknownParameterNamesTheTypo() {
        Map<String, String> withATypo = documentationSetParameters();
        withATypo.put("templates", "arc42");

        upload(UUID.randomUUID(), uploadToken(), withATypo)
                .then()
                .statusCode(400)
                .body("code", equalTo("UNKNOWN_PARAMETER"));
    }

    /**
     * Which sites exist is configuration, so an upload naming a site nobody configured is a typo rather than the
     * first upload of a new site - and the answer names the sites there are, so the typo can be fixed.
     */
    @Test
    void uploadForASiteThisInstanceDoesNotConfigureNamesTheSitesThereAre() {
        Map<String, String> forAnUnknownSite = documentationSetParameters();
        forAnUnknownSite.put("site", "governance");

        upload(UUID.randomUUID(), uploadToken(), forAnUnknownSite)
                .then()
                .statusCode(400)
                .body("code", equalTo("UNKNOWN_SITE"))
                .body("detail", containsString(SITE))
                .body("detail", containsString(DocumentationSets.HANDBOOK_SITE));
    }

    /**
     * A set that would not be published is refused by the upload itself, and not only by the advisory endpoint
     * below - a pipeline may skip that endpoint, and a misfiled page must not be able to reach a site. The
     * answer carries the same findings the validation endpoint answers with, so a workflow prints them without
     * knowing which of the two refused the set.
     * <p>
     * <b>Nothing is stored</b>: the list of paths is read off the archive before the bundle is put anywhere.
     * The upload is left recorded as failed, which is a state the same upload id can be retried under once the
     * tree is fixed.
     */
    @Test
    void anUploadWhoseSetWouldNotBePublishedIsRefusedWithItsFindings() {
        UUID uploadId = UUID.randomUUID();
        String accessToken = uploadToken();

        upload(uploadId, accessToken, documentationSetParameters(), DocumentationSets.misfiledBundle())
                .then()
                .statusCode(422)
                .contentType("application/problem+json")
                .body("code", equalTo("STRUCTURE_INVALID"))
                .body("template", equalTo("arc42"))
                .body("pathsChecked", equalTo(DocumentationSets.misfiledPaths().size()))
                .body("findings.code", hasItem("UNKNOWN_CHAPTER"))
                .body("findings.find { it.code == 'UNKNOWN_CHAPTER' }.path",
                      equalTo(DocumentationSets.MISFILED_PAGE));

        given().baseUri(DOC_BASE_URL)
                .auth().oauth2(accessToken)
                .queryParam("system", SYSTEM)
                .when()
                .get(uploadPath(uploadId))
                .then()
                .statusCode(200)
                .body("state", equalTo("FAILED"));
    }

    /**
     * The size of the bundle has to be announced: it lets a bundle that is too large be rejected before it is
     * transferred, and it is what a body cut short is recognised by. Every client that uploads a file sends it -
     * {@code curl --data-binary @docs.zip} does, and so does rest-assured - so the request that does not is sent
     * with the JDK client, which streams a body of unknown length chunked.
     */
    @Test
    void uploadWithoutAnAnnouncedSizeIsRejected() throws Exception {
        String query = documentationSetParameters().entrySet().stream()
                .map(parameter -> parameter.getKey() + "=" + URLEncoder.encode(parameter.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest chunked = HttpRequest.newBuilder(URI.create(DOC_BASE_URL + uploadPath(UUID.randomUUID()) + "?" + query))
                .header("Authorization", "Bearer " + uploadToken())
                .header("Content-Type", "application/zip")
                .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(documentationSet())))
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(chunked, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(411);
            assertThat(response.body()).contains("LENGTH_REQUIRED");
        }
    }

    /**
     * What became of an upload, for a pipeline whose answer never arrived. It is the same role as the upload
     * itself: a pipeline reads the state of its own uploads.
     */
    @Test
    void theStateOfAnUploadIsReadableByThePipelineThatSentIt() {
        UUID uploadId = UUID.randomUUID();
        String accessToken = uploadToken();

        int id = upload(uploadId, accessToken, documentationSetParameters())
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        given().baseUri(DOC_BASE_URL)
                .auth().oauth2(accessToken)
                .queryParam("system", SYSTEM)
                .when()
                .get(uploadPath(uploadId))
                .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("state", equalTo("PENDING"))
                .body("component", equalTo("jme-doc-service"));
    }

    @Test
    void theStateOfAnUnknownUploadIsNotFound() {
        given().baseUri(DOC_BASE_URL)
                .auth().oauth2(uploadToken())
                .queryParam("system", SYSTEM)
                .when()
                .get(uploadPath(UUID.randomUUID()))
                .then()
                .statusCode(404);
    }

    @Test
    void theStateOfAnUploadOfAnotherSystemIsRejected() {
        String accessToken = fetchAccessToken(AUTH_BASE_URL, "other-system-doc-pipeline", "secret");

        given().baseUri(DOC_BASE_URL)
                .auth().oauth2(accessToken)
                .queryParam("system", SYSTEM)
                .when()
                .get(uploadPath(UUID.randomUUID()))
                .then()
                .statusCode(403);
    }

    /**
     * Where the bundle of an upload ends up: under the identifier the doc service gave the upload and the number
     * of the attempt that stored it, and tagged, so that the lifecycle rule of the bucket expires it. A build log
     * naming the id is therefore enough to find the bundle again.
     */
    @Test
    void theBundleLiesInTheObjectStorageUnderTheIdOfTheUpload() {
        UUID uploadId = UUID.randomUUID();
        String accessToken = uploadToken();

        Response stored = upload(uploadId, accessToken, documentationSetParameters());
        stored.then().statusCode(201);
        int id = stored.path("id");
        int sizeInBytes = stored.path("sizeInBytes");

        int attempt = given().baseUri(DOC_BASE_URL)
                .auth().oauth2(accessToken)
                .queryParam("system", SYSTEM)
                .when()
                .get(uploadPath(uploadId))
                .then()
                .statusCode(200)
                .extract()
                .path("attempt");

        String key = "uploads/docs/%d/%d/bundle.zip".formatted(id, attempt);
        try (S3Client objectStorage = objectStorage()) {
            HeadObjectResponse bundle = objectStorage.headObject(HeadObjectRequest.builder()
                    .bucket(documentationBucket).key(key).build());
            assertThat(bundle.contentLength()).isEqualTo(sizeInBytes);

            List<Tag> tags = objectStorage.getObjectTagging(GetObjectTaggingRequest.builder()
                    .bucket(documentationBucket).key(key).build()).tagSet();
            assertThat(tags).extracting(Tag::key, Tag::value).containsExactly(tuple(UPLOAD_TAG_KEY, UPLOAD_TAG_VALUE));
        }
    }

    /**
     * The verdict is the status line: {@code 200} publish, {@code 422} print the findings and stop. The body of
     * an accepted tree is not empty all the same - it carries the chapters the template allows, so a workflow
     * can print them once instead of the service repeating them in every message.
     */
    @Test
    void aTreeThatFollowsTheTemplateIsAccepted() {
        validate(uploadToken(), DocumentationSets.paths())
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("template", equalTo("arc42"))
                .body("pathsChecked", equalTo(DocumentationSets.paths().size()))
                .body("pathsIgnored", equalTo(0))
                .body("findings", empty())
                .body("findingsOmitted", equalTo(0))
                .body("allowedFolders", hasItem("6-runtime-view"))
                .body("allowedExtensions", hasItem("md"));
    }

    /**
     * A chapter arc42 does not have. The answer is the problem document of the upload API, carrying the report
     * as extension members, and a finding names the path it is about - which is the half a pipeline prints for
     * whoever wrote the file.
     */
    @Test
    void aTreeThatDoesNotFollowTheTemplateIsReportedBeforeItIsPacked() {
        validate(uploadToken(), List.of("1-intro/why-we-built-this.md",
                                        "4-runtime-view/how-an-upload-travels.md"))
                .then()
                .statusCode(422)
                .contentType("application/problem+json")
                .body("type", equalTo("https://jeap.admin.ch/problems/docs/structure-invalid"))
                .body("template", equalTo("arc42"))
                .body("pathsChecked", equalTo(2))
                .body("findings.code", hasItem("UNKNOWN_CHAPTER"))
                .body("findings.find { it.code == 'UNKNOWN_CHAPTER' }.path",
                      equalTo("4-runtime-view/how-an-upload-travels.md"));
    }

    /**
     * A name the site generator writes into that chapter itself. A leading number is not part of a name, so
     * {@code 01-index.md} is the chapter's generated landing page under another file name - two files at one
     * URL, reported before the ZIP exists rather than failing the build of a part hours later.
     */
    @Test
    void aNameTheGeneratorWritesIntoTheChapterIsReserved() {
        validate(uploadToken(), List.of("1-intro/01-index.md"))
                .then()
                .statusCode(422)
                .body("findings.code", hasItem("RESERVED_NAME"));
    }

    /**
     * The role is the upload one, checked for the system named in the request - so a pipeline of another system
     * is refused here exactly as it is on the upload itself.
     */
    @Test
    void validationForAnotherSystemIsRejected() {
        String accessToken = fetchAccessToken(AUTH_BASE_URL, "other-system-doc-pipeline", "secret");

        validate(accessToken, DocumentationSets.paths())
                .then()
                .statusCode(403);
    }

    /**
     * A structure does not depend on a commit hash, so this endpoint accepts fewer parameters than the upload
     * does - and a pipeline that passes its whole doc workflow configuration through is told so rather than
     * having the surplus ignored.
     */
    @Test
    void validationWithAParameterTheStructureDoesNotDependOnIsRejected() {
        given().baseUri(DOC_BASE_URL)
                .auth().oauth2(uploadToken())
                .contentType("application/json")
                .queryParams(documentationSetParameters())
                .body(Map.of("paths", DocumentationSets.paths()))
                .when()
                .post(VALIDATION_PATH)
                .then()
                .statusCode(400)
                .body("code", equalTo("UNKNOWN_PARAMETER"));
    }

    @Test
    void validationWithoutTokenIsRejected() {
        given().baseUri(DOC_BASE_URL)
                .contentType("application/json")
                .queryParams(DocumentationSets.structureParameters())
                .body(Map.of("paths", DocumentationSets.paths()))
                .when()
                .post(VALIDATION_PATH)
                .then()
                .statusCode(401);
    }

    /**
     * <b>An HTML set is a built site rather than chapters of pages</b>, so it follows no template's allowlist:
     * what is refused is what has no business in documentation and every business on a workstation. The
     * upload refuses it with the findings, having stored nothing - exactly as it refuses a misfiled Markdown
     * page.
     */
    @Test
    void aMicrositeCarryingAnExecutableIsRefused() {
        List<String> paths = new ArrayList<>(DocumentationSets.micrositePaths());
        paths.add("tools/install.exe");

        uploadMicrosite(uploadToken(), DocumentationSets.micrositeParameters("with-an-executable"), paths)
                .then()
                .statusCode(422)
                .body("code", equalTo("STRUCTURE_INVALID"))
                .body("findings.find { it.code == 'FORBIDDEN_EXTENSION' }.path", equalTo("tools/install.exe"));
    }

    /**
     * One name at the root of a microsite is the service's own: the text of its pages is extracted when it is
     * uploaded and stored beside its files under that name, so a set that brings the file would have it
     * silently overwritten.
     */
    @Test
    void aMicrositeCarryingTheServicesOwnSearchTextIsRefused() {
        List<String> paths = new ArrayList<>(DocumentationSets.micrositePaths());
        paths.add(DocumentationSets.MICROSITE_SEARCH_TEXT);

        uploadMicrosite(uploadToken(), DocumentationSets.micrositeParameters("with-a-search-text"), paths)
                .then()
                .statusCode(422)
                .body("findings.find { it.code == 'RESERVED_PATH' }.path",
                      equalTo(DocumentationSets.MICROSITE_SEARCH_TEXT));
    }

    /** A microsite is opened at its entry point, so a set without one would be a frame showing nothing. */
    @Test
    void aMicrositeWithoutAnEntryPointIsRefused() {
        uploadMicrosite(uploadToken(), DocumentationSets.micrositeParameters("without-an-entry-point"),
                        List.of(DocumentationSets.MICROSITE_NESTED_PAGE))
                .then()
                .statusCode(422)
                .body("findings.code", hasItem("MISSING_ENTRY_POINT"));
    }

    /** The location is a chapter of the template, and one arc42 does not have is nowhere to embed it. */
    @Test
    void aMicrositeEmbeddedInAChapterTheTemplateDoesNotHaveIsRefused() {
        Map<String, String> parameters = DocumentationSets.micrositeParameters("in-no-chapter");
        parameters.put("location", "13-appendix");

        uploadMicrosite(uploadToken(), parameters, DocumentationSets.micrositePaths())
                .then()
                .statusCode(422)
                .body("findings.code", hasItem("UNKNOWN_LOCATION"));
    }

    /** A microsite has no pages to take a title from, so the label is the only thing that can name it. */
    @Test
    void aMicrositeWithoutALabelNamesTheMissingParameter() {
        Map<String, String> parameters = DocumentationSets.micrositeParameters("without-a-label");
        parameters.remove("label");

        uploadMicrosite(uploadToken(), parameters, DocumentationSets.micrositePaths())
                .then()
                .statusCode(400)
                .body("code", equalTo("MISSING_PARAMETER"));
    }

    /**
     * The other way round: a Markdown set is written into the chapters its folders name, so a location passed
     * with one says the workflow configuration mixed up the two kinds of documentation.
     */
    @Test
    void aMarkdownUploadNamingALocationIsRejected() {
        Map<String, String> parameters = documentationSetParameters();
        parameters.put("location", DocumentationSets.MICROSITE_LOCATION);

        upload(UUID.randomUUID(), uploadToken(), parameters)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_PARAMETER_VALUE"));
    }

    /**
     * <b>A microsite is served straight from the bucket, file by file, as soon as it is accepted</b> - there
     * is no build between the upload and its files, only the page that frames it waits for one. What it is
     * served with is what contains it: a sandbox on every response, so a file opened directly is as isolated as
     * a framed one; the one script the service adds; the uploaded bytes otherwise untouched; and a file that is
     * not a page downloaded rather than rendered.
     * <p>
     * <b>{@code Access-Control-Allow-Origin} belongs to the microsites alone.</b> A framed microsite fetches
     * its own files from an opaque origin, which makes those cross-origin requests - and nothing else this
     * service answers may be one.
     */
    @Test
    void anAcceptedMicrositeIsServedAtOnceInsideItsSandbox() {
        String topic = "served-at-once";
        uploadMicrosite(uploadToken(), DocumentationSets.micrositeParameters(topic),
                        DocumentationSets.micrositePaths())
                .then()
                .statusCode(201);
        String microsite = DOC_BASE_URL + micrositePathOf(topic);

        // The prefix a request resolves to is cached for a few seconds, so the first answer is waited for.
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> given().when().get(microsite).getStatusCode() == 200);

        given().when()
                .get(microsite)
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .header("Content-Security-Policy", startsWith("sandbox allow-scripts"))
                .header("Content-Security-Policy", not(containsString("allow-same-origin")))
                .header("Access-Control-Allow-Origin", "*")
                .body(containsString(MICROSITE_SHIM_MARKER))
                .body(containsString(DocumentationSets.MICROSITE_HEADING));

        given().when()
                .get(microsite + "data/properties.json")
                .then()
                .statusCode(200)
                .header("Content-Disposition", "attachment")
                .body(not(containsString(MICROSITE_SHIM_MARKER)));

        given().when()
                .get(microsite + "pages/missing.html")
                .then()
                .statusCode(404)
                .header("Content-Security-Policy", startsWith("sandbox"))
                .body(containsString("has no such file"));

        given().baseUri(DOC_BASE_URL)
                .auth().oauth2(uploadToken())
                .queryParam("system", SYSTEM)
                .when()
                .get(uploadPath(UUID.randomUUID()))
                .then()
                .header("Access-Control-Allow-Origin", nullValue());
    }

    /**
     * Where a microsite ends up: one object per file under a {@code files/} prefix of its set, below the
     * current documentation - and tagged as that, because the current documentation is the only copy there is
     * and no lifecycle rule may expire it.
     */
    @Test
    void theFilesOfAMicrositeLieInTheObjectStorageOneObjectEach() {
        String topic = "one-object-per-file";
        uploadMicrosite(uploadToken(), DocumentationSets.micrositeParameters(topic),
                        DocumentationSets.micrositePaths())
                .then()
                .statusCode(201);

        // The set's own segment of the key: its format, its template, and where it is embedded.
        String setSegment = "/html/arc42/" + DocumentationSets.MICROSITE_LOCATION + "-" + topic + "/";
        try (S3Client objectStorage = objectStorage()) {
            List<String> keys = objectStorage.listObjectsV2Paginator(ListObjectsV2Request.builder()
                            .bucket(documentationBucket).prefix(CURRENT_PREFIX).build())
                    .contents().stream()
                    .map(S3Object::key)
                    .filter(key -> key.contains(setSegment) && key.contains("/files/"))
                    .toList();

            assertThat(keys).allMatch(key -> key.startsWith(CURRENT_PREFIX));
            for (String path : DocumentationSets.micrositePaths()) {
                assertThat(keys).anyMatch(key -> key.endsWith("/files/" + path));
            }

            String entryPoint = keys.stream().filter(key -> key.endsWith("/files/index.html")).findFirst()
                    .orElseThrow();
            List<Tag> tags = objectStorage.getObjectTagging(GetObjectTaggingRequest.builder()
                    .bucket(documentationBucket).key(entryPoint).build()).tagSet();
            assertThat(tags).extracting(Tag::key, Tag::value)
                    .containsExactly(tuple(UPLOAD_TAG_KEY, CURRENT_TAG_VALUE));
        }
    }

    /** Where a microsite of the component is served, below the root of the site. */
    private static String micrositePathOf(String topic) {
        return "/microsites/" + SYSTEM + "/components/" + DocumentationSets.COMPONENT + "/arc42/"
               + DocumentationSets.MICROSITE_LOCATION + "/" + topic + "/";
    }

    private static Response uploadMicrosite(String accessToken, Map<String, String> parameters,
                                            List<String> paths) {
        return upload(UUID.randomUUID(), accessToken, parameters, DocumentationSets.micrositeBundleOf(paths));
    }

    private static Response validate(String accessToken, List<String> paths) {
        return given().baseUri(DOC_BASE_URL)
                .auth().oauth2(accessToken)
                .contentType("application/json")
                .queryParams(DocumentationSets.structureParameters())
                .body(Map.of("paths", paths))
                .when()
                .post(VALIDATION_PATH);
    }

    /**
     * A client for the object storage of the example. Path style addressing, because the bucket of a local
     * S3-compatible storage is a path and not a subdomain.
     */
    private S3Client objectStorage() {
        return S3Client.builder()
                .endpointOverride(objectStorageEndpoint)
                .region(Region.of(objectStorageRegion))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(objectStorageAccessKey, objectStorageSecretKey)))
                .forcePathStyle(true)
                .build();
    }

    private String uploadToken() {
        return fetchAccessToken(AUTH_BASE_URL, "jme-doc-pipeline", "secret");
    }

    private static Response upload(UUID uploadId, String accessToken, Map<String, String> parameters) {
        return upload(uploadId, accessToken, parameters, documentationSet());
    }

    private static Response upload(UUID uploadId, String accessToken, Map<String, String> parameters,
                                   byte[] bundle) {
        return given().baseUri(DOC_BASE_URL)
                .auth().oauth2(accessToken)
                .contentType("application/zip")
                .queryParams(parameters)
                .body(bundle)
                .when()
                .put(uploadPath(uploadId));
    }

    private static String uploadPath(UUID uploadId) {
        return "/api/uploads/docs/" + uploadId;
    }

    private static Map<String, String> documentationSetParameters() {
        return DocumentationSets.parameters();
    }

    private static byte[] documentationSet() {
        return DocumentationSets.bundle();
    }
}
