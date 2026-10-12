package com.awepeeps.whitelistgate.notify;

import com.awepeeps.whitelistgate.core.ApprovalRequest;
import java.util.function.Consumer;

public interface RequestNotifier extends AutoCloseable {
    void notifyRequest(ApprovalRequest request, Consumer<String> messageIdSaved);

    void notifyOutcome(ApprovalRequest request, Consumer<String> messageIdSaved);

    void attachMessageId(String requestId, String messageId);

    @Override
    void close();
}
