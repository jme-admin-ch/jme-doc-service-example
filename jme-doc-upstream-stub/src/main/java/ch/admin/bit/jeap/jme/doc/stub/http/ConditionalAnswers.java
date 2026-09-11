package ch.admin.bit.jeap.jme.doc.stub.http;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Answers a payload with an entity tag taken over that payload, and a {@code 304} when the caller already has
 * it.
 * <p>
 * <b>The tag is computed rather than written down</b>, and that is the other reason this stub is a service
 * instead of a directory of files. The replication index of the architecture repository carries the tag of
 * each content resource <i>inside its body</i>, byte-identical to the {@code ETag} that resource answers with -
 * which is what lets a consumer decide whether to fetch without asking. A file server derives its tag from a
 * file's timestamp and size, so the two could never be held in step. Hashing what is about to be serialized
 * keeps them equal by construction.
 * <p>
 * It is written here rather than in the architecture repository's package because every stubbed upstream needs
 * it: the reaction observer tags its graphs the same way and carries the same value in the body as a
 * fingerprint.
 */
@Component
public class ConditionalAnswers {

    private final JsonMapper json;

    public ConditionalAnswers(JsonMapper json) {
        this.json = json;
    }

    /**
     * The payload with its tag, or {@code 304} when the caller already has that version.
     *
     * @param ifNoneMatch the header as it arrived, or null. Compared as it stands, quotes included: a
     *                    consumer sends back exactly the string it was given
     */
    public <T> ResponseEntity<T> answer(T payload, String ifNoneMatch) {
        String tag = tagOf(payload);
        if (tag.equals(ifNoneMatch)) {
            return ResponseEntity.status(304).eTag(tag).build();
        }
        return ResponseEntity.ok().eTag(tag).body(payload);
    }

    /**
     * What a payload is answered with. Public because an index that names another resource's tag has to
     * compute that resource's tag without answering it.
     */
    public String tagOf(Object payload) {
        return "\"sha256:%s\"".formatted(fingerprintOf(payload));
    }

    /**
     * The hash of a payload on its own, without the quotes an entity tag is wrapped in. The reaction observer
     * carries one of these inside a graph resource, beside the tag it answers with.
     */
    public String fingerprintOf(Object payload) {
        return sha256(json.writeValueAsBytes(payload));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // Every JVM has SHA-256, so this cannot happen and there is nothing to recover to.
            throw new IllegalStateException(e);
        }
    }
}
