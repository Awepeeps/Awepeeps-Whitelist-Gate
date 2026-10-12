package com.awepeeps.whitelistgate.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WhitelistStateTest {
    @Test
    void fullyEnforcedRequiresWhitelistAndEnforcement() {
        assertTrue(WhitelistState.known(true, true).fullyEnforced());
        assertFalse(WhitelistState.known(true, false).fullyEnforced());
        assertFalse(WhitelistState.known(false, true).fullyEnforced());
        assertFalse(WhitelistState.unknown(true, "boom").fullyEnforced());
    }

    @Test
    void describesEnforcementForStatus() {
        assertTrue(WhitelistState.known(true, true).describeEnforcement().equals("true"));
        assertTrue(WhitelistState.known(true, false).describeEnforcement().equals("false"));
        String unknown = WhitelistState.unknown(true, "boom").describeEnforcement();
        assertTrue(unknown.startsWith("unknown"));
        assertTrue(unknown.contains("boom"));
    }

    @Test
    void unknownStateIsReportedAsNotKnown() {
        WhitelistState state = WhitelistState.unknown(true, null);

        assertFalse(state.enforcementKnown());
        assertFalse(state.enforceWhitelist());
        assertTrue(state.describeEnforcement().contains("not available"));
    }
}
