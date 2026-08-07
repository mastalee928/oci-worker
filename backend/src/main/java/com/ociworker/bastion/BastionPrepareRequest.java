package com.ociworker.bastion;

/** Request body for the one-shot Bastion preparation endpoint. */
public record BastionPrepareRequest(
        String id,
        String instanceId,
        String region,
        String compartmentId,
        String loginMode,
        String username,
        String password,
        String privateKey,
        String passphrase) {
}
