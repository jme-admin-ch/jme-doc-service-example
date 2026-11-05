package ch.admin.bit.jeap.jme.doc.stub.governance;

import ch.admin.bit.jeap.jme.doc.stub.fixture.GovernanceFixture;
import ch.admin.bit.jeap.jme.doc.stub.fixture.Landscape;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static ch.admin.bit.jeap.jme.doc.stub.governance.GovernanceDtos.RuleStateValue.DISABLED;
import static ch.admin.bit.jeap.jme.doc.stub.governance.GovernanceDtos.RuleStateValue.FAIL;
import static ch.admin.bit.jeap.jme.doc.stub.governance.GovernanceDtos.RuleStateValue.OK;
import static ch.admin.bit.jeap.jme.doc.stub.governance.GovernanceDtos.RuleStateValue.PAUSED;

/**
 * The Governance Service's reports, computed from {@link GovernanceFixture} over the components of the
 * {@link Landscape}.
 * <p>
 * The rules are evaluated the way the Governance Service evaluates them. A component a rule found nothing wrong
 * with is {@code OK}. An exemption pauses the rule for the component, or disables it when it has no end. A
 * violation within the rule's violation delay is still {@code OK}, with the end of the grace period added to its
 * comment. A score is the weight of the rules a component complies with, as a share of the weight of the rules
 * that are evaluated for it.
 * <p>
 * <b>The rules are taken to be evaluated at five every morning</b>, so the systems and rules move once a day, the
 * way the architecture repository stub's {@code lastSeen} does, and not once a request. <b>A scoring run is taken
 * to have completed when this stub started</b>, or at the latest evaluation if that is later. The indexes carry
 * that time, so each start of the example is a new scoring run for the doc service to import, while an import
 * later the same day fetches no system and no rule again.
 */
@Component
class GovernanceReports {

    private static final LocalTime EVALUATION_TIME = LocalTime.of(5, 0);
    private static final DateTimeFormatter GRACE_PERIOD_END_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final Landscape landscape;
    private final GovernanceFixture fixture;
    private final OffsetDateTime startedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    GovernanceReports(Landscape landscape, GovernanceFixture fixture) {
        this.landscape = landscape;
        this.fixture = fixture;
        check();
    }

    /** The latest evaluation of the rules: five this morning, or five yesterday morning before then. */
    OffsetDateTime evaluatedAt() {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime today = now.toLocalDate().atTime(EVALUATION_TIME).atZone(zone);
        return (now.isBefore(today) ? today.minusDays(1) : today).toOffsetDateTime();
    }

    /** When the latest scoring run completed, which is what the indexes say the scores are as of. */
    OffsetDateTime scoredAt() {
        OffsetDateTime evaluatedAt = evaluatedAt();
        return startedAt.isAfter(evaluatedAt) ? startedAt : evaluatedAt;
    }

    int trendPeriodDays() {
        return fixture.trendPeriodDays();
    }

    /** Every system of the landscape, by name. */
    List<GovernanceDtos.SystemDto> systems(OffsetDateTime evaluatedAt) {
        return landscape.systems().stream()
                .map(system -> systemOf(system, evaluatedAt))
                .sorted(Comparator.comparing(GovernanceDtos.SystemDto::name))
                .toList();
    }

    Optional<GovernanceDtos.SystemDto> system(String name, OffsetDateTime evaluatedAt) {
        return landscape.find(name).map(system -> systemOf(system, evaluatedAt));
    }

    /** Every rule, by ID. */
    List<GovernanceDtos.RuleDto> rules(OffsetDateTime evaluatedAt) {
        return fixture.rules().stream()
                .map(rule -> ruleOf(rule, evaluatedAt))
                .sorted(Comparator.comparing(GovernanceDtos.RuleDto::ruleId))
                .toList();
    }

    Optional<GovernanceDtos.RuleDto> rule(String ruleId, OffsetDateTime evaluatedAt) {
        return fixture.rules().stream()
                .filter(rule -> rule.ruleId().equals(ruleId))
                .findFirst()
                .map(rule -> ruleOf(rule, evaluatedAt));
    }

    /**
     * The share of the total weight of the active rules, in percent with one decimal - nominal, since a
     * component for which other rules are disabled weighs each remaining rule more.
     */
    private BigDecimal shareOf(GovernanceFixture.Rule rule) {
        int total = activeRules().stream().mapToInt(GovernanceFixture.Rule::weight).sum();
        return BigDecimal.valueOf(rule.weight() * 100L).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }

    private GovernanceDtos.SystemDto systemOf(Landscape.DocumentedSystem system, OffsetDateTime evaluatedAt) {
        List<GovernanceDtos.ComponentDto> components = system.components().stream()
                .map(component -> componentOf(component.name(), evaluatedAt))
                .sorted(Comparator.comparing(GovernanceDtos.ComponentDto::name))
                .toList();
        List<String> names = system.components().stream().map(Landscape.DocumentedComponent::name).toList();
        List<GovernanceDtos.DailyScoreDto> history =
                historyOf(evaluatedAt, daysAgo -> average(names.stream().map(name -> score(name, daysAgo)).toList()));
        return new GovernanceDtos.SystemDto(system.name(), latest(history), trendOf(history), history, components);
    }

    private GovernanceDtos.ComponentDto componentOf(String component, OffsetDateTime evaluatedAt) {
        List<GovernanceDtos.DailyScoreDto> history = historyOf(evaluatedAt, daysAgo -> score(component, daysAgo));
        List<GovernanceDtos.RuleStateDto> states = activeRules().stream()
                .sorted(Comparator.comparing(GovernanceFixture.Rule::ruleId))
                .map(rule -> ruleStateOf(rule, component, evaluatedAt))
                .toList();
        return new GovernanceDtos.ComponentDto(component, latest(history), trendOf(history), history, states);
    }

    private GovernanceDtos.RuleStateDto ruleStateOf(GovernanceFixture.Rule rule, String component,
                                                    OffsetDateTime evaluatedAt) {
        Evaluation today = evaluate(rule, component, 0);
        String comment = null;
        List<String> details = List.of();
        if (today.violation() != null) {
            comment = today.violation().comment();
            details = today.violation().details();
            if (today.graced()) {
                OffsetDateTime endsAt = detectedAt(today.violation(), evaluatedAt).plus(rule.violationDelay());
                comment = comment + ". Violation grace period ends at "
                          + endsAt.atZoneSameInstant(ZoneId.systemDefault()).format(GRACE_PERIOD_END_FORMAT);
            }
        }
        return new GovernanceDtos.RuleStateDto(rule.ruleId(), rule.name(), today.state(), comment, details,
                evaluatedAt.minusDays(daysInState(rule, component)));
    }

    private GovernanceDtos.RuleDto ruleOf(GovernanceFixture.Rule rule, OffsetDateTime evaluatedAt) {
        List<GovernanceDtos.ParameterDto> parameters = rule.parameters().stream()
                .sorted(Comparator.comparing(GovernanceFixture.Parameter::name))
                .map(parameter -> new GovernanceDtos.ParameterDto(parameter.name(), parameter.value()))
                .toList();
        boolean gracePeriodConfigured = rule.violationDelay() != null && rule.violationDelay().isPositive();
        String violationDelay = rule.violationDelay() == null ? null : rule.violationDelay().toString();
        if (!Boolean.TRUE.equals(rule.active())) {
            return new GovernanceDtos.RuleDto(rule.ruleId(), rule.name(), false, rule.explanation(), rule.weight(),
                    null, violationDelay, gracePeriodConfigured, parameters, List.of(), null,
                    GovernanceDtos.Trend.NO_DATA, List.of(), List.of(), List.of());
        }
        List<Placed> components = components();
        List<Integer> rates = IntStream.iterate(trendPeriodDays() - 1, daysAgo -> daysAgo >= 0, daysAgo -> daysAgo - 1)
                .mapToObj(daysAgo -> rate(rule, components, daysAgo))
                .filter(Objects::nonNull)
                .toList();
        return new GovernanceDtos.RuleDto(rule.ruleId(), rule.name(), true, rule.explanation(), rule.weight(),
                shareOf(rule), violationDelay, gracePeriodConfigured, parameters, exemptionsOf(rule, evaluatedAt),
                rate(rule, components, 0), trendOfRates(rates), systemRatesOf(rule),
                nonCompliantComponentsOf(rule, evaluatedAt),
                gracePeriodConfigured ? gracePeriodComponentsOf(rule, evaluatedAt) : List.of());
    }

    private List<GovernanceDtos.ExemptionDto> exemptionsOf(GovernanceFixture.Rule rule, OffsetDateTime evaluatedAt) {
        return rule.exemptions().stream()
                .sorted(Comparator.comparing(GovernanceFixture.Exemption::component)
                        .thenComparing(GovernanceFixture.Exemption::id))
                .map(exemption -> new GovernanceDtos.ExemptionDto(exemption.id(), exemption.component(),
                        exemption.reason(),
                        exemption.endsInDays() == null ? null
                                : evaluatedAt.toLocalDate().plusDays(exemption.endsInDays()),
                        exemption.endsInDays() == null ? GovernanceDtos.ExemptionEffect.DISABLED
                                : GovernanceDtos.ExemptionEffect.PAUSED,
                        List.of()))
                .toList();
    }

    private List<GovernanceDtos.SystemConformanceDto> systemRatesOf(GovernanceFixture.Rule rule) {
        List<GovernanceDtos.SystemConformanceDto> rates = new ArrayList<>();
        for (Landscape.DocumentedSystem system : landscape.systems()) {
            List<Placed> components = components().stream()
                    .filter(placed -> placed.system().equals(system.name()))
                    .toList();
            Integer rate = rate(rule, components, 0);
            if (rate != null) {
                rates.add(new GovernanceDtos.SystemConformanceDto(system.name(), rate));
            }
        }
        rates.sort(Comparator.comparing(GovernanceDtos.SystemConformanceDto::system));
        return rates;
    }

    private List<GovernanceDtos.NonCompliantComponentDto> nonCompliantComponentsOf(GovernanceFixture.Rule rule,
                                                                                  OffsetDateTime evaluatedAt) {
        return components().stream()
                .filter(placed -> evaluate(rule, placed.component(), 0).state() == FAIL)
                .map(placed -> new GovernanceDtos.NonCompliantComponentDto(placed.system(), placed.component(),
                        evaluatedAt.toLocalDate().minusDays(daysInState(rule, placed.component()))))
                .sorted(Comparator.comparing(GovernanceDtos.NonCompliantComponentDto::system)
                        .thenComparing(GovernanceDtos.NonCompliantComponentDto::component))
                .toList();
    }

    private List<GovernanceDtos.GracePeriodComponentDto> gracePeriodComponentsOf(GovernanceFixture.Rule rule,
                                                                                OffsetDateTime evaluatedAt) {
        List<GovernanceDtos.GracePeriodComponentDto> graced = new ArrayList<>();
        for (Placed placed : components()) {
            Evaluation today = evaluate(rule, placed.component(), 0);
            if (today.graced()) {
                OffsetDateTime detectedAt = detectedAt(today.violation(), evaluatedAt);
                graced.add(new GovernanceDtos.GracePeriodComponentDto(placed.system(), placed.component(),
                        today.violation().comment(), today.violation().details(), detectedAt,
                        detectedAt.plus(rule.violationDelay())));
            }
        }
        graced.sort(Comparator.comparing(GovernanceDtos.GracePeriodComponentDto::gracePeriodEndsAt)
                .thenComparing(GovernanceDtos.GracePeriodComponentDto::system)
                .thenComparing(GovernanceDtos.GracePeriodComponentDto::component));
        return graced;
    }

    /** How a rule stood for a component a number of days before the scoring run. */
    private Evaluation evaluate(GovernanceFixture.Rule rule, String component, int daysAgo) {
        Optional<GovernanceFixture.Exemption> exemption = rule.exemptions().stream()
                .filter(candidate -> candidate.component().equals(component))
                .findFirst();
        if (exemption.isPresent()) {
            return new Evaluation(exemption.get().endsInDays() == null ? DISABLED : PAUSED, null, false);
        }
        for (GovernanceFixture.Violation violation : rule.violations()) {
            boolean found = violation.component().equals(component) && violation.sinceDaysAgo() >= daysAgo;
            boolean fixed = violation.fixedDaysAgo() != null && violation.fixedDaysAgo() >= daysAgo;
            if (found && !fixed) {
                boolean graced = rule.violationDelay() != null
                                 && Duration.ofDays((long) violation.sinceDaysAgo() - daysAgo)
                                         .compareTo(rule.violationDelay()) < 0;
                return new Evaluation(graced ? OK : FAIL, violation, graced);
            }
        }
        return new Evaluation(OK, null, false);
    }

    /**
     * How many days a rule has stood as it does today for a component, as far back as the trend period goes -
     * which is when its state last changed.
     */
    private int daysInState(GovernanceFixture.Rule rule, String component) {
        GovernanceDtos.RuleStateValue today = evaluate(rule, component, 0).state();
        for (int daysAgo = 1; daysAgo < trendPeriodDays(); daysAgo++) {
            if (evaluate(rule, component, daysAgo).state() != today) {
                return daysAgo - 1;
            }
        }
        return trendPeriodDays() - 1;
    }

    private Integer score(String component, int daysAgo) {
        int earned = 0;
        int possible = 0;
        for (GovernanceFixture.Rule rule : activeRules()) {
            GovernanceDtos.RuleStateValue state = evaluate(rule, component, daysAgo).state();
            if (state == OK) {
                earned += rule.weight();
                possible += rule.weight();
            } else if (state == FAIL) {
                possible += rule.weight();
            }
        }
        return possible == 0 ? null : Math.round(100f * earned / possible);
    }

    private Integer rate(GovernanceFixture.Rule rule, List<Placed> components, int daysAgo) {
        int compliant = 0;
        int evaluated = 0;
        for (Placed placed : components) {
            GovernanceDtos.RuleStateValue state = evaluate(rule, placed.component(), daysAgo).state();
            if (state == OK) {
                compliant++;
                evaluated++;
            } else if (state == FAIL) {
                evaluated++;
            }
        }
        return evaluated == 0 ? null : Math.round(100f * compliant / evaluated);
    }

    private List<GovernanceDtos.DailyScoreDto> historyOf(OffsetDateTime evaluatedAt, ScoreOnDay score) {
        List<GovernanceDtos.DailyScoreDto> history = new ArrayList<>();
        for (int daysAgo = trendPeriodDays() - 1; daysAgo >= 0; daysAgo--) {
            Integer value = score.daysAgo(daysAgo);
            if (value != null) {
                history.add(new GovernanceDtos.DailyScoreDto(evaluatedAt.toLocalDate().minusDays(daysAgo), value));
            }
        }
        return history;
    }

    private static Integer latest(List<GovernanceDtos.DailyScoreDto> history) {
        return history.isEmpty() ? null : history.getLast().score();
    }

    private static GovernanceDtos.Trend trendOf(List<GovernanceDtos.DailyScoreDto> history) {
        return trendOfRates(history.stream().map(GovernanceDtos.DailyScoreDto::score).toList());
    }

    private static GovernanceDtos.Trend trendOfRates(List<Integer> values) {
        if (values.size() < 2) {
            return GovernanceDtos.Trend.NO_DATA;
        }
        int change = values.getLast() - values.getFirst();
        return change > 0 ? GovernanceDtos.Trend.UP
                : change < 0 ? GovernanceDtos.Trend.DOWN
                : GovernanceDtos.Trend.STABLE;
    }

    private static Integer average(List<Integer> scores) {
        List<Integer> known = scores.stream().filter(Objects::nonNull).toList();
        return known.isEmpty() ? null
                : Math.round((float) known.stream().mapToInt(Integer::intValue).sum() / known.size());
    }

    private static OffsetDateTime detectedAt(GovernanceFixture.Violation violation, OffsetDateTime evaluatedAt) {
        return evaluatedAt.minusDays(violation.sinceDaysAgo());
    }

    private List<GovernanceFixture.Rule> activeRules() {
        return fixture.rules().stream().filter(rule -> Boolean.TRUE.equals(rule.active())).toList();
    }

    private List<Placed> components() {
        return landscape.systems().stream()
                .flatMap(system -> system.components().stream()
                        .map(component -> new Placed(system.name(), component.name())))
                .toList();
    }

    /**
     * Refuses a fixture that names a component the landscape does not hold, or a rule twice - either would
     * quietly report something other than what the file says.
     */
    private void check() {
        Set<String> known = new HashSet<>(components().stream().map(Placed::component).toList());
        Set<String> ruleIds = new HashSet<>();
        for (GovernanceFixture.Rule rule : fixture.rules()) {
            if (!ruleIds.add(rule.ruleId())) {
                throw new IllegalStateException("The rule %s is in governance.yml twice.".formatted(rule.ruleId()));
            }
            if (Boolean.TRUE.equals(rule.active()) && rule.weight() == null) {
                throw new IllegalStateException(
                        "The active rule %s has no weight in governance.yml.".formatted(rule.ruleId()));
            }
            List<String> named = new ArrayList<>();
            rule.exemptions().forEach(exemption -> named.add(exemption.component()));
            rule.violations().forEach(violation -> named.add(violation.component()));
            for (String component : named) {
                if (!known.contains(component)) {
                    throw new IllegalStateException(("The rule %s of governance.yml names the component %s, "
                            + "which landscape.yml does not hold.").formatted(rule.ruleId(), component));
                }
            }
        }
    }

    private record Evaluation(GovernanceDtos.RuleStateValue state, GovernanceFixture.Violation violation,
                              boolean graced) {
    }

    private record Placed(String system, String component) {
    }

    @FunctionalInterface
    private interface ScoreOnDay {
        Integer daysAgo(int daysAgo);
    }
}
