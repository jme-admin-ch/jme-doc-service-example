package ch.admin.bit.jeap.jme.doc.stub.fixture;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * What the stubbed Governance Service reports about the landscape, read from {@code governance.yml}.
 * <p>
 * <b>Only what cannot be computed is written down</b>: the rules, their exemptions and the violations they
 * found. The rule states, scores, conformance rates, histories and trends follow from those over the
 * components of the {@link Landscape}, so the numbers on the site add up the way a reader expects them to.
 * <p>
 * Days are counted back from the day of the scoring run rather than written as dates, for the same reason
 * {@link Landscape.DocumentedComponent} has no {@code lastSeen}.
 *
 * @param trendPeriodDays how many days the histories and trends cover
 * @param rules           every rule the Governance Service knows, active or not
 */
@ConfigurationProperties("stub.governance")
public record GovernanceFixture(Integer trendPeriodDays, List<Rule> rules) {

    public GovernanceFixture {
        trendPeriodDays = trendPeriodDays == null ? 30 : trendPeriodDays;
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /**
     * @param active         whether the rule is evaluated at all. An inactive rule has an explanation and
     *                       nothing else
     * @param violationDelay how long a violation lasts before it counts, or null for none
     * @param explanation    Markdown, or null where the rule ships none
     */
    public record Rule(String ruleId, String name, Boolean active, Integer weight, Duration violationDelay,
                       String explanation, List<Parameter> parameters, List<Exemption> exemptions,
                       List<Violation> violations) {

        public Rule {
            active = active == null || active;
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
            exemptions = exemptions == null ? List.of() : List.copyOf(exemptions);
            violations = violations == null ? List.of() : List.copyOf(violations);
        }
    }

    public record Parameter(String name, String value) {
    }

    /**
     * @param endsInDays the day the exemption ends, counted from the scoring run. Null for a permanent one,
     *                   which disables the rule for the component rather than pausing it
     */
    public record Exemption(String id, String component, String reason, Integer endsInDays) {
    }

    /**
     * One violation a rule found on a component.
     *
     * @param sinceDaysAgo when it was found
     * @param fixedDaysAgo when it was fixed, or null while it lasts
     */
    public record Violation(String component, String comment, List<String> details, int sinceDaysAgo,
                            Integer fixedDaysAgo) {

        public Violation {
            details = details == null ? List.of() : List.copyOf(details);
        }
    }
}
