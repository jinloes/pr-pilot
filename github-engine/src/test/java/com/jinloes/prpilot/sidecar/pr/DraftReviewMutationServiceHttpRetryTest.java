package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DraftReviewMutationServiceHttpRetryTest {
    @Nested
    class IsRetryableHttpMethod {
        @Test
        void retriesOnlyIdempotentHttpMethods() {
            assertThat(DraftReviewMutationService.isRetryableHttpMethod("GET")).isTrue();
            assertThat(DraftReviewMutationService.isRetryableHttpMethod("PUT")).isTrue();
            assertThat(DraftReviewMutationService.isRetryableHttpMethod("DELETE")).isTrue();
            assertThat(DraftReviewMutationService.isRetryableHttpMethod("POST")).isFalse();
        }
    }
}
