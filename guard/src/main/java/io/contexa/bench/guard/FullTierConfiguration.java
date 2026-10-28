package io.contexa.bench.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.contexa.contexacommon.repository.UserRepository;
import io.contexa.contexacore.autonomous.agent.AgentRegistry;
import io.contexa.contexaiam.security.xacml.pep.CustomDynamicAuthorizationManager;
import io.contexa.contexaidentity.security.core.adapter.state.oauth2.OAuth2RequestScope;
import io.contexa.contexaidentity.security.core.config.PlatformConfig;
import io.contexa.contexaidentity.security.core.dsl.IdentityDslRegistry;
import io.contexa.contexaidentity.security.token.service.TokenService;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

import java.util.EnumSet;

/**
 * Stages C2 and C3: Contexa owns authentication (CONTEXA_OWNED) and the engine's own areas are on. The agent's call is
 * authenticated by the access token of the engine's authorization server (the person's token exchanged by the agent: the
 * {@code act} chain), read by the engine's resource server, the bridge and the zero trust filters; the agent gate (call
 * limit, confirmation, revoked delegation, narrowed scope) and the gateway decide it. The guard keeps no authentication of
 * its own beside it.
 * <p>
 * The flow takes token requests only ({@link OAuth2RequestScope#TOKEN_REQUESTS}): bearer tokens and the authorization
 * server's endpoints. The guard's own control endpoints ({@code /guard/begin}, {@code /guard/end}, ...) are no token
 * requests and are served by the application, on the local address.
 * <p>
 * Not a scanned configuration: {@link FullTierActivation} imports it before Contexa's own, so that the application's
 * {@code PlatformConfig} exists when Contexa decides whether to define a default one.
 */
public class FullTierConfiguration {

    @Bean
    public PlatformConfig platformDslConfig(ApplicationContext applicationContext,
                                            CustomDynamicAuthorizationManager authorizationManager) throws Exception {
        return new IdentityDslRegistry<HttpSecurity>(applicationContext)
                .global(http -> {
                    // Reached only by the benchmark runner over the local address, never by a browser.
                    http.csrf(AbstractHttpConfigurer::disable);
                    http.authorizeHttpRequests(requests -> requests.anyRequest().access(authorizationManager));
                })
                .rest(Customizer.withDefaults())
                .oauth2(oauth2 -> oauth2.requestScope(OAuth2RequestScope.TOKEN_REQUESTS))
                .build();
    }

    @Bean
    public RunPersons runPersons(UserRepository users, PasswordEncoder passwordEncoder) {
        return new RunPersons(users, passwordEncoder);
    }

    @Bean
    public SuiteAgents suiteAgents(RegisteredClientRepository clients, AgentRegistry registry,
                                   PasswordEncoder passwordEncoder, GuardProperties properties) {
        return new SuiteAgents(clients, registry, passwordEncoder, properties);
    }

    @Bean
    public DelegationTokens delegationTokens(TokenService tokenService, AuthorizationServerSettings serverSettings,
                                             ObjectMapper json, @Value("${server.port:19300}") int port) {
        return new DelegationTokens(tokenService, serverSettings, "http://127.0.0.1:" + port, json);
    }

    @Bean
    public RunLifecycle fullLifecycle(RunPersons persons, SuiteAgents agents, RunDelegations delegations,
                                      DelegationTokens tokens) {
        return new FullLifecycle(persons, agents, delegations, tokens);
    }

    /** The host's company-record comparison, beside the engine's own providers. */
    @Bean
    public BenchRecordsContextProvider benchRecordsContextProvider(GuardRuns runs, CallContexts calls) {
        return new BenchRecordsContextProvider(runs, calls);
    }

    /**
     * Before Spring Security (whose filter is ordered at -100): the call is presented to the chain as the agent's request
     * to the tool's endpoint with its bearer token.
     */
    @Bean
    public FilterRegistrationBean<GuardCallFilter> guardCallFilter(GuardRuns runs, ToolTable tools, CallContexts calls,
                                                                   DecisionTraces traces, ObjectMapper json) {
        FilterRegistrationBean<GuardCallFilter> registration =
                new FilterRegistrationBean<>(new GuardCallFilter(runs, tools, calls, traces, json));
        registration.setName("guardCallFilter");
        registration.addUrlPatterns(GuardCallFilter.DECIDE_PATH);
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        registration.setOrder(-200);
        return registration;
    }
}
