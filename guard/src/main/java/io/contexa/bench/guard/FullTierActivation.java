package io.contexa.bench.guard;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Stages C2 and C3: the application's configuration, then Contexa's activation.
 * <p>
 * The order of the two imports is the order of the beans: Contexa's configuration defines a default {@code PlatformConfig}
 * only when none exists yet, and a bean of the same name defined after it would be refused. (An annotation on this class
 * would be read before its own {@code @Import}, so the activation is imported through a class of its own.)
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${bench.guard.tier:C1}'.toUpperCase() != 'C1'")
@Import({FullTierConfiguration.class, FullTierAiSecurity.class})
public class FullTierActivation {
}
