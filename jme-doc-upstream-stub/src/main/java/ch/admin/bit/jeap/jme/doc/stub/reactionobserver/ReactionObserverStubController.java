package ch.admin.bit.jeap.jme.doc.stub.reactionobserver;

import ch.admin.bit.jeap.jme.doc.stub.http.ConditionalAnswers;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The graph API of the reaction observer, as far as an import of it reads.
 * <p>
 * <b>Six resources.</b> Three indexes saying which graphs exist and what each of them hashes to, and three
 * kinds of graph. The doc service reads the indexes on every round and fetches only the graphs whose entity
 * tag moved - without them it would be one request per system, per component and per message type of the
 * landscape for a graph the observer rebuilds once a day.
 * <p>
 * <b>The role is the real one.</b> {@code hasRole('reactions', 'read')} is what the reaction observer requires,
 * and it carries no tenant part: what reacted to what is nobody's system in particular. So the example
 * exercises a second client registration of the doc service and not only a second URL.
 * <p>
 * <b>An index lists what exists, and nothing else answers.</b> The observer indexes only graphs that are not
 * empty, and a system, component or message type with no reactions in its window is a {@code 404} - which the
 * doc service reads as <i>no runtime view for this one</i> rather than as a failure.
 * <p>
 * The observer offers HTTP Basic as well as a bearer token. That half is not stubbed, because the doc service
 * does not use it.
 */
@RestController
@PreAuthorize("hasRole('reactions', 'read')")
class ReactionObserverStubController {

    private static final String SYSTEM_GRAPHS = "/api/graphs/systems";
    private static final String COMPONENT_GRAPHS = "/api/graphs/components";
    private static final String MESSAGE_GRAPHS = "/api/graphs/messages";

    private final ReactionGraphs graphs;
    private final ConditionalAnswers answers;

    ReactionObserverStubController(ReactionGraphs graphs, ConditionalAnswers answers) {
        this.graphs = graphs;
        this.answers = answers;
    }

    @GetMapping(path = SYSTEM_GRAPHS, produces = "application/json")
    ResponseEntity<ReactionGraphDtos.GraphIndexDto> systemIndex(
            HttpServletRequest request,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        List<ReactionGraphDtos.GraphIndexEntryDto> entries = new ArrayList<>();
        for (String system : graphs.systemsWithGraphs()) {
            graphs.ofSystem(system).ifPresent(graph -> entries.add(new ReactionGraphDtos.GraphIndexEntryDto(
                    // No system beside the name: in the index of systems it would say the same thing twice.
                    system, null, tagOf(graph), pathOf(request, SYSTEM_GRAPHS, system))));
        }
        return answers.answer(new ReactionGraphDtos.GraphIndexDto(entries), ifNoneMatch);
    }

    @GetMapping(path = COMPONENT_GRAPHS, produces = "application/json")
    ResponseEntity<ReactionGraphDtos.GraphIndexDto> componentIndex(
            HttpServletRequest request,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        List<ReactionGraphDtos.GraphIndexEntryDto> entries = new ArrayList<>();
        for (ReactionGraphs.ComponentGraph component : graphs.componentsWithGraphs()) {
            graphs.ofComponent(component.component())
                    .ifPresent(graph -> entries.add(new ReactionGraphDtos.GraphIndexEntryDto(
                            component.component(), component.system(), tagOf(graph),
                            pathOf(request, COMPONENT_GRAPHS, component.component()))));
        }
        return answers.answer(new ReactionGraphDtos.GraphIndexDto(entries), ifNoneMatch);
    }

    @GetMapping(path = MESSAGE_GRAPHS, produces = "application/json")
    ResponseEntity<ReactionGraphDtos.MessageGraphIndexDto> messageIndex(
            HttpServletRequest request,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        List<ReactionGraphDtos.MessageGraphIndexEntryDto> entries = new ArrayList<>();
        for (ReactionGraphs.MessageGraph message : graphs.messagesWithGraphs()) {
            Map<String, ReactionGraphDtos.GraphWithFingerprintDto> answer =
                    fingerprinted(graphs.ofMessageType(message.messageType()));
            if (answer.isEmpty()) {
                continue;
            }
            // One tag over the whole answer, because one request carries every variant of the type.
            entries.add(new ReactionGraphDtos.MessageGraphIndexEntryDto(message.messageType(),
                    List.copyOf(answer.keySet()), answers.tagOf(answer),
                    pathOf(request, MESSAGE_GRAPHS, message.messageType())));
        }
        return answers.answer(new ReactionGraphDtos.MessageGraphIndexDto(entries), ifNoneMatch);
    }

    @GetMapping(path = SYSTEM_GRAPHS + "/{system}", produces = "application/json")
    ResponseEntity<ReactionGraphDtos.GraphWithFingerprintDto> systemGraph(
            @PathVariable String system,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        return answers.answer(fingerprinted(graphs.ofSystem(system)
                .orElseThrow(() -> noGraph("system", system))), ifNoneMatch);
    }

    @GetMapping(path = COMPONENT_GRAPHS + "/{component}", produces = "application/json")
    ResponseEntity<ReactionGraphDtos.GraphWithFingerprintDto> componentGraph(
            @PathVariable String component,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        return answers.answer(fingerprinted(graphs.ofComponent(component)
                .orElseThrow(() -> noGraph("component", component))), ifNoneMatch);
    }

    /**
     * Every variant of one message type at once, keyed as the index announced them. One request rather than
     * one per variant, and one tag over all of them - the observer's own rule, and the one place its API is
     * not shaped like the architecture repository's.
     */
    @GetMapping(path = MESSAGE_GRAPHS + "/{messageType}", produces = "application/json")
    ResponseEntity<Map<String, ReactionGraphDtos.GraphWithFingerprintDto>> messageGraphs(
            @PathVariable String messageType,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        Map<String, ReactionGraphDtos.GraphWithFingerprintDto> answer =
                fingerprinted(graphs.ofMessageType(messageType));
        if (answer.isEmpty()) {
            throw noGraph("message type", messageType);
        }
        return answers.answer(answer, ifNoneMatch);
    }

    /**
     * The tag a graph resource will answer with, computed over the very payload it answers - which is what
     * lets a consumer skip fetching a graph it already has.
     */
    private String tagOf(ReactionGraphDtos.GraphDto graph) {
        return answers.tagOf(fingerprinted(graph));
    }

    private ReactionGraphDtos.GraphWithFingerprintDto fingerprinted(ReactionGraphDtos.GraphDto graph) {
        return new ReactionGraphDtos.GraphWithFingerprintDto(graph, answers.fingerprintOf(graph));
    }

    private Map<String, ReactionGraphDtos.GraphWithFingerprintDto> fingerprinted(
            Map<String, ReactionGraphDtos.GraphDto> byKey) {
        return byKey.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> fingerprinted(entry.getValue()),
                        (first, second) -> first, LinkedHashMap::new));
    }

    /**
     * Where a graph is served, as the index names it. It carries the context path of this service, because
     * that is what the observer's own index carries: the consumer resolves it against the origin rather than
     * appending it to the URL it was configured with.
     */
    private static String pathOf(HttpServletRequest request, String resource, String name) {
        return request.getContextPath() + resource + "/" + name;
    }

    private static ResponseStatusException noGraph(String kind, String name) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Nothing was observed reacting in the %s %s.".formatted(kind, name));
    }
}
