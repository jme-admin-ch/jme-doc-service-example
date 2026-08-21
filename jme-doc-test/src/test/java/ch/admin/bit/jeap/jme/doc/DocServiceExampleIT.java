package ch.admin.bit.jeap.jme.doc;

import ch.admin.bit.jeap.jme.test.BootServiceSpringIntegrationTestBase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Uploads documentation to the doc service of this example, with a token of the OAuth mock server.
 * <p>
 * The test starts both services and shows the rule the doc service enforces: a pipeline may publish the
 * documentation of its own system, and of no other system.
 */
class DocServiceExampleIT extends BootServiceSpringIntegrationTestBase {

    private static final List<Integer> SERVICE_PORTS = reserveFreePorts(2);
    private static final int AUTH_PORT = SERVICE_PORTS.getFirst();
    private static final int DOC_PORT = SERVICE_PORTS.get(1);
    private static final String AUTH_BASE_URL = "http://localhost:" + AUTH_PORT + "/jme-doc-auth-scs";
    private static final String DOC_BASE_URL = "http://localhost:" + DOC_PORT + "/jme-doc-service";

    private static final String SYSTEM = "jme";

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
    void uploadWithTheWriteRoleOfTheOwnSystemIsAccepted() {
        String accessToken = fetchAccessToken(AUTH_BASE_URL, "jme-doc-pipeline", "secret");

        uploadRequest(accessToken)
                .then()
                .statusCode(200);
    }

    @Test
    void uploadForAnotherSystemIsRejected() {
        String accessToken = fetchAccessToken(AUTH_BASE_URL, "other-system-doc-pipeline", "secret");

        uploadRequest(accessToken)
                .then()
                .statusCode(403);
    }

    @Test
    void uploadWithoutTokenIsRejected() {
        given().baseUri(DOC_BASE_URL)
                .contentType("application/zip")
                .body(documentationSet())
                .when()
                .put(uploadPath())
                .then()
                .statusCode(401);
    }

    @Test
    void uploadWithoutTheVersionOfTheComponentNamesTheMissingParameter() {
        String accessToken = fetchAccessToken(AUTH_BASE_URL, "jme-doc-pipeline", "secret");

        given().baseUri(DOC_BASE_URL)
                .auth().oauth2(accessToken)
                .contentType("application/zip")
                .queryParams(documentationSetParameters())
                .queryParam("version", "")
                .body(documentationSet())
                .when()
                .put(uploadPath())
                .then()
                .statusCode(400)
                .body("code", equalTo("MISSING_PARAMETER"));
    }

    private static io.restassured.response.Response uploadRequest(String accessToken) {
        return given().baseUri(DOC_BASE_URL)
                .auth().oauth2(accessToken)
                .contentType("application/zip")
                .queryParams(documentationSetParameters())
                .queryParam("version", "1.0.0")
                .body(documentationSet())
                .when()
                .put(uploadPath());
    }

    private static String uploadPath() {
        return "/api/uploads/" + UUID.randomUUID();
    }

    /**
     * The documentation set a doc pipeline of the system 'jme' uploads for one of its components - the
     * parameters are named like the keys of the doc workflow configuration of the repository.
     */
    private static Map<String, String> documentationSetParameters() {
        return Map.of(
                "type", "component-docs",
                "system", SYSTEM,
                "component", "jme-doc-service",
                "template", "arc42",
                "source-format", "markdown",
                "source-repository", "ssh://git@bitbucket.example.ch/bit_jme/jme-doc-service-example.git",
                "source-revision", "9a1c2f8",
                "source-ref", "main",
                "source-timestamp", "2026-08-21T09:12:00+02:00");
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
