package ch.admin.bit.jeap.jme.doc.stub.governance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The payloads of the Governance Service's {@code /docs-api}, with the field names and enum values it puts on
 * the wire.
 */
final class GovernanceDtos {

    private GovernanceDtos() {
    }

    enum Trend {UP, DOWN, STABLE, UNKNOWN, NO_DATA}

    enum RuleStateValue {OK, FAIL, PAUSED, DISABLED, UNKNOWN}

    enum ExemptionEffect {PAUSED, DISABLED, EXPIRED}

    record SystemIndexDto(OffsetDateTime scoredAt, int trendPeriodDays, List<SystemIndexEntryDto> systems) {
    }

    record SystemIndexEntryDto(String name, Integer score, Trend trend, String etag, String url) {
    }

    record SystemDto(String name, Integer score, Trend trend, List<DailyScoreDto> history,
                     List<ComponentDto> components) {
    }

    record ComponentDto(String name, Integer score, Trend trend, List<DailyScoreDto> history,
                        List<RuleStateDto> ruleStates) {
    }

    record RuleStateDto(String ruleId, String name, RuleStateValue state, String comment, List<String> details,
                        OffsetDateTime lastStatusChange) {
    }

    record DailyScoreDto(LocalDate day, int score) {
    }

    record RuleIndexDto(OffsetDateTime scoredAt, List<RuleIndexEntryDto> rules) {
    }

    record RuleIndexEntryDto(String ruleId, String name, boolean active, Integer weight, BigDecimal share,
                             Integer conformanceRate, Trend trend, Integer exemptionCount, String etag,
                             String url) {
    }

    record RuleDto(String ruleId, String name, boolean active, String explanation, Integer weight,
                   BigDecimal share, String violationDelay, boolean gracePeriodConfigured,
                   List<ParameterDto> parameters, List<ExemptionDto> exemptions, Integer conformanceRate,
                   Trend trend, List<SystemConformanceDto> systems,
                   List<NonCompliantComponentDto> nonCompliantComponents,
                   List<GracePeriodComponentDto> gracePeriodComponents) {
    }

    record ParameterDto(String name, String value) {
    }

    record ExemptionDto(String id, String component, String reason, LocalDate until, ExemptionEffect effect,
                        List<ParameterDto> parameters) {
    }

    record SystemConformanceDto(String system, int conformanceRate) {
    }

    record NonCompliantComponentDto(String system, String component, LocalDate since) {
    }

    record GracePeriodComponentDto(String system, String component, String comment, List<String> details,
                                   OffsetDateTime violationDetectedAt, OffsetDateTime gracePeriodEndsAt) {
    }
}
