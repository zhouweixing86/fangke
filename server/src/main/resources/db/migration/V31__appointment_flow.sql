-- C-82 审批流程快照：提交时锁定流程，之后改模板不影响在途单
CREATE TABLE appointment_flow (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    appointment_id BIGINT UNSIGNED NOT NULL,
    seq            INT NOT NULL,
    node_name      VARCHAR(64) NOT NULL,
    assignee_role  VARCHAR(32) NOT NULL,
    allow_edit_gates TINYINT NOT NULL DEFAULT 1,
    allow_qr       TINYINT NOT NULL DEFAULT 1,
    cc_roles       VARCHAR(255) NULL COMMENT '节点办结后抄送角色，逗号分隔',
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_appt_seq (appointment_id, seq),
    KEY idx_appt (appointment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约审批流程快照（提交时锁定）';
