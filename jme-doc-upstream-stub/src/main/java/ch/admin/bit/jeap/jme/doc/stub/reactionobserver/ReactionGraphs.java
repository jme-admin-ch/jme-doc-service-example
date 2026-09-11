package ch.admin.bit.jeap.jme.doc.stub.reactionobserver;

import ch.admin.bit.jeap.jme.doc.stub.fixture.Landscape;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The reactions of the landscape, cut into the graphs the reaction observer serves.
 * <p>
 * <b>One graph per thing a reader can be looking at.</b> The observer holds one graph of everything it has
 * seen and cuts a subgraph out of it per system, per component and per message type; this projects the same
 * three cuts out of the fixture. A system's graph is the reactions of its components, a component's is its
 * own, and a message type's is what it triggered <i>and</i> what published it - the two sides that make a
 * message page say where it comes from and what it sets off.
 * <p>
 * <b>Only graphs that are not empty exist.</b> The indexes of the observer list exactly the graphs that have
 * something in them, and everything else answers {@code 404} - so a system nothing was observed reacting in
 * is absent here rather than present and empty, and the doc service writes it no runtime view.
 * <p>
 * Node ids are handed out per graph, which is what the observer does too: they address the nodes of the
 * payload they are in and nothing beyond it.
 */
@Component
class ReactionGraphs {

    private final Landscape landscape;

    ReactionGraphs(Landscape landscape) {
        this.landscape = landscape;
    }

    /** The systems something was observed reacting in, in the order the landscape holds them. */
    List<String> systemsWithGraphs() {
        return landscape.systems().stream()
                .map(Landscape.DocumentedSystem::name)
                .filter(system -> !reactionsOfSystem(system).isEmpty())
                .toList();
    }

    /** The components something was observed reacting in, each with the system it reacted under. */
    List<ComponentGraph> componentsWithGraphs() {
        return landscape.reactions().stream()
                .map(reaction -> new ComponentGraph(reaction.component(), reaction.system()))
                .distinct()
                .toList();
    }

    /**
     * The message types that appear in a reaction, each with the keys its graphs are answered under - the
     * type alone where it has no variant, and {@code messageType/variant} where it has one.
     */
    List<MessageGraph> messagesWithGraphs() {
        Map<String, List<String>> keys = new LinkedHashMap<>();
        for (Landscape.ObservedReaction reaction : landscape.reactions()) {
            keys.computeIfAbsent(reaction.messageType(), type -> new ArrayList<>())
                    .add(keyOf(reaction.messageType(), reaction.variant()));
            for (String published : reaction.publishes()) {
                keys.computeIfAbsent(published, type -> new ArrayList<>())
                        .add(keyOf(published, null));
            }
        }
        return keys.entrySet().stream()
                .map(entry -> new MessageGraph(entry.getKey(), entry.getValue().stream().distinct().toList()))
                .toList();
    }

    /** The graph of one system, or empty where nothing was observed reacting in it. */
    Optional<ReactionGraphDtos.GraphDto> ofSystem(String system) {
        return graphOf(reactionsOfSystem(system));
    }

    /** The graph of one component, or empty where nothing was observed reacting in it. */
    Optional<ReactionGraphDtos.GraphDto> ofComponent(String component) {
        return graphOf(landscape.reactions().stream()
                .filter(reaction -> component.equals(reaction.component()))
                .toList());
    }

    /**
     * The graphs of one message type, keyed as the observer keys them: one entry per variant that has a
     * graph, and one entry under the type itself where it has no variant. Empty where the type appears in no
     * reaction at all.
     */
    Map<String, ReactionGraphDtos.GraphDto> ofMessageType(String messageType) {
        Map<String, ReactionGraphDtos.GraphDto> graphs = new LinkedHashMap<>();
        for (String key : keysOf(messageType)) {
            graphOf(reactionsAround(messageType, variantOf(messageType, key)))
                    .ifPresent(graph -> graphs.put(key, graph));
        }
        return graphs;
    }

    private List<Landscape.ObservedReaction> reactionsOfSystem(String system) {
        return landscape.reactions().stream()
                .filter(reaction -> system.equals(reaction.system()))
                .toList();
    }

    /**
     * Both sides of a message type: the reactions it triggered, and the reactions that published it. A
     * message page shows what sets a message off as well as what it sets off, and a graph carrying only one
     * of the two would make the other invisible.
     */
    private List<Landscape.ObservedReaction> reactionsAround(String messageType, String variant) {
        return landscape.reactions().stream()
                .filter(reaction -> (messageType.equals(reaction.messageType())
                                     && Objects.equals(variant, reaction.variant()))
                                    || reaction.publishes().contains(messageType))
                .toList();
    }

    private List<String> keysOf(String messageType) {
        return messagesWithGraphs().stream()
                .filter(message -> message.messageType().equals(messageType))
                .findFirst()
                .map(MessageGraph::variants)
                .orElse(List.of());
    }

    /** The variant a key carries: what is left of it once the message type in front of it is removed. */
    private static String variantOf(String messageType, String key) {
        return key.equals(messageType) ? null : key.substring(messageType.length() + 1);
    }

    private static String keyOf(String messageType, String variant) {
        return variant == null || variant.isBlank() ? messageType : messageType + "/" + variant;
    }

    private static Optional<ReactionGraphDtos.GraphDto> graphOf(List<Landscape.ObservedReaction> reactions) {
        if (reactions.isEmpty()) {
            return Optional.empty();
        }
        GraphBuilder graph = new GraphBuilder();
        for (Landscape.ObservedReaction reaction : reactions) {
            long trigger = graph.message(reaction.messageType(), reaction.variant());
            long reacted = graph.reaction(reaction.component());
            graph.triggered(trigger, reacted, reaction.observed());
            for (String published : reaction.publishes()) {
                graph.published(reacted, graph.message(published, null));
            }
        }
        return Optional.of(graph.build());
    }

    /** One component's graph, with the system the observer says its reactions were published under. */
    record ComponentGraph(String component, String system) {
    }

    /** One message type's graphs, with the keys they are answered under. */
    record MessageGraph(String messageType, List<String> variants) {
    }

    /**
     * Collects the nodes and edges of one graph, giving each node an id once - a message that triggers two
     * reactions is one node with two edges out of it, which is the whole point of drawing a graph rather than
     * a list.
     */
    private static final class GraphBuilder {

        private final Map<String, Long> ids = new LinkedHashMap<>();
        private final List<ReactionGraphDtos.NodeDto> nodes = new ArrayList<>();
        private final List<ReactionGraphDtos.EdgeDto> edges = new ArrayList<>();

        long message(String messageType, String variant) {
            return ids.computeIfAbsent("MESSAGE " + keyOf(messageType, variant), key -> {
                long id = ids.size() + 1L;
                nodes.add(new ReactionGraphDtos.MessageNodeDto(id, messageType, variant));
                return id;
            });
        }

        long reaction(String component) {
            return ids.computeIfAbsent("REACTION " + component, key -> {
                long id = ids.size() + 1L;
                nodes.add(new ReactionGraphDtos.ReactionNodeDto(id, component));
                return id;
            });
        }

        void triggered(long message, long reaction, Integer observed) {
            edges.add(new ReactionGraphDtos.TriggerEdgeDto(message, reaction, observed));
        }

        void published(long reaction, long message) {
            edges.add(new ReactionGraphDtos.ActionEdgeDto(reaction, message));
        }

        ReactionGraphDtos.GraphDto build() {
            return new ReactionGraphDtos.GraphDto(List.copyOf(nodes), List.copyOf(edges));
        }
    }
}
