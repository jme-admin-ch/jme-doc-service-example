package ch.admin.bit.jeap.jme.doc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The documentation set a doc pipeline of the system {@code jme} uploads for one of its components.
 * <p>
 * Both integration tests upload it - the one that checks what becomes of an upload, and the one that checks what
 * an upload sets in motion - so it is written down once. A retry has to send the parameters unchanged, and they
 * are named like the keys of the doc workflow configuration of a repository, so this is the one place where the
 * doc workflow configuration of the system {@code jme} is spelled out.
 */
final class DocumentationSets {

    /** The system this example issues its semantic roles for, and the one the pipeline may upload for. */
    static final String SYSTEM = "jme";

    private DocumentationSets() {
    }

    /**
     * The parameters of the upload. It names no {@code site}, so the documentation belongs to the default site -
     * which is the only site this example configures.
     */
    static Map<String, String> parameters() {
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

    /** The bundle itself: one chapter of an arc42 documentation, zipped. */
    static byte[] bundle() {
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
