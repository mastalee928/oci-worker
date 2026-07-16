package com.ociworker.model.dto;

import java.util.Map;

public record InstancePublicIpResponse(
        Map<String, String> publicIps,
        boolean complete,
        int requested,
        int resolved) {
}
