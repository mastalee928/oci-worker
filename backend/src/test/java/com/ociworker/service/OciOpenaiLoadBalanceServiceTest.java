package com.ociworker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OciOpenaiLoadBalanceServiceTest {

    @Test
    void plainHttpStatusIsNotModelAvailabilityFailure() {
        assertThat(OciOpenaiLoadBalanceService.isModelAvailabilityFailure(400, "HTTP 400")).isFalse();
        assertThat(OciOpenaiLoadBalanceService.isModelAvailabilityFailure(404, "HTTP 404")).isFalse();
        assertThat(OciOpenaiLoadBalanceService.isModelAvailabilityFailure(422, "")).isFalse();
    }

    @Test
    void explicitModelAvailabilityErrorsAreDetected() {
        assertThat(OciOpenaiLoadBalanceService.isModelAvailabilityFailure(
                400,
                "{\"error\":{\"message\":\"model xai.grok-4.3 not found\"}}"))
                .isTrue();
        assertThat(OciOpenaiLoadBalanceService.isModelAvailabilityFailure(
                403,
                "Model xai.grok-4.3 is not allowed for this account"))
                .isTrue();
        assertThat(OciOpenaiLoadBalanceService.isModelAvailabilityFailure(
                404,
                "The requested model does not exist"))
                .isTrue();
    }

    @Test
    void unrelatedModelMentionIsNotAvailabilityFailure() {
        assertThat(OciOpenaiLoadBalanceService.isModelAvailabilityFailure(
                400,
                "Failed to deserialize ModelInput payload"))
                .isFalse();
        assertThat(OciOpenaiLoadBalanceService.isModelAvailabilityFailure(
                400,
                "The model generated invalid tool arguments"))
                .isFalse();
    }
}
