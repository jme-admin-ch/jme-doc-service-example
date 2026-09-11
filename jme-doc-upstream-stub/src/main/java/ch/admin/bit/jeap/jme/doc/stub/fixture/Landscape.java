package ch.admin.bit.jeap.jme.doc.stub.fixture;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Optional;

/**
 * The landscape every stubbed upstream answers about, read from {@code landscape.yml}.
 * <p>
 * <b>One landscape, several projections.</b> The architecture repository and the reaction observer describe the
 * same systems, components and message types from two sides, and two unrelated fixtures would drift - the site
 * would then show a runtime view contradicting its building block view, which is precisely what an example must
 * not teach. So the landscape is described once and each upstream projects it onto its own payloads.
 * <p>
 * <b>It is deliberately small</b>: two systems of two components each, two events, one REST call within each
 * system, and two reactions. Two systems rather than one because a single system can show neither a context
 * view with a neighbour in it nor a component's <i>Messages</i> page carrying another system's message - and
 * those are the pages worth looking at. The two reactions are a chain across the two systems, which is what a
 * runtime view is for: one system reacts to an order and publishes what became of its payment, and the other
 * reacts to that.
 * <p>
 * The field names are the ones the architecture repository puts on the wire, so that the projection below is a
 * regrouping and not a translation. Adding a system to what this example documents is editing that file.
 *
 * @param systems   the systems of the landscape, in the order they are answered in
 * @param reactions what was observed reacting to what at runtime - the reaction observer's side of the same
 *                  landscape, projected onto its graphs
 */
@ConfigurationProperties("stub")
public record Landscape(List<DocumentedSystem> systems, List<ObservedReaction> reactions) {

    public Landscape {
        systems = systems == null ? List.of() : List.copyOf(systems);
        reactions = reactions == null ? List.of() : List.copyOf(reactions);
    }

    /** One system, by the name it is addressed with. */
    public Optional<DocumentedSystem> find(String name) {
        return systems.stream().filter(system -> system.name().equals(name)).findFirst();
    }

    /**
     * @param components what the system is built of
     * @param relations  what it exchanges with other systems and within itself
     * @param messages   the message types this system <b>defines</b>. A message is defined once, by the system
     *                   that owns it, and the components of other systems appear in its contracts
     */
    public record DocumentedSystem(String name, String description, List<String> aliases, Team team,
                                   List<DocumentedComponent> components, List<Relation> relations,
                                   List<Message> messages) {

        public DocumentedSystem {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            components = components == null ? List.of() : List.copyOf(components);
            relations = relations == null ? List.of() : List.copyOf(relations);
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }

    public record Team(String name, String contactAddress, String jiraLink, String confluenceLink) {
    }

    /**
     * Note that there is no {@code lastSeen} here. The architecture repository answers when an importer last
     * saw a component, and a component nothing has seen for a fortnight is documented as stale - so a date
     * written into a fixture would turn this example's site stale a fortnight after it was written. The
     * projection answers the current day instead.
     *
     * @param type one of the component types the doc service knows, by name - anything else is documented as
     *             {@code UNKNOWN}, which is what the real upstream's newer kinds do too
     */
    public record DocumentedComponent(String name, String description, String type, String importer,
                                      List<RestApi> restApis) {

        public DocumentedComponent {
            restApis = restApis == null ? List.of() : List.copyOf(restApis);
        }
    }

    public record RestApi(String method, String path) {
    }

    /**
     * @param type {@code REST_API_RELATION}, {@code EVENT_RELATION} or {@code COMMAND_RELATION} - the
     *             upstream's spellings, and anything else is documented as a plain relation
     */
    public record Relation(String type, String consumerSystem, String consumer, String providerSystem,
                           String provider, String method, String path, String pactUrl, String messageType) {
    }

    /**
     * @param kind     {@code EVENT} or {@code COMMAND}
     * @param versions the versions that exist, oldest first
     */
    public record Message(String name, String kind, String scope, String topic, String description,
                          List<String> versions, List<MessageContract> contracts) {

        public Message {
            versions = versions == null ? List.of() : List.copyOf(versions);
            contracts = contracts == null ? List.of() : List.copyOf(contracts);
        }
    }

    /**
     * @param role {@code PUBLISHER} or {@code CONSUMER}, as the upstream spells the two sides of an event
     */
    public record MessageContract(String role, String component, String system, String topic,
                                  List<String> versions) {

        public MessageContract {
            versions = versions == null ? List.of() : List.copyOf(versions);
        }
    }

    /**
     * One reaction, as the reaction observer saw it: a message arrived, a component did something, and it
     * published these messages in answer.
     * <p>
     * <b>Every name here has to be in the landscape above.</b> The doc service resolves what the observer
     * offers against the architecture model it imported and leaves out what the model does not hold, so a
     * component or a message type only the reactions know would silently document nothing.
     *
     * @param messageType what triggered the reaction
     * @param variant     the variant of that message type, or null where it has none. The observer keys a
     *                    variant's graph as {@code messageType/variant}
     * @param component   what reacted
     * @param system      the system the reaction was published under, which is how the observer groups them
     * @param publishes   the message types the reaction published in answer, empty where none was seen
     * @param observed    how often the reaction was seen, which is what a runtime view's table shows
     */
    public record ObservedReaction(String messageType, String variant, String component, String system,
                                   List<String> publishes, Integer observed) {

        public ObservedReaction {
            publishes = publishes == null ? List.of() : List.copyOf(publishes);
        }
    }
}
