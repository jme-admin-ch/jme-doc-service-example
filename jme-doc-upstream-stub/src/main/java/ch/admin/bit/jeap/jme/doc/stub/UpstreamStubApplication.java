package ch.admin.bit.jeap.jme.doc.stub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The upstream services the doc service reads, stubbed, so that the example can generate documentation on a
 * developer machine and in its build.
 * <p>
 * <b>Why this exists.</b> The doc service imports an architecture model and generates a site out of it. Without
 * an upstream there is no model, so the site of this example is its shell and nothing else - which shows how an
 * instance is configured and none of what it produces. Running a real architecture repository beside it would
 * mean its database, its importers and its source data for the sake of two systems.
 * <p>
 * <b>Two upstreams, one service.</b> The doc service reads an architecture repository for what is deployed and
 * a reaction observer for what was seen happening, and both are stubbed here - out of one landscape, so that a
 * runtime view cannot contradict the building block view beside it. Each of them keeps its own path root, its
 * own role and its own client registration of the doc service, which is the half of an upstream integration
 * that actually breaks on a real stage.
 * <p>
 * <b>What it is not.</b> It is not a test double for the doc service's own tests, which stub HTTP where they
 * need to, and it is not a second implementation of the architecture repository: it serves one fixed landscape
 * and answers nothing that is not read by an import. The deployed instance of this example
 * (<i>jme-nivel-doc-service-example</i>) reads the real architecture repository of its stage and does not use
 * this at all.
 * <p>
 * <b>Why it is a service of the example rather than configuration.</b> This repository is otherwise
 * configuration only, deliberately - an instance supplies its application class and its YAML and nothing else.
 * This module is the exception, and it is here rather than in the doc service because there is exactly one
 * consumer of it.
 * <p>
 * The landscape it serves is in {@code landscape.yml}, and adding a system to it is editing that file.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class UpstreamStubApplication {

    public static void main(String[] args) {
        SpringApplication.run(UpstreamStubApplication.class, args);
    }
}
