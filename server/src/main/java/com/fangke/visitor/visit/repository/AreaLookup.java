package com.fangke.visitor.visit.repository;

import java.util.Optional;

/**
 * 区域查询端口（C-75）：预约/审批需要「区域是否有效 + 名称」，但不需要来访登记整套能力。
 */
public interface AreaLookup {

    /** 启用的区域名称；区域不存在或已停用返回 empty。 */
    Optional<String> areaName(Long areaId);
}
