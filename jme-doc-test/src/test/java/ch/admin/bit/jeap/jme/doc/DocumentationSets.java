package ch.admin.bit.jeap.jme.doc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The documentation set a doc pipeline of the system {@code jme} uploads for one of its components.
 * <p>
 * Both integration tests use it - the one that checks what becomes of an upload, and the one that checks what
 * the generator publishes - so it is written down once. A retry has to send the parameters unchanged, and they
 * are named like the keys of the doc workflow configuration of a repository, so this is the one place where the
 * doc workflow configuration of the system {@code jme} is spelled out.
 */
final class DocumentationSets {

    /** The system this example issues its semantic roles for, and the one the pipeline may upload for. */
    static final String SYSTEM = "jme";

    /** The component the set documents. The stubbed landscape holds it, so it has generated pages too. */
    static final String COMPONENT = "jme-doc-service";

    /**
     * A second component of the same system that the stubbed landscape does <b>not</b> hold - no importer has
     * ever seen it - so everything published about it is what was uploaded for it.
     */
    static final String COMPONENT_OUTSIDE_THE_MODEL = "jme-doc-upstream-stub";

    /** The one page of the set: the chapter folder it is filed under, and its name without the extension. */
    static final String CHAPTER = "1-intro";
    static final String PAGE_NAME = "why-we-built-this";

    /** What the page says. The heading becomes its title, and the sentence is in no other page of the site. */
    static final String PAGE_TITLE = "Why we built this";
    static final String PAGE_TEXT =
            "Because the documentation of a component belongs next to the code of that component.";

    /**
     * A picture beside the page, which the page shows. It is filed in the same chapter folder, because that is
     * where the site generator writes it: next to the page that links to it.
     */
    static final String IMAGE_NAME = "what-an-upload-carries.png";

    /** What the page calls the picture, which is how the published page is searched for it. */
    static final String IMAGE_ALT = "A picture uploaded beside the page";

    /**
     * The picture: noise, so that it does not compress, and large enough to stay a file.
     * <p>
     * <b>Over 10 KB on purpose.</b> Docusaurus inlines a smaller image into the page as a data URL, and an
     * inlined picture is never published as a file of its own - which is the way a screenshot goes, and the
     * way this is here to cover. The seed is fixed, so it is the same picture on every run and the bytes the
     * site serves can be compared with the ones uploaded.
     */
    private static final byte[] IMAGE = picture();

    /** The commit the upload names, which the provenance under the published page shows. */
    static final String SOURCE_REVISION = "9a1c2f8";

    /** A chapter arc42 does not have, which is what makes {@link #misfiledBundle()} unpublishable. */
    static final String MISFILED_PAGE = "4-runtime-view/how-an-upload-travels.md";

    private DocumentationSets() {
    }

    /**
     * What the <i>structure</i> of a documentation set depends on, and nothing else. The validation endpoint
     * accepts these and refuses the rest: a path tree does not depend on a commit hash, and it does not depend
     * on the site that will publish it either.
     */
    static Map<String, String> structureParameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("type", "component-docs");
        parameters.put("system", SYSTEM);
        parameters.put("component", COMPONENT);
        parameters.put("template", "arc42");
        parameters.put("source-format", "markdown");
        return parameters;
    }

    /**
     * The parameters of the upload: what the structure depends on, plus where the documentation came from. It
     * names no {@code site}, so the documentation belongs to the default site - which is the only site this
     * example configures.
     */
    static Map<String, String> parameters() {
        Map<String, String> parameters = new LinkedHashMap<>(structureParameters());
        parameters.put("version", "1.0.0");
        parameters.put("source-repository", "ssh://git@bitbucket.example.ch/bit_jme/jme-doc-service-example.git");
        parameters.put("source-revision", SOURCE_REVISION);
        parameters.put("source-ref", "main");
        parameters.put("source-timestamp", "2026-08-21T09:12:00+02:00");
        return parameters;
    }

    /** The same set, uploaded for another component of the same system. */
    static Map<String, String> parametersFor(String component) {
        Map<String, String> parameters = parameters();
        parameters.put("component", component);
        return parameters;
    }

    /**
     * The files of the set, as a doc workflow lists them for the validation endpoint: relative to the root of
     * the documentation folder, written with {@code /}, directories not listed.
     */
    static List<String> paths() {
        return List.of(CHAPTER + "/" + PAGE_NAME + ".md", CHAPTER + "/" + IMAGE_NAME);
    }

    /**
     * A tree that would not be published: the page above plus one in a chapter arc42 does not have. The
     * validation endpoint reports it, and so does the upload endpoint - a pipeline may skip the first.
     */
    static List<String> misfiledPaths() {
        List<String> paths = new ArrayList<>(paths());
        paths.add(MISFILED_PAGE);
        return List.copyOf(paths);
    }

    /** The bundle itself: {@link #paths()}, zipped. */
    static byte[] bundle() {
        return bundleOf(paths());
    }

    /** The bundle of a set the doc service refuses: {@link #misfiledPaths()}, zipped. */
    static byte[] misfiledBundle() {
        return bundleOf(misfiledPaths());
    }

    private static byte[] bundleOf(List<String> paths) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String path : paths) {
                zip.putNextEntry(new ZipEntry(path));
                zip.write(path.endsWith(".md") ? page().getBytes(StandardCharsets.UTF_8) : image());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * The Markdown of the page. It carries no front matter of its own: what a reader sees under the page -
     * the repository, the branch, the commit and the version - is written by the doc service out of the
     * upload's parameters, and a page may not claim any of it itself.
     */
    private static String page() {
        return "# " + PAGE_TITLE + "\n\n" + PAGE_TEXT + "\n\n![" + IMAGE_ALT + "](./" + IMAGE_NAME + ")\n";
    }

    /** The bytes of {@link #IMAGE_NAME}, as uploaded. */
    static byte[] image() {
        return IMAGE.clone();
    }

    private static byte[] picture() {
        BufferedImage picture = new BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB);
        Random noise = new Random(42); // NOSONAR a fixed seed on purpose: the same picture on every run
        for (int x = 0; x < picture.getWidth(); x++) {
            for (int y = 0; y < picture.getHeight(); y++) {
                picture.setRGB(x, y, noise.nextInt(0x1000000));
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(picture, "png", bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
