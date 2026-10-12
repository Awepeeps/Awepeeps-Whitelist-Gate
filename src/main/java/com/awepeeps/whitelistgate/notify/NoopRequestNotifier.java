package com.awepeeps.whitelistgate.notify;

import com.awepeeps.whitelistgate.core.ApprovalRequest;
import java.util.function.Consumer;

public final class NoopRequestNotifier implements RequestNotifier {
    @Override
    public void notifyRequest(ApprovalRequest request, Consumer<String> messageIdSaved) {}

    @Override
    public void notifyOutcome(ApprovalRequest request, Consumer<String> messageIdSaved) {}

    @Override
    public void attachMessageId(String requestId, String messageId) {}

    @Override
    public void close() {}
}
