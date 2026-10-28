package io.contexa.bench.guard;

import io.contexa.contexacore.autonomous.agent.AgentRegistrationRequest;
import io.contexa.contexacore.autonomous.agent.AgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The benchmark's agents, one fixed agent per suite (C2): registered the way an application registers its agents with
 * Contexa, as the agent's OAuth2 client in the authorization server's client repository (client credentials for its own
 * token, token exchange for a token acting for a person) and the agent itself in Contexa's agent registry, approved and
 * with the person accountable for it. The client's secret is random per start of this application.
 */
public class SuiteAgents {

    private static final Logger log = LoggerFactory.getLogger(SuiteAgents.class);

    static final String AGENT_TYPE = "BENCHMARK_AGENT";
    /** The scope the agent's client may receive: the internal client's default scope, which the person's token has. */
    static final String SCOPE = "read";
    /** How long an agent's access token lives; a run is shorter. */
    static final Duration TOKEN_LIFETIME = Duration.ofMinutes(30);

    /** An agent of a suite and the credentials of its client. */
    public record Agent(String agentId, String clientId, String clientSecret) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RegisteredClientRepository clients;
    private final AgentRegistry registry;
    private final PasswordEncoder passwordEncoder;
    private final GuardProperties properties;
    private final Map<String, Agent> agents = new ConcurrentHashMap<>();

    public SuiteAgents(RegisteredClientRepository clients, AgentRegistry registry, PasswordEncoder passwordEncoder,
                       GuardProperties properties) {
        this.clients = clients;
        this.registry = registry;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    public Agent ensure(String agentId, String suite) {
        return agents.computeIfAbsent(agentId, id -> register(id, suite));
    }

    private Agent register(String agentId, String suite) {
        String clientId = agentId + "-client";
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String secret = HexFormat.of().formatHex(bytes);
        RegisteredClient existing = clients.findByClientId(clientId);
        RegisteredClient.Builder builder = existing != null
                ? RegisteredClient.from(existing)
                : RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE)
                .scope(SCOPE);
        clients.save(builder
                .clientName("Benchmark agent " + agentId)
                .clientSecret(passwordEncoder.encode(secret))
                .clientSettings(ClientSettings.builder().requireAuthorizationConsent(false).build())
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(TOKEN_LIFETIME).build())
                .build());
        registry.register(new AgentRegistrationRequest(null, agentId,
                "Benchmark agent of the " + (suite == null || suite.isBlank() ? "default" : suite) + " suite",
                AGENT_TYPE, clientId, properties.delegation().sponsor(), true));
        log.info("[guard] agent {} registered with client {} (sponsor {})", agentId, clientId,
                properties.delegation().sponsor());
        return new Agent(agentId, clientId, secret);
    }

    public int registered() {
        return agents.size();
    }
}
