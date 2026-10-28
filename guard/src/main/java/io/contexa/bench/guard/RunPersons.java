package io.contexa.bench.guard;

import io.contexa.contexacommon.entity.Users;
import io.contexa.contexacommon.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The engine account of a run's subject (C2): the person who handed the task to the agent. The authorization server
 * issues the person's token for an account of the engine's user table and the resource server finds the person by it, so
 * the subject of a run is a real account with a password nobody knows (it is random and not kept).
 */
public class RunPersons {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public RunPersons(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    public void create(GuardRuns.Run run) {
        byte[] secret = new byte[24];
        RANDOM.nextBytes(secret);
        users.save(Users.builder()
                .username(run.principal())
                .email(run.principal() + "@bench.guard.local")
                .password(passwordEncoder.encode(HexFormat.of().formatHex(secret)))
                .name("Benchmark user of run " + run.runId())
                .department("benchmark")
                .position("benchmark-user")
                .locale("en")
                .timezone("UTC")
                .build());
    }
}
