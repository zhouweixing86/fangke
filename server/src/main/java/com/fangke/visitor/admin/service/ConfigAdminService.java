package com.fangke.visitor.admin.service;

import com.fangke.visitor.admin.repository.AdminConfigRepository;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 系统配置（docs/11 §5.9）。只允许写"业务参数"，敏感配置必须走环境变量。
 *
 * <p>白名单式校验：key 由小写字母、数字、点、下划线组成；含 password/secret/token/key
 * 等字样的 key 一律拒绝，避免把口令存进数据库（sys_config 里出现明文密码是常见事故）。
 */
@Service
public class ConfigAdminService {

    /** 允许在库里调整的配置白名单（新增项要在这里显式登记）。 */
    private static final Set<String> ALLOWED = Set.of(
            "appointment.advance-days",
            "appointment.mate-max",
            "approval.reminder-interval-min",
            "approval.reminder-max-per-task",
            "device.visitor-user-type",
            "qr.ttl-seconds",
            "retention.heartbeat-days",
            "retention.notification-days",
            "retention.job-log-days",
            "report.max-range-days",
            "notify.sms-enabled");

    private static final Set<String> FORBIDDEN_WORDS = Set.of(
            "password", "passwd", "secret", "token", "key", "credential", "aksk");

    private final AdminConfigRepository repository;

    public ConfigAdminService(AdminConfigRepository repository) {
        this.repository = repository;
    }

    public List<AdminConfigRepository.Row> list() {
        return repository.list();
    }

    @Transactional
    public void save(String configKey, String configValue, String remark, Long operatorId) {
        String key = configKey == null ? "" : configKey.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty() || !key.matches("[a-z0-9._-]{3,64}")) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "配置项名称只能是小写字母/数字/点/下划线/短横线，3~64 位");
        }
        for (String word : FORBIDDEN_WORDS) {
            if (key.contains(word)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "敏感配置（" + word + "）不允许写库，请使用环境变量注入：" + key);
            }
        }
        if (!ALLOWED.contains(key)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "配置项不在允许列表：" + key + "（允许：" + String.join("、", ALLOWED) + "）");
        }
        String value = configValue == null ? "" : configValue.trim();
        if (value.length() > 512) {
            throw new BusinessException(ErrorCode.PARAM_TOO_LONG, "配置值不能超过 512 字");
        }
        repository.upsert(key, value,
                remark == null ? null : remark.substring(0, Math.min(remark.length(), 255)), operatorId);
    }
}
