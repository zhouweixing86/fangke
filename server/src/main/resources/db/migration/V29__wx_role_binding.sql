-- =====================================================================
-- C-76 微信角色绑定：管理端按角色生成二维码，小程序扫码登记手机号+姓名后授予该角色
--
-- 场景：小程序只有「访客预约 / 我的预约」；员工扫「员工」码、审批员扫「审批」码、
-- 领导扫「领导」码后，小程序多出「我的审批」入口，能按审批模板里配置的角色
-- （如「审批」初核、「领导」复核）依次处理待办。
-- =====================================================================

-- 角色绑定二维码：一个角色一个长期 token，可重新生成（旧码立即失效）
CREATE TABLE wx_role_qr (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    role_id    BIGINT UNSIGNED NOT NULL,
    role_code  VARCHAR(64) NOT NULL COMMENT '冗余角色编码，任务匹配用',
    token      CHAR(32) NOT NULL COMMENT '二维码里的绑定令牌',
    status     TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0已作废',
    created_by BIGINT UNSIGNED NULL COMMENT '生成人（sys_user）',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_role (role_id),
    UNIQUE KEY uk_token (token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='微信角色绑定二维码';

-- 微信账号绑定：一个微信（openid）一条，可绑多个角色（多次扫码）
CREATE TABLE wx_binding (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    openid     VARCHAR(64) NOT NULL,
    name       VARCHAR(64) NOT NULL,
    phone_enc  VARBINARY(255) NOT NULL COMMENT '手机号密文',
    phone_mask VARCHAR(20) NOT NULL,
    phone_hmac CHAR(64) NOT NULL COMMENT '手机号 HMAC，用于匹配员工档案',
    employee_id BIGINT UNSIGNED NULL COMMENT '手机号命中员工档案时关联',
    status     TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_openid (openid),
    KEY idx_phone_hmac (phone_hmac)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='微信账号与人员绑定';

CREATE TABLE wx_binding_role (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    binding_id  BIGINT UNSIGNED NOT NULL,
    role_id     BIGINT UNSIGNED NOT NULL,
    role_code   VARCHAR(64) NOT NULL,
    source_qr   BIGINT UNSIGNED NULL COMMENT '扫码来源 wx_role_qr.id',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_binding_role (binding_id, role_id),
    KEY idx_binding (binding_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='微信账号已授予的角色';

-- 审批轨迹：微信审批人留姓名，避免列表只显示一串 id
ALTER TABLE approval_action
    ADD COLUMN operator_name VARCHAR(64) NULL COMMENT '操作人姓名（微信审批人）' AFTER operator_id;
