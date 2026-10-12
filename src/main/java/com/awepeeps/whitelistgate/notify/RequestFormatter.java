package com.awepeeps.whitelistgate.notify;

import com.awepeeps.whitelistgate.core.ApprovalRequest;
import com.awepeeps.whitelistgate.core.RequestState;
import com.awepeeps.whitelistgate.core.TextSafety;

public final class RequestFormatter {
    private RequestFormatter() {}

    public static String format(ApprovalRequest request) {
        StringBuilder text = new StringBuilder();
        if (request.state() == RequestState.PENDING) {
            text.append("Whitelist request **").append(request.requestId()).append("**\n")
                    .append("Name: ").append(TextSafety.discord(request.name())).append('\n')
                    .append("Platform: ").append(request.platform()).append('\n')
                    .append("UUID: `").append(request.uuid()).append("`\n")
                    .append("Attempts: ").append(request.attempts()).append('\n')
                    .append("Approve: `/wlgate approve ").append(request.requestId()).append("`\n")
                    .append("Deny: `/wlgate deny ").append(request.requestId()).append('`');
        } else {
            text.append("Whitelist request **").append(request.requestId()).append("**: ")
                    .append(request.state());
            if (request.actor() != null) {
                text.append(" by ").append(TextSafety.discord(request.actor()));
            }
        }
        return text.toString();
    }
}
