package io.contexa.bench.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.contexa.contexacommon.annotation.EnableAISecurity;
import io.contexa.contexacommon.security.bridge.SecurityMode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

/**
 * Stage C1: the host-owned security chain (SANDBOX mode: this application owns authentication and Contexa adds no chain of
 * its own). The guard is a local service called by the benchmark runner; what it authenticates is the run's acting
 * subject, which the run filter puts into the SecurityContext so that the {@code @Protectable} gateway and the Contexa
 * bridge can see who is calling.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${bench.guard.tier:C1}'.toUpperCase() == 'C1'")
@EnableAISecurity(mode = SecurityMode.SANDBOX)
public class GuardSecurityConfiguration {

    @Bean
    public SecurityFilterChain guardSecurityFilterChain(HttpSecurity http, GuardRuns runs, ObjectMapper json)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // Not a bean: a Filter bean would also be registered in the servlet chain, before Spring Security.
                .addFilterAfter(new GuardRunAuthenticationFilter(runs, json), SecurityContextHolderFilter.class);
        return http.build();
    }

    /** C1 sets up nothing with the engine but, in RECORD mode, the run's delegation record. */
    @Bean
    public RunLifecycle hostOwnedLifecycle(RunDelegations delegations) {
        return delegations::beginC1;
    }
}
