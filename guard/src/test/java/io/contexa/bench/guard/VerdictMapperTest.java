package io.contexa.bench.guard;

import io.contexa.contexacore.autonomous.execution.RapidProtectableReentryDeniedException;
import io.contexa.contexacore.autonomous.execution.ZeroTrustAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authorization.AuthorizationDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

class VerdictMapperTest {

    @Test
    void aReturnedCallIsAllow() {
        VerdictMapper.Outcome outcome = VerdictMapper.allowed();
        assertThat(outcome.action()).isEqualTo("ALLOW");
        assertThat(outcome.allowed()).isTrue();
        assertThat(outcome.unresolved()).isFalse();
    }

    @Test
    void theEnginesVerdictsAreAnsweredAsTheyAre() {
        assertThat(VerdictMapper.of(ZeroTrustAccessDeniedException.blocked("r")).action()).isEqualTo("BLOCK");
        assertThat(VerdictMapper.of(ZeroTrustAccessDeniedException.challengeRequired("r")).action()).isEqualTo("CHALLENGE");
        assertThat(VerdictMapper.of(ZeroTrustAccessDeniedException.pendingReview("r")).action()).isEqualTo("ESCALATE");
        assertThat(VerdictMapper.of(ZeroTrustAccessDeniedException.blocked("r")).unresolved()).isFalse();
    }

    @Test
    void aRefusalWithoutAVerdictFailsClosedAndIsMarkedUnresolved() {
        for (AuthorizationDeniedException refusal : new AuthorizationDeniedException[]{
                ZeroTrustAccessDeniedException.analysisRequired("r"),
                ZeroTrustAccessDeniedException.analysisTimeout("r", 3000),
                ZeroTrustAccessDeniedException.analysisBusy("r", 5),
                new RapidProtectableReentryDeniedException("r", 5)}) {
            VerdictMapper.Outcome outcome = VerdictMapper.of(refusal);
            assertThat(outcome.action()).isEqualTo("BLOCK");
            assertThat(outcome.unresolved()).isTrue();
            assertThat(outcome.engineAction()).isNotEqualTo("BLOCK");
        }
    }

    @Test
    void aMethodPolicyDenialIsABlockOfItsOwnKind() {
        VerdictMapper.Outcome outcome = VerdictMapper.of(new AuthorizationDeniedException("Access is denied by method policies"));
        assertThat(outcome.action()).isEqualTo("BLOCK");
        assertThat(outcome.engineAction()).isEqualTo("POLICY_DENIED");
        assertThat(outcome.unresolved()).isFalse();
    }
}
