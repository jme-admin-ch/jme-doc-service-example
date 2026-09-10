package ch.admin.bit.jeap.jme.doc.stub.archrepo;

import ch.admin.bit.jeap.jme.doc.stub.fixture.Landscape;
import ch.admin.bit.jeap.jme.doc.stub.http.ConditionalAnswers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * The {@code /docs-api} of the architecture repository, as far as an import of it reads.
 * <p>
 * <b>Six resources and no more.</b> The doc service fetches the system list, each system's topology and each
 * system's messages, and asks the three replication indexes what has moved. Everything else the real
 * {@code /docs-api} serves is answered by nobody here, on purpose: a stub that grows towards its original stops
 * being readable and starts being a second implementation to keep in step.
 * <p>
 * <b>The role is the real one.</b> {@code hasRole('architecture-model', 'read')} is what the architecture
 * repository puts on every one of these resources, so a token that would be refused there is refused here.
 * <p>
 * The three indexes answer empty. Replicating an OpenAPI specification means serving its content at the URL
 * the index names, carrying the very tag the index announced for it - which is what
 * {@link ConditionalAnswers} is built for, and what no test needs yet.
 */
@RestController
@PreAuthorize("hasRole('architecture-model', 'read')")
class ArchRepoStubController {

    private final Landscape landscape;
    private final ConditionalAnswers answers;

    ArchRepoStubController(Landscape landscape, ConditionalAnswers answers) {
        this.landscape = landscape;
        this.answers = answers;
    }

    @GetMapping(path = "/docs-api/systems", produces = "application/json")
    ResponseEntity<SystemListDto> systems(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        List<SystemSummaryDto> summaries = landscape.systems().stream()
                .map(system -> new SystemSummaryDto(system.name(), system.description(), system.aliases(),
                        system.team()))
                .toList();
        return answers.answer(new SystemListDto(summaries), ifNoneMatch);
    }

    @GetMapping(path = "/docs-api/systems/{system}", produces = "application/json")
    ResponseEntity<SystemDetailDto> system(
            @PathVariable String system,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        Landscape.DocumentedSystem documented = require(system);
        List<ComponentDto> components = documented.components().stream()
                .map(ArchRepoStubController::componentOf)
                .toList();
        return answers.answer(new SystemDetailDto(documented.name(), documented.description(),
                documented.aliases(), documented.team(), components, documented.relations()), ifNoneMatch);
    }

    @GetMapping(path = "/docs-api/systems/{system}/messages", produces = "application/json")
    ResponseEntity<MessageListDto> messages(
            @PathVariable String system,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        return answers.answer(new MessageListDto(require(system).messages()), ifNoneMatch);
    }

    @GetMapping(path = "/docs-api/openapi-specs", produces = "application/json")
    ResponseEntity<ArtifactIndexDto> openApiSpecs(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        return answers.answer(new ArtifactIndexDto(List.of()), ifNoneMatch);
    }

    @GetMapping(path = "/docs-api/database-schemas", produces = "application/json")
    ResponseEntity<ArtifactIndexDto> databaseSchemas(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        return answers.answer(new ArtifactIndexDto(List.of()), ifNoneMatch);
    }

    @GetMapping(path = "/docs-api/message-types", produces = "application/json")
    ResponseEntity<MessageTypeIndexDto> messageTypes(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        return answers.answer(new MessageTypeIndexDto(List.of()), ifNoneMatch);
    }

    /**
     * A system the landscape does not hold is a {@code 404}, which the doc service reads as <i>that system is
     * gone</i> rather than as a failure - so getting this wrong would quietly shrink the landscape instead of
     * failing the import.
     */
    private Landscape.DocumentedSystem require(String system) {
        return landscape.find(system).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "The stubbed landscape has no system %s.".formatted(system)));
    }

    /**
     * Today, as the day an importer last saw the component. See {@link Landscape.DocumentedComponent} for why
     * it is not in the fixture; the doc service hashes it by the day, so answering the current date moves the
     * landscape's hash once a day and not once a request.
     */
    private static ComponentDto componentOf(Landscape.DocumentedComponent component) {
        return new ComponentDto(component.name(), component.description(), component.type(), null,
                component.importer(), LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()),
                component.restApis());
    }

    record SystemListDto(List<SystemSummaryDto> systems) {
    }

    record SystemSummaryDto(String name, String description, List<String> aliases, Landscape.Team team) {
    }

    record SystemDetailDto(String name, String description, List<String> aliases, Landscape.Team team,
                           List<ComponentDto> components, List<Landscape.Relation> relations) {
    }

    /**
     * No {@code openApi} and no {@code databaseSchema}: what those name is a specification to replicate, and
     * the indexes below answer none. A consumer reads a field that is not there as absent, which is what they
     * are.
     */
    record ComponentDto(String name, String description, String type, Landscape.Team team, String importer,
                        ZonedDateTime lastSeen, List<Landscape.RestApi> restApis) {
    }

    record MessageListDto(List<Landscape.Message> messages) {
    }

    /** Always empty - see the class comment. The element type is what an entry would be, and there is none. */
    record ArtifactIndexDto(List<Object> artifacts) {
    }

    /** Always empty, for the same reason. */
    record MessageTypeIndexDto(List<Object> messageTypes) {
    }
}
