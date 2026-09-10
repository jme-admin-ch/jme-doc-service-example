package ch.admin.bit.jeap.jme.doc.stub;

import ch.admin.bit.jeap.security.resource.token.AuthoritiesResolver;
import ch.admin.bit.jeap.security.resource.token.JeapAuthenticationConverter;
import ch.admin.bit.jeap.security.resource.validation.JeapJwtDecoderFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The stub authenticates its callers, which is most of the reason it is a service rather than a directory of
 * files behind a web server.
 * <p>
 * An upstream that answered anybody would prove that the doc service knows a URL. This one requires a bearer
 * token the OAuth mock server signed, carrying the semantic role the real architecture repository requires -
 * so what the example exercises is the whole client registration: the token endpoint, the credentials and the
 * role. That is the half of an upstream integration which actually breaks on a real stage.
 * <p>
 * The chains mirror the doc service's own: the API authenticated with a bearer token and without CSRF
 * protection, because its caller holds a token and no cookie; the actuator open, because a test that waits for
 * this service has to be able to ask whether it is up.
 */
@Configuration
@EnableMethodSecurity
public class StubSecurityConfiguration {

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 20)
    SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/actuator/**")
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 15)
    SecurityFilterChain docsApiSecurityFilterChain(HttpSecurity http, JeapJwtDecoderFactory jwtDecoders,
                                                   AuthoritiesResolver authorities) throws Exception {
        return http
                .securityMatcher("/docs-api/**")
                .authorizeHttpRequests(requests -> requests.anyRequest().fullyAuthenticated())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> jwt
                        .decoder(jwtDecoders.createJwtDecoder())
                        .jwtAuthenticationConverter(new JeapAuthenticationConverter(authorities))))
                .build();
    }
}
