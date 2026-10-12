package com.awepeeps.whitelistgate;

import java.util.UUID;
import org.geysermc.floodgate.api.FloodgateApi;

public final class FloodgateSupport {
    public boolean isBedrock(UUID authenticatedUuid) {
        return FloodgateApi.getInstance().isFloodgateId(authenticatedUuid);
    }
}
