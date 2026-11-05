package ch.admin.bit.jeap.jme.doc.stub.reactionobserver;

import ch.admin.bit.jeap.jme.doc.stub.fixture.Landscape;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reactions of the landscape, as the one graph of everything the reaction observer has seen.
 * <p>
 * <b>One graph, whole.</b> The observer serves the complete graph of its environment, and the doc service cuts
 * the views of a system, a component and a message out of it itself. A message that triggers two reactions is
 * one node with two edges out of it, which is the whole point of drawing a graph rather than a list.
 * <p>
 * <b>One reaction node per observed reaction.</b> A reaction has exactly one trigger, so a component that
 * reacts to two messages is two reactions, and the doc service refuses a graph in which one has two.
 * <p>
 * Node ids are numbers handed out for this graph, which is what the observer does too: they address the nodes of
 * the payload they are in and nothing beyond it.
 */
@Component
class ReactionGraphs {

    private final Landscape landscape;

    ReactionGraphs(Landscape landscape) {
        this.landscape = landscape;
    }

    /** Everything that was observed, which is an empty graph where nothing was. */
    ReactionGraphDtos.GraphDto complete() {
        GraphBuilder graph = new GraphBuilder();
        for (Landscape.ObservedReaction reaction : landscape.reactions()) {
            long trigger = graph.message(reaction.messageType(), reaction.variant());
            long reacted = graph.reaction(reaction.component(), reaction.observed());
            graph.triggered(trigger, reacted);
            for (String published : reaction.publishes()) {
                graph.published(reacted, graph.message(published, null));
            }
        }
        return graph.build();
    }

    private static String keyOf(String messageType, String variant) {
        return variant == null || variant.isBlank() ? messageType : messageType + "/" + variant;
    }

    /** Collects the nodes and edges of the graph, giving each message node an id once. */
    private static final class GraphBuilder {

        private final Map<String, Long> messageIds = new LinkedHashMap<>();
        private final List<ReactionGraphDtos.NodeDto> nodes = new ArrayList<>();
        private final List<ReactionGraphDtos.EdgeDto> edges = new ArrayList<>();
        private long lastId;

        long message(String messageType, String variant) {
            return messageIds.computeIfAbsent(keyOf(messageType, variant), key -> {
                long id = ++lastId;
                nodes.add(new ReactionGraphDtos.MessageNodeDto(id, messageType, variant));
                return id;
            });
        }

        long reaction(String component, Integer observed) {
            long id = ++lastId;
            nodes.add(new ReactionGraphDtos.ReactionNodeDto(id, component, observed == null ? 0 : observed));
            return id;
        }

        void triggered(long message, long reaction) {
            edges.add(new ReactionGraphDtos.TriggerEdgeDto(message, reaction));
        }

        void published(long reaction, long message) {
            edges.add(new ReactionGraphDtos.ActionEdgeDto(reaction, message));
        }

        ReactionGraphDtos.GraphDto build() {
            return new ReactionGraphDtos.GraphDto(List.copyOf(nodes), List.copyOf(edges));
        }
    }
}
