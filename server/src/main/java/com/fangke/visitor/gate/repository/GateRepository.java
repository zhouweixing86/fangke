package com.fangke.visitor.gate.repository;

import com.fangke.visitor.gate.model.Gate;

import java.util.List;
import java.util.Optional;

/**
 * 门点仓储。抽成接口让门点相关服务可以脱离数据库做单测。
 */
public interface GateRepository {

    List<Gate> findAll();

    Optional<Gate> findById(Long id);

    Optional<Gate> findByCode(String gateCode);

    long insert(Gate gate);

    /** 只更新名称、类型、排序、备注（编码建好不再改，避免历史数据对不上）。 */
    void update(Gate gate);

    void updateStatus(Long id, int status);

    /** 编码唯一校验；excludeId 用于编辑自身时排除。 */
    boolean existsCode(String gateCode, Long excludeId);
}
