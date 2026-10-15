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
    void onlyRequestsAddressedToAndComingFromThisMachineAreLocal() {
        assertThat(LocalVerifyGuard.isLocalRequest("127.0.0.1:18091", null, null)).isTrue();
        assertThat(LocalVerifyGuard.isLocalRequest("localhost", "http://127.0.0.1:13091", null)).isTrue();
        assertThat(LocalVerifyGuard.isLocalRequest("[::1]:18091", "http://localhost:13091", null)).isTrue();

        assertThat(LocalVerifyGuard.isLocalRequest(null, null, null)).isFalse();
        assertThat(LocalVerifyGuard.isLocalRequest("evil.example:18091", null, null)).isFalse();        // DNS rebinding
        assertThat(LocalVerifyGuard.isLocalRequest("127.0.0.1:18091", "https://evil.example", null)).isFalse();  // cross-site
        assertThat(LocalVerifyGuard.isLocalRequest("127.0.0.1:18091", "null", null)).isFalse();
        assertThat(LocalVerifyGuard.isLocalRequest("127.0.0.1:18091", "http://127.0.0.1.evil.example", null)).isFalse();
        assertThat(LocalVerifyGuard.isLocalRequest("127.0.0.1:18091", "::not a uri", null)).isFalse();
        // behind the admin UI proxy: the original host is in X-Forwarded-Host
        assertThat(LocalVerifyGuard.isLocalRequest("127.0.0.1", null, "127.0.0.1:13091")).isTrue();
        assertThat(LocalVerifyGuard.isLocalRequest("127.0.0.1", null, "evil.example:13091")).isFalse();
        assertThat(LocalVerifyGuard.isLocalRequest("127.0.0.1", null, "127.0.0.1:13091, evil.example")).isFalse();
    }

    @Test
    void aRealAiProviderOrAnApiKeyIsRefusedWhateverSetIt() {
        assertThat(LocalVerifyGuard.problems(PROFILES, "127.0.0.1", "openai", "")).hasSize(1);
        assertThat(LocalVerifyGuard.problems(PROFILES, "127.0.0.1", "OpenAI", "")).hasSize(1);
        assertThat(LocalVerifyGuard.problems(PROFILES, "127.0.0.1", "something-new", "")).hasSize(1);
        assertThat(LocalVerifyGuard.problems(PROFILES, "127.0.0.1", "stub", "not-a-real-key")).hasSize(1);
    }
}
