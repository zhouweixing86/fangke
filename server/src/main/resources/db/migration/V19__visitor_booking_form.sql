-- 访客预约表单（对照江苏院小程序）：来访公司/职务、上午下午时段、微信一键登录 openid
-- 背景：原表单用「开始/结束时间」，访客端改为「来访日期 + 全天/上午/下午」，由后端换算成时段。

ALTER TABLE appointment
    ADD COLUMN company VARCHAR(128) NULL COMMENT '来访公司（团体为负责人公司）' AFTER purpose,
    ADD COLUMN position VARCHAR(64) NULL COMMENT '来访人职务' AFTER company,
    ADD COLUMN period TINYINT NOT NULL DEFAULT 1 COMMENT '时段：1 全天 / 2 上午 / 3 下午' AFTER position;

ALTER TABLE visitor
    ADD COLUMN position VARCHAR(64) NULL COMMENT '职务' AFTER company,
    ADD COLUMN wx_openid VARCHAR(64) NULL COMMENT '微信小程序 openid（一键登录）' AFTER position,
    ADD UNIQUE KEY uk_visitor_wx_openid (wx_openid);
