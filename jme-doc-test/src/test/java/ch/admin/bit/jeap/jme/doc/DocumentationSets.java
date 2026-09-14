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
     * names no {@code site}, so the documentation belongs to the default site.
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

    // --- The system's own documentation ---------------------------------------------------------------------

    /** The chapter the system's own set is filed in, and the page in it that carries raw HTML. */
    static final String SYSTEM_CHAPTER = "2-constraints";
    static final String RAW_HTML_PAGE_NAME = "raw-html";

    /**
     * What the raw HTML page carries: a script that would set a flag, a style that would draw a frame around
     * the whole page, and a picture in SVG - the one image format a browser opens as a document. The script
     * and the style are shown as text and never applied; the SVG is served sandboxed.
     */
    static final String RAW_SCRIPT = "<script>window.UPLOADED_SCRIPT_RAN = true</script>";
    static final String RAW_STYLE = "<style>body { outline: 7px solid red }</style>";
    static final String SVG_NAME = "boundary.svg";
    static final String SVG_ALT = "The boundary of the system";

    /**
     * A word on the uploaded Markdown page and inside the uploaded microsite, and on no generated page - so a
     * search for it finds exactly the two kinds of uploaded documentation, and narrowing by source has an
     * effect.
     */
    static final String ON_BOTH_UPLOADED_KINDS = "quartermaster";

    /** The parameters of the system's own set: a system documents itself, so it names no subject and no version. */
    static Map<String, String> systemParameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("type", "system-docs");
        parameters.put("system", SYSTEM);
        parameters.put("template", "arc42");
        parameters.put("source-format", "markdown");
        parameters.putAll(provenance());
        return parameters;
    }

    /** The system's own set: one page with raw HTML in it, and the SVG it shows. */
    static byte[] systemBundle() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            write(zip, SYSTEM_CHAPTER + "/" + RAW_HTML_PAGE_NAME + ".md", """
                    # Raw HTML as it was uploaded

                    The %s keeps the constraints of this system.

                    %s

                    %s

                    ![%s](./%s)
                    """.formatted(ON_BOTH_UPLOADED_KINDS, RAW_SCRIPT, RAW_STYLE, SVG_ALT, SVG_NAME));
            write(zip, SYSTEM_CHAPTER + "/" + SVG_NAME, svg());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * An SVG with a script in it, which runs when the file is opened as a document - the reason the file is
     * served sandboxed. <b>Over 10 KB on purpose</b>, for the same reason as {@link #image()}: a smaller one is
     * inlined into the page and never served as a file of its own.
     */
    private static String svg() {
        StringBuilder svg = new StringBuilder(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"480\" height=\"480\">\n"
                + "<script>window.UPLOADED_SVG_SCRIPT_RAN = true</script>\n");
        Random noise = new Random(7); // NOSONAR a fixed seed on purpose: the same picture on every run
        for (int i = 0; i < 400; i++) {
            svg.append("<rect x=\"%d\" y=\"%d\" width=\"12\" height=\"12\" fill=\"#%06x\"/>\n"
                    .formatted(noise.nextInt(468), noise.nextInt(468), noise.nextInt(0x1000000)));
        }
        return svg.append("</svg>\n").toString();
    }

    // --- A library ------------------------------------------------------------------------------------------

    /**
     * A library of the system. No architecture model holds a library, so everything published about it comes
     * out of this upload - its tree beside the components, and the chapter written here.
     */
    static final String LIBRARY = "jme-doc-fixtures";
    static final String LIBRARY_CHAPTER = "12-glossary";
    static final String LIBRARY_PAGE_NAME = "terms";
    static final String LIBRARY_PAGE_TEXT = "A documentation set is what one upload carries.";

    static Map<String, String> libraryParameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("type", "library-docs");
        parameters.put("system", SYSTEM);
        parameters.put("library", LIBRARY);
        parameters.put("template", "arc42");
        parameters.put("source-format", "markdown");
        parameters.put("version", "2.0.0");
        parameters.putAll(provenance());
        return parameters;
    }

    static byte[] libraryBundle() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            write(zip, LIBRARY_CHAPTER + "/" + LIBRARY_PAGE_NAME + ".md",
                    "# The terms of the fixtures\n\n" + LIBRARY_PAGE_TEXT + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    // --- An HTML microsite ----------------------------------------------------------------------------------

    /** Where the microsite is embedded - a chapter of the component - and what names it in the navigation. */
    static final String MICROSITE_LOCATION = "8-crosscutting-concepts";
    static final String MICROSITE_TOPIC = "configuration-reference";
    static final String MICROSITE_LABEL = "Configuration Reference";

    static final String MICROSITE_HEADING = "Every property of this service";

    /** A page below the entry point, with a stylesheet of its own: what a deep link and a search hit open. */
    static final String MICROSITE_NESTED_PAGE = "pages/properties.html";
    static final String MICROSITE_NESTED_HEADING = "Every property, one by one";
    static final String MICROSITE_NESTED_COLOUR = "rgb(0, 100, 0)";

    /** A word only inside the microsite, so finding it says its content was indexed when it was uploaded. */
    static final String ONLY_INSIDE_THE_MICROSITE = "idempotency";

    /** What the entry point tells the page it frames it in about its height - within the four screens allowed. */
    static final int MICROSITE_REPORTED_HEIGHT = 1500;

    /** The service's own name for the file a microsite's search text is stored in, which a set may not carry. */
    static final String MICROSITE_SEARCH_TEXT = "_jeap-search.tsv";

    /** The parameters of a microsite of the component, under a topic of the caller's choosing. */
    static Map<String, String> micrositeParameters(String topic) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("type", "component-docs");
        parameters.put("system", SYSTEM);
        parameters.put("component", COMPONENT);
        parameters.put("template", "arc42");
        parameters.put("source-format", "html");
        parameters.put("location", MICROSITE_LOCATION);
        parameters.put("topic", topic);
        parameters.put("label", MICROSITE_LABEL);
        parameters.put("version", "1.0.0");
        parameters.putAll(provenance());
        return parameters;
    }

    /** What the structure of a microsite depends on - the validation endpoint refuses a label and a version. */
    static Map<String, String> micrositeStructureParameters(String topic) {
        Map<String, String> parameters = micrositeParameters(topic);
        parameters.keySet().removeAll(List.of("label", "version"));
        parameters.keySet().removeAll(provenance().keySet());
        return parameters;
    }

    /** The files of the microsite, as a build that emits HTML leaves them. */
    static List<String> micrositePaths() {
        return List.of("index.html", MICROSITE_NESTED_PAGE, "pages/style.css", "data/properties.json");
    }

    /**
     * The microsite: an entry point that does what an application does while it loads and writes down what the
     * browser let it do, a nested page with its own stylesheet, and a JSON file it would fetch.
     */
    static byte[] micrositeBundle() {
        return micrositeBundleOf(micrositePaths());
    }

    /** A microsite carrying the given paths - the known ones with their content, anything else with a line. */
    static byte[] micrositeBundleOf(List<String> paths) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String path : paths) {
                write(zip, path, switch (path) {
                    case "index.html" -> micrositeEntryPoint();
                    case MICROSITE_NESTED_PAGE -> micrositeNestedPage();
                    case "pages/style.css" -> "h1 { color: " + MICROSITE_NESTED_COLOUR + " }\n";
                    case "data/properties.json" -> "{\"properties\": 12}\n";
                    default -> "not a file of a documentation site\n";
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * The entry point. Its script reads storage - which a sandboxed document may not do without the shim the
     * service injects - records its origin and whether it could reach the page around it, and reports a height
     * to that page, which is the one message a page framing a microsite listens for.
     */
    private static String micrositeEntryPoint() {
        return """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><title>Configuration</title></head>
                <body>
                <h1>%s</h1>
                <p>The %s of this service reads every property once.</p>
                <script>
                  window.STORAGE_WORKED = false;
                  try {
                    localStorage.setItem('probe', 'yes');
                    window.STORAGE_WORKED = localStorage.getItem('probe') === 'yes';
                  } catch (e) {
                    window.STORAGE_WORKED = false;
                  }
                  window.ORIGIN = String(window.origin);
                  try {
                    window.REACHED_PARENT = Boolean(parent.location.href);
                  } catch (e) {
                    window.REACHED_PARENT = false;
                  }
                  parent.postMessage({type: 'jeap-doc-microsite-height', height: %d}, '*');
                </script>
                </body></html>
                """.formatted(MICROSITE_HEADING, ON_BOTH_UPLOADED_KINDS, MICROSITE_REPORTED_HEIGHT);
    }

    private static String micrositeNestedPage() {
        return """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><title>Properties</title>
                <link rel="stylesheet" href="style.css"></head>
                <body><h1>%s</h1>
                <p>Every request carries an %s key, and a repeated one is answered from the log.</p>
                </body></html>
                """.formatted(MICROSITE_NESTED_HEADING, ONLY_INSIDE_THE_MICROSITE);
    }

    // --- The second site -------------------------------------------------------------------------------------

    /** The second site this example configures: one that needs no architecture model. */
    static final String HANDBOOK_SITE = "handbook";

    /** The page the system uploads to the handbook: its chapter folder and its name without the extension. */
    static final String HANDBOOK_CHAPTER = "8-crosscutting-concepts";
    static final String HANDBOOK_PAGE_NAME = "how-we-release";
    static final String HANDBOOK_PAGE_TEXT = "A release of JME is ratified by two maintainers before it is tagged.";

    /** A word on the handbook's page and on no page of the default site, so each site's search can be asked. */
    static final String ON_THE_HANDBOOK_ONLY = "ratified";

    /** The system's own set once more, for the handbook: the same parameters, naming the site. */
    static Map<String, String> handbookParameters() {
        Map<String, String> parameters = systemParameters();
        parameters.put("site", HANDBOOK_SITE);
        return parameters;
    }

    static byte[] handbookBundle() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            write(zip, HANDBOOK_CHAPTER + "/" + HANDBOOK_PAGE_NAME + ".md", """
                    ---
                    title: How we release
                    description: What happens between a merged change and a tagged release.
                    ---

                    %s
                    """.formatted(HANDBOOK_PAGE_TEXT));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** Where the provenance of every set of this fixture points. */
    private static Map<String, String> provenance() {
        Map<String, String> provenance = new LinkedHashMap<>();
        provenance.put("source-repository", "ssh://git@bitbucket.example.ch/bit_jme/jme-doc-service-example.git");
        provenance.put("source-revision", SOURCE_REVISION);
        provenance.put("source-ref", "main");
        provenance.put("source-timestamp", "2026-08-21T09:12:00+02:00");
        return provenance;
    }

    private static void write(ZipOutputStream zip, String path, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
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
