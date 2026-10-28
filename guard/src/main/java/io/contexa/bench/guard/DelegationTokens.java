package io.contexa.bench.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contexa.contexaidentity.security.token.dto.TokenPair;
import io.contexa.contexaidentity.security.token.service.TokenService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/**
 * The agent's delegated access token (C2), obtained the way an agent obtains it from Contexa's authorization server:
 * the person's access token (issued by the engine's internal client for the person's account), the agent's own client
 * credentials token as the actor, and the RFC 8693 token exchange of the first for a token that names the person as the
 * subject and the agent's client as the actor ({@code act}) and, when the person's delegation to the agent holds,
 * names that delegation ({@code delegation_id}). The exchange goes to the authorization server's token endpoint over HTTP.
 */
public class DelegationTokens {

    private static final String TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";

    private final TokenService tokenService;
    private final AuthorizationServerSettings serverSettings;
    private final RestClient authorizationServer;
    private final ObjectMapper json;

    public DelegationTokens(TokenService tokenService, AuthorizationServerSettings serverSettings,
                            String authorizationServerUrl, ObjectMapper json) {
        this.tokenService = tokenService;
        this.serverSettings = serverSettings;
        this.authorizationServer = RestClient.builder().baseUrl(authorizationServerUrl).build();
        this.json = json;
    }

    /** The access token of the agent acting for the run's subject. */
    public String issue(GuardRuns.Run run, SuiteAgents.Agent agent) {
        Authentication person = UsernamePasswordAuthenticationToken.authenticated(run.principal(), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        TokenPair personToken = personToken(person);

        MultiValueMap<String, String> actorRequest = new LinkedMultiValueMap<>();
        actorRequest.add("grant_type", "client_credentials");
        actorRequest.add("scope", SuiteAgents.SCOPE);
        String actorToken = accessToken(post(agent, actorRequest, "the agent's own token"));

        MultiValueMap<String, String> exchange = new LinkedMultiValueMap<>();
        exchange.add("grant_type", TOKEN_EXCHANGE);
        exchange.add("subject_token", personToken.getAccessToken());
        exchange.add("subject_token_type", ACCESS_TOKEN_TYPE);
        exchange.add("actor_token", actorToken);
        exchange.add("actor_token_type", ACCESS_TOKEN_TYPE);
        exchange.add("scope", SuiteAgents.SCOPE);
        return accessToken(post(agent, exchange, "the token exchange"));
    }

    /**
     * The person's token from the engine's internal token engine, which issues it in process. The engine issues it inside
     * a request of the authorization server's own chain (a sign-in), where the server's context is set; the guard asks
     * outside such a request, so the context is set here for the call.
     */
    private TokenPair personToken(Authentication person) {
        AuthorizationServerContextHolder.setContext(new AuthorizationServerContext() {
            @Override
            public String getIssuer() {
                return serverSettings.getIssuer();
            }

            @Override
            public AuthorizationServerSettings getAuthorizationServerSettings() {
                return serverSettings;
            }
        });
        try {
            return tokenService.createTokenPair(person, null);
        } finally {
            AuthorizationServerContextHolder.resetContext();
        }
    }

    private String post(SuiteAgents.Agent agent, MultiValueMap<String, String> form, String what) {
        try {
            return authorizationServer.post().uri("/oauth2/token")
                    .headers(headers -> headers.setBasicAuth(agent.clientId(), agent.clientSecret()))
                    .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException refused) {
            throw new IllegalStateException("The authorization server refused " + what + " (HTTP "
                    + refused.getStatusCode().value() + "): " + refused.getResponseBodyAsString(), refused);
        }
    }

    private String accessToken(String body) {
        try {
            JsonNode token = json.readTree(body).path("access_token");
            if (token.isMissingNode() || token.asText().isBlank()) {
                throw new IllegalStateException("The authorization server's answer holds no access token");
            }
            return token.asText();
        } catch (java.io.IOException unreadable) {
            throw new IllegalStateException("The authorization server's answer could not be read", unreadable);
        }
    }
}
