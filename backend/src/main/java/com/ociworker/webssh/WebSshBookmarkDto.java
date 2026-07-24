package com.ociworker.webssh;

import java.util.List;

/** JSON contract for the persistent WebSSH bookmark API. */
public final class WebSshBookmarkDto {

    private WebSshBookmarkDto() {
    }

    public record ConnectionInput(String hostname, Integer port, String username, String authType) {
    }

    public record ScriptInput(String name, String cmd) {
    }

    public record MigrationRequest(List<ConnectionInput> connections, List<ScriptInput> scripts) {
    }

    public record ConnectionView(String id, String hostname, int port, String username, String authType) {
    }

    public record ScriptView(String id, String name, String cmd) {
    }

    public record Bundle(List<ConnectionView> connections, List<ScriptView> scripts) {
    }
}
