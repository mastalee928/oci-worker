package com.ociworker.service;

import com.ociworker.model.entity.OciOpenaiLbMember;
import com.ociworker.model.entity.OciOpenaiPortBinding;
import com.ociworker.model.entity.OciUser;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

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

    @Test
    void unsupportedRequestParameterIsNotModelAvailabilityFailure() {
        assertThat(OciOpenaiLoadBalanceService.isModelAvailabilityFailure(
                400,
                "{\"code\":\"invalid-argument\",\"error\":\"Model grok-4.20-0309-reasoning does not support parameter reasoningEffort.\"}"))
                .isFalse();
        assertThat(OciOpenaiLoadBalanceService.isModelAvailabilityFailure(
                400,
                "{\"code\":\"invalid-argument\",\"error\":\"This model does not support parameter reasoning_effort.\"}"))
                .isFalse();
    }

    @Test
    void endpointCapabilityMismatchIsNotModelAvailabilityFailure() {
        assertThat(OciOpenaiLoadBalanceService.isModelAvailabilityFailure(
                400,
                "Model cohere.embed-v4.0 does not support chat/completions endpoint"))
                .isFalse();
        assertThat(OciOpenaiLoadBalanceService.isModelAvailabilityFailure(
                400,
                "Model xai.grok-voice-agent is unsupported for this API operation"))
                .isFalse();
    }

    @Test
    void loadBalanceMemberSelectorMatchesCommonAccountFields() {
        OciOpenaiLbMember member = new OciOpenaiLbMember();
        member.setId("member-1");
        member.setPortBindingId("binding-1");
        OciOpenaiPortBinding binding = new OciOpenaiPortBinding();
        binding.setId("binding-1");
        binding.setName("tokyo-main");
        binding.setPort(30001);
        binding.setOciUserId("tenant-row-1");
        OciUser user = new OciUser();
        user.setId("tenant-row-1");
        user.setUsername("friend-a");
        user.setTenantName("Friend Tenant");

        assertThat(OciOpenaiLoadBalanceService.memberMatchesRequestedAccount(member, binding, user, "member-1")).isTrue();
        assertThat(OciOpenaiLoadBalanceService.memberMatchesRequestedAccount(member, binding, user, "30001")).isTrue();
        assertThat(OciOpenaiLoadBalanceService.memberMatchesRequestedAccount(member, binding, user, "friend tenant")).isTrue();
        assertThat(OciOpenaiLoadBalanceService.memberMatchesRequestedAccount(member, binding, user, "other")).isFalse();
    }

    @Test
    void staleTransientMemberErrorRequiresExpiredFailureOutsideCooldown() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 7, 13, 0);
        OciOpenaiLbMember stale = failedMember(now.minusDays(3));
        assertThat(OciOpenaiLoadBalanceService.isStaleTransientMemberError(stale, now, 6)).isTrue();

        OciOpenaiLbMember recent = failedMember(now.minusHours(2));
        assertThat(OciOpenaiLoadBalanceService.isStaleTransientMemberError(recent, now, 6)).isFalse();

        OciOpenaiLbMember cooling = failedMember(now.minusDays(3));
        cooling.setCooldownUntil(now.plusMinutes(5));
        assertThat(OciOpenaiLoadBalanceService.isStaleTransientMemberError(cooling, now, 6)).isFalse();

        OciOpenaiLbMember recovering = failedMember(now.minusDays(3));
        recovering.setRecoveryUntil(now.plusMinutes(5));
        assertThat(OciOpenaiLoadBalanceService.isStaleTransientMemberError(recovering, now, 6)).isFalse();

        OciOpenaiLbMember noLastUsed = failedMember(null);
        assertThat(OciOpenaiLoadBalanceService.isStaleTransientMemberError(noLastUsed, now, 6)).isFalse();
    }

    private static OciOpenaiLbMember failedMember(LocalDateTime lastUsed) {
        OciOpenaiLbMember member = new OciOpenaiLbMember();
        member.setId("member-1");
        member.setFailCount(1);
        member.setLastUsed(lastUsed);
        member.setLastError("Gemini 原生 Chat 调用失败");
        return member;
    }
}
