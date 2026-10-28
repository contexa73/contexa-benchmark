package io.contexa.bench.guard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The guard application of the benchmark runs: the agent of a benchmark (AgentDojo, InjecAgent) asks it before every tool
 * call whether the call may run, and the Contexa engine decides.
 * <p>
 * The stage ({@code bench.guard.tier}, environment {@code BENCH_TIER}) selects the configuration: C1 is host-owned
 * ({@link GuardSecurityConfiguration}), C2 and C3 are Contexa-owned ({@link FullTierConfiguration}). As in the showcase's
 * control D, the application's own datasource (the vector store) is not Contexa's engine database.
 */
@SpringBootApplication(exclude = HibernateJpaAutoConfiguration.class)
@ConfigurationPropertiesScan(basePackageClasses = GuardApplication.class)
public class GuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(GuardApplication.class, args);
    }
}
