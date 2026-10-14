package com.aicontent.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class LocalVerifyGuardTest {

    private static final List<String> PROFILES = List.of("local-verify");

    @Test
    void loopbackWithoutPaidAiIsAccepted() {
        for (String address : List.of("127.0.0.1", "::1", "localhost")) {
            assertThat(LocalVerifyGuard.problems(PROFILES, address, "stub", "")).isEmpty();
        }
        assertThat(LocalVerifyGuard.problems(PROFILES, "127.0.0.1", "none", null)).isEmpty();
    }

    @Test
    void theProfileMustBeActiveAndLiveMustNot() {
        assertThat(LocalVerifyGuard.problems(List.of("test"), "127.0.0.1", "stub", "")).hasSize(1);
        assertThat(LocalVerifyGuard.problems(List.of("local-verify", "live"), "127.0.0.1", "stub", "")).hasSize(1);
    }

    @Test
    void everyNonLoopbackOrMissingAddressIsRefused() {
        for (String address : new String[] {null, "", "0.0.0.0", "::", "192.168.0.10", "example.test"}) {
            assertThat(LocalVerifyGuard.problems(PROFILES, address, "stub", "")).as(String.valueOf(address)).hasSize(1);
        }
    }

    @Test
    void aRealAiProviderOrAnApiKeyIsRefusedWhateverSetIt() {
        assertThat(LocalVerifyGuard.problems(PROFILES, "127.0.0.1", "openai", "")).hasSize(1);
        assertThat(LocalVerifyGuard.problems(PROFILES, "127.0.0.1", "OpenAI", "")).hasSize(1);
        assertThat(LocalVerifyGuard.problems(PROFILES, "127.0.0.1", "something-new", "")).hasSize(1);
        assertThat(LocalVerifyGuard.problems(PROFILES, "127.0.0.1", "stub", "not-a-real-key")).hasSize(1);
    }
}
