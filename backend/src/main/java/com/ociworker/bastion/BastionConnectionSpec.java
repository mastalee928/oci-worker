package com.ociworker.bastion;

/**
 * One short-lived Bastion connection lease. Secrets are intentionally kept in
 * this server-side object and are never returned by the prepare endpoint.
 */
record BastionConnectionSpec(
        String tenantId,
        String region,
        String sessionId,
        String bastionHost,
        int bastionPort,
        String bastionUser,
        String bastionPrivateKey,
        String targetHost,
        int targetPort,
        String targetUsername,
        int targetLoginType,
        String targetPassword,
        String targetPrivateKey,
        String targetPassphrase,
        String targetDisplayName,
        long expiresAtMillis,
        boolean managedSsh,
        String bastionHostKeyInfo) {

    /**
     * Kept for the original package-level tests and for callers that construct a
     * lease without OCI host-key metadata. New leases always use the full
     * constructor so the host key is pinned before opening JSch.
     */
    BastionConnectionSpec(
            String tenantId,
            String region,
            String sessionId,
            String bastionHost,
            int bastionPort,
            String bastionUser,
            String bastionPrivateKey,
            String targetHost,
            int targetPort,
            String targetUsername,
            int targetLoginType,
            String targetPassword,
            String targetPrivateKey,
            String targetPassphrase,
            String targetDisplayName,
            long expiresAtMillis) {
        this(tenantId, region, sessionId, bastionHost, bastionPort, bastionUser,
                bastionPrivateKey, targetHost, targetPort, targetUsername,
                targetLoginType, targetPassword, targetPrivateKey, targetPassphrase,
                targetDisplayName, expiresAtMillis, false, null);
    }
}
