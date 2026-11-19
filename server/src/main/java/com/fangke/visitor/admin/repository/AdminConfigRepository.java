package com.fangke.visitor.admin.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 系统配置（sys_config）：只放"可以公开、可运行时调整"的业务参数。
 *
 * <p>口令/密钥类配置**不允许**写库（应由环境变量注入），服务层会拦截。
 */
public interface AdminConfigRepository {

    record Row(String configKey, String configValue, boolean encrypted, String remark,
               Long updatedBy, LocalDateTime updatedAt) {
    }

    List<Row> list();

    Optional<Row> find(String configKey);

    void upsert(String configKey, String configValue, String remark, Long updatedBy);
}
