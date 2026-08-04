package dev.hotreload.protocol.message;

/** An authenticated request that mutates the running application. */
public interface ReloadRequest {
    String getRequestId();
    String getToken();
}
