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
import software.amazon.awssdk.services.s3.model.Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Uploads documentation to the doc service of this example, with a token of the OAuth mock server.
 * <p>
 * The test starts both services and shows the two rules a doc pipeline has to know: a pipeline may publish the
 * documentation of its own system and of no other system, and the upload id it chooses is the idempotency key of
 * the upload - repeating a request under it never publishes a second documentation set.
 */
class DocServiceExampleIT extends BootServiceSpringIntegrationTestBase {

    private static final List<Integer> SERVICE_PORTS = reserveFreePorts(2);
    private static final int AUTH_PORT = SERVICE_PORTS.getFirst();
    private static final int DOC_PORT = SERVICE_PORTS.get(1);
    private static final String AUTH_BASE_URL = "http://localhost:" + AUTH_PORT + "/jme-doc-auth-scs";
    private static final String DOC_BASE_URL = "http://localhost:" + DOC_PORT + "/jme-doc-service";

    private static final String SYSTEM = "jme";

    /**
     * The tag every uploaded bundle carries. The lifecycle rule of the bucket selects on it rather than on the
     * prefix, because jeap.doc.storage.upload-prefix is configured per instance while the tag is the same
     * everywhere - see docker/docker-compose.yml.
     */
    private static final String UPLOAD_TAG_KEY = "jeap-doc-content";
    private static final String UPLOAD_TAG_VALUE = "upload";

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
                AUTH_BASE_URL + "/.well-known/jwks.json"));
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
        return given().baseUri(DOC_BASE_URL)
                .auth().oauth2(accessToken)
                .contentType("application/zip")
                .queryParams(parameters)
                .body(documentationSet())
                .when()
                .put(uploadPath(uploadId));
    }

    private static String uploadPath(UUID uploadId) {
        return "/api/uploads/docs/" + uploadId;
    }

    /**
     * The documentation set a doc pipeline of the system 'jme' uploads for one of its components - the
     * parameters are named like the keys of the doc workflow configuration of the repository. A retry has to
     * send them unchanged, so the map is the one place they are written down.
     */
    private static Map<String, String> documentationSetParameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("type", "component-docs");
        parameters.put("system", SYSTEM);
        parameters.put("component", "jme-doc-service");
        parameters.put("template", "arc42");
        parameters.put("source-format", "markdown");
        parameters.put("version", "1.0.0");
        parameters.put("source-repository", "ssh://git@bitbucket.example.ch/bit_jme/jme-doc-service-example.git");
        parameters.put("source-revision", "9a1c2f8");
        parameters.put("source-ref", "main");
        parameters.put("source-timestamp", "2026-08-21T09:12:00+02:00");
        return parameters;
    }

    private static byte[] documentationSet() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("1-intro/why-we-built-this.md"));
            zip.write("# Why we built this".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
