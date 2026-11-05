package ch.admin.bit.jeap.jme.doc.stub.governance;

import ch.admin.bit.jeap.jme.doc.stub.http.ConditionalAnswers;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The documentation API of the Governance Service: an index of the systems and one of the rules, and a
 * resource for each entry.
 * <p>
 * <b>It is served below {@code /governance}</b>, because the architecture repository stub already answers
 * {@code /docs-api} at the root of this service. The doc service appends the paths it asks for to the URL it is
 * configured with, so the prefix is part of that URL and nothing else changes.
 * <p>
 * <b>An index carries the tag of each resource it lists</b>, equal to the {@code ETag} that resource answers
 * with, so the doc service fetches only what moved. The role is the real one:
 * {@code hasRole('governance-documentation', 'read')}.
 */
@RestController
@RequestMapping(GovernanceStubController.ROOT)
@PreAuthorize("hasRole('governance-documentation', 'read')")
class GovernanceStubController {

    static final String ROOT = "/governance/docs-api";
    private static final String SYSTEMS = "/systems";
    private static final String RULES = "/rules";

    private final GovernanceReports reports;
    private final ConditionalAnswers answers;

    GovernanceStubController(GovernanceReports reports, ConditionalAnswers answers) {
        this.reports = reports;
        this.answers = answers;
    }

    @GetMapping(path = SYSTEMS, produces = "application/json")
    ResponseEntity<GovernanceDtos.SystemIndexDto> systemIndex(
            HttpServletRequest request,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        List<GovernanceDtos.SystemIndexEntryDto> entries = reports.systems(reports.evaluatedAt()).stream()
                .map(system -> new GovernanceDtos.SystemIndexEntryDto(system.name(), system.score(),
                        system.trend(), answers.tagOf(system), pathOf(request, SYSTEMS, system.name())))
                .toList();
        return answers.answer(new GovernanceDtos.SystemIndexDto(reports.scoredAt(), reports.trendPeriodDays(),
                entries),
                ifNoneMatch);
    }

    @GetMapping(path = SYSTEMS + "/{system}", produces = "application/json")
    ResponseEntity<GovernanceDtos.SystemDto> system(
            @PathVariable String system,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        return answers.answer(reports.system(system, reports.evaluatedAt())
                .orElseThrow(() -> notFound("system", system)), ifNoneMatch);
    }

    @GetMapping(path = RULES, produces = "application/json")
    ResponseEntity<GovernanceDtos.RuleIndexDto> ruleIndex(
            HttpServletRequest request,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        List<GovernanceDtos.RuleIndexEntryDto> entries = reports.rules(reports.evaluatedAt()).stream()
                .map(rule -> new GovernanceDtos.RuleIndexEntryDto(rule.ruleId(), rule.name(), rule.active(),
                        rule.weight(), rule.share(), rule.conformanceRate(), rule.trend(),
                        rule.active() ? exemptionsInForce(rule) : null, answers.tagOf(rule),
                        pathOf(request, RULES, rule.ruleId())))
                .toList();
        return answers.answer(new GovernanceDtos.RuleIndexDto(reports.scoredAt(), entries), ifNoneMatch);
    }

    @GetMapping(path = RULES + "/{ruleId}", produces = "application/json")
    ResponseEntity<GovernanceDtos.RuleDto> rule(
            @PathVariable String ruleId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        return answers.answer(reports.rule(ruleId, reports.evaluatedAt())
                .orElseThrow(() -> notFound("rule", ruleId)), ifNoneMatch);
    }

    private static int exemptionsInForce(GovernanceDtos.RuleDto rule) {
        return (int) rule.exemptions().stream()
                .filter(exemption -> exemption.effect() != GovernanceDtos.ExemptionEffect.EXPIRED)
                .count();
    }

    /** Where a resource is served, with the context path, as the Governance Service's own index names it. */
    private static String pathOf(HttpServletRequest request, String resource, String name) {
        return request.getContextPath() + ROOT + resource + "/"
               + UriUtils.encodePathSegment(name, StandardCharsets.UTF_8);
    }

    private static ResponseStatusException notFound(String kind, String name) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
                "The Governance Service knows no %s %s.".formatted(kind, name));
    }
}
