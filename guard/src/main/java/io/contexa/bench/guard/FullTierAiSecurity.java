package io.contexa.bench.guard;

import io.contexa.contexacommon.annotation.EnableAISecurity;
import io.contexa.contexacommon.security.bridge.SecurityMode;

/**
 * Activates Contexa in FULL mode (Contexa owns authentication). Imported by {@link FullTierActivation} after
 * {@link FullTierConfiguration}; not a scanned configuration.
 */
@EnableAISecurity(mode = SecurityMode.FULL)
class FullTierAiSecurity {
}
