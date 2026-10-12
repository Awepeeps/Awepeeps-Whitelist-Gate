package com.awepeeps.whitelistgate.storage;

import com.awepeeps.whitelistgate.core.ApprovalRequest;
import java.io.IOException;
import java.util.List;

public interface RequestStore {
    List<ApprovalRequest> load() throws IOException;

    void save(List<ApprovalRequest> requests) throws IOException;
}
