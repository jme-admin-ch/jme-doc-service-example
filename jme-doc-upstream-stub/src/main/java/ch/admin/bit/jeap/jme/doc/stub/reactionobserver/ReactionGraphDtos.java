package ch.admin.bit.jeap.jme.doc.stub.reactionobserver;

import java.util.List;

/**
 * The payloads of the reaction observer, as far as the doc service reads them.
 * <p>
 * <b>The field names are the observer's own</b>, discriminator included: its nodes carry {@code nodeType} and
 * its edges {@code edgeType}, and a consumer reads the polymorphism by hand off that field. A stub that
 * spelled a field differently would be a stub that passes where the real observer would not.
 * <p>
 * <b>The discriminator is a component of the record</b> rather than a method answering a constant, because
 * what is serialized of a record is its components - a kind that is only a method is a kind that never
 * reaches the wire, and every node would then be skipped as one of a kind nobody knows.
 * <p>
 * A node kind this version of the doc service does not know is skipped rather than failing the graph, which is
 * why there are two kinds here and not the observer's whole vocabulary: what is not read is not stubbed.
 */
final class ReactionGraphDtos {

    static final String MESSAGE = "MESSAGE";
    static final String REACTION = "REACTION";
    static final String TRIGGER = "TRIGGER";
    static final String ACTION = "ACTION";

    private ReactionGraphDtos() {
    }

    /** A graph with the fingerprint of its content, which is what a graph resource answers. */
    record GraphWithFingerprintDto(GraphDto graph, String fingerprint) {
    }

    record GraphDto(List<NodeDto> nodes, List<EdgeDto> edges) {
    }

    sealed interface NodeDto permits MessageNodeDto, ReactionNodeDto {
    }

    /** A message that was seen arriving, with its variant where it has one. */
    record MessageNodeDto(String nodeType, long id, String messageType, String variant) implements NodeDto {

        MessageNodeDto(long id, String messageType, String variant) {
            this(MESSAGE, id, messageType, variant);
        }
    }

    /** A component that was seen reacting. */
    record ReactionNodeDto(String nodeType, long id, String component) implements NodeDto {

        ReactionNodeDto(long id, String component) {
            this(REACTION, id, component);
        }
    }

    sealed interface EdgeDto permits TriggerEdgeDto, ActionEdgeDto {
    }

    /**
     * A message triggering a reaction. Only a message can, which is why {@code sourceNodeType} is a constant
     * here - it is a field of the observer's payload all the same.
     *
     * @param median how often the observer saw this trigger - its own name for the number, kept as it is
     */
    record TriggerEdgeDto(String edgeType, long sourceId, String sourceNodeType, long targetReactionId,
                          Integer median) implements EdgeDto {

        TriggerEdgeDto(long sourceId, long targetReactionId, Integer median) {
            this(TRIGGER, sourceId, MESSAGE, targetReactionId, median);
        }
    }

    /** A reaction publishing a message in answer. */
    record ActionEdgeDto(String edgeType, long sourceReactionId, long targetId, String targetNodeType)
            implements EdgeDto {

        ActionEdgeDto(long sourceReactionId, long targetId) {
            this(ACTION, sourceReactionId, targetId, MESSAGE);
        }
    }

    /** Which graphs exist, with the entity tag of each - one call instead of one per system or component. */
    record GraphIndexDto(List<GraphIndexEntryDto> entries) {
    }

    /**
     * @param name   the system or the component the graph is of
     * @param system the system a component's reactions were published under; null in the index of systems,
     *               where it would say the same thing twice
     * @param etag   the tag the graph resource answers with, byte for byte
     * @param path   where that resource is, so a consumer builds no URL out of a name
     */
    record GraphIndexEntryDto(String name, String system, String etag, String path) {
    }

    record MessageGraphIndexDto(List<MessageGraphIndexEntryDto> entries) {
    }

    /**
     * @param variants the keys the graph resource answers with - the message type alone when it has no
     *                 variant, and {@code messageType/variant} otherwise
     * @param etag     the tag of the whole answer, so every variant of one type shares it
     */
    record MessageGraphIndexEntryDto(String messageType, List<String> variants, String etag, String path) {
    }
}
