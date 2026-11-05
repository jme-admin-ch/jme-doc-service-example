package ch.admin.bit.jeap.jme.doc.stub.reactionobserver;

import ch.admin.bit.jeap.jme.doc.stub.http.ConditionalAnswers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The graph API of the reaction observer, as far as an import of it reads.
 * <p>
 * <b>One resource.</b> The complete graph of the environment, answered with an entity tag over the very payload,
 * so that the doc service asks conditionally on every round and fetches the graph only when it moved. An
 * environment where nothing was observed answers an empty graph rather than {@code 404}.
 * <p>
 * <b>The role is the real one.</b> {@code hasRole('reactions', 'read')} is what the reaction observer requires,
 * and it carries no tenant part: what reacted to what is nobody's system in particular. So the example
 * exercises a second client registration of the doc service and not only a second URL.
 * <p>
 * The observer offers HTTP Basic as well as a bearer token. That half is not stubbed, because the doc service
 * does not use it.
 */
@RestController
@PreAuthorize("hasRole('reactions', 'read')")
class ReactionObserverStubController {

    private static final String GRAPH = "/api/graphs";

    private final ReactionGraphs graphs;
    private final ConditionalAnswers answers;

    ReactionObserverStubController(ReactionGraphs graphs, ConditionalAnswers answers) {
        this.graphs = graphs;
        this.answers = answers;
    }

    @GetMapping(path = GRAPH, produces = "application/json")
    ResponseEntity<ReactionGraphDtos.GraphWithFingerprintDto> graph(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        ReactionGraphDtos.GraphDto graph = graphs.complete();
        return answers.answer(new ReactionGraphDtos.GraphWithFingerprintDto(graph, answers.fingerprintOf(graph)),
                ifNoneMatch);
    }
}
