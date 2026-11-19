package com.explore.opsagent.gateway;

/** Bearer token for calls to the gateway. */
public interface GatewayTokenProvider {

    String token();

    /** Drops any cached token (the gateway answered 401), so the next call fetches a new one. */
    void invalidate();
}
