package com.fangke.visitor.approval.repository;

import com.fangke.visitor.approval.model.ApprovalConfig;

public interface ApprovalConfigRepository {

    ApprovalConfig loadGlobal();

    void saveGlobal(ApprovalConfig config);
}
