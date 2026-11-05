package ch.admin.bit.jeap.jme.doc.stub.reactionobserver;

import java.util.List;

/**
 * The payload of the reaction observer's graph API, as far as the doc service reads it.
 * <p>
 * <b>The field names are the observer's own</b>, discriminator included: its nodes carry {@code nodeType} and
 * its edges {@code edgeType}, and a consumer reads the polymorphism by hand off that field. A stub that
 * spelled a field differently would be a stub that passes where the real observer would not.
 * <p>
 * <b>The discriminator is a component of the record</b> rather than a method answering a constant, because
 * what is serialized of a record is its components - a kind that is only a method is a kind that never
 * reaches the wire, and every node would then be rejected as one of a kind nobody knows.
 * <p>
 * Only the node and edge kinds the doc service reads are here: what is not read is not stubbed.
 */
final class ReactionGraphDtos {

    static final String MESSAGE = "MESSAGE";
    static final String REACTION = "REACTION";
    static final String TRIGGER = "TRIGGER";
    static final String ACTION = "ACTION";

    private ReactionGraphDtos() {
    }

    /** The graph with the fingerprint of its content, which is what the graph resource answers. */
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

    /**
     * A component that was seen reacting.
     *
     * @param median how often it was seen reacting per day, which is what a runtime view's table shows
     */
    record ReactionNodeDto(String nodeType, long id, String component, int median) implements NodeDto {

        ReactionNodeDto(long id, String component, int median) {
            this(REACTION, id, component, median);
        }
    }

    sealed interface EdgeDto permits TriggerEdgeDto, ActionEdgeDto {
    }

    /** A message triggering a reaction. Only a message can, which is why the source needs no kind. */
    record TriggerEdgeDto(String edgeType, long sourceId, long targetReactionId) implements EdgeDto {

        TriggerEdgeDto(long sourceId, long targetReactionId) {
            this(TRIGGER, sourceId, targetReactionId);
        }
    }

    /** A reaction publishing a message in answer. */
    record ActionEdgeDto(String edgeType, long sourceReactionId, long targetId) implements EdgeDto {

        ActionEdgeDto(long sourceReactionId, long targetId) {
            this(ACTION, sourceReactionId, targetId);
        }
    }
}
