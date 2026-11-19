package com.fangke.visitor.dispatch.repository;

import com.fangke.visitor.dispatch.model.SyncBatch;
import com.fangke.visitor.dispatch.model.SyncBatchItem;

import java.util.List;
import java.util.Optional;

public interface SyncBatchRepository {

    long insert(SyncBatch batch);

    void start(Long id);

    void finish(Long id, int total, int success, int fail, int status);

    long addItem(SyncBatchItem item);

    void updateItem(Long id, int status, String errorCode, String errorMsg);

    Optional<SyncBatch> findById(Long id);

    List<SyncBatch> list(int offset, int limit);

    long count();

    List<SyncBatchItem> items(Long batchId, int offset, int limit);

    long countItems(Long batchId);
}
