-- M12：通知中心 + 定时任务执行日志（docs/10 §10、docs/11 §5.9）
CREATE TABLE notification (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  receiver_type TINYINT NOT NULL COMMENT '1员工 2访客',
  receiver_id   BIGINT UNSIGNED NOT NULL,
  channel       TINYINT NOT NULL DEFAULT 1 COMMENT '1站内 2短信',
  template_code VARCHAR(48) NOT NULL COMMENT '模板编码，见 NotifyTemplates',
  title         VARCHAR(128) NOT NULL,
  content       VARCHAR(500) NOT NULL,
  biz_type      VARCHAR(32) NULL COMMENT 'APPOINTMENT / APPROVAL / VISIT / SYSTEM',
  biz_no        VARCHAR(64) NULL COMMENT '预约单号 / 任务号等',
  status        TINYINT NOT NULL DEFAULT 1 COMMENT '0待发 1已发 2失败',
  read_at       DATETIME(3) NULL COMMENT '站内已读时间',
  sent_at       DATETIME(3) NULL,
  retry_count   INT NOT NULL DEFAULT 0,
  error_msg     VARCHAR(255) NULL,
  dedup_key     VARCHAR(160) NOT NULL COMMENT '去重键：模板+接收人+业务号',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_dedup (dedup_key),
  KEY idx_receiver (receiver_type, receiver_id, read_at),
  KEY idx_status (status, channel, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内/短信通知（去重、可重试、可查询）';

CREATE TABLE job_run_log (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  job_code    VARCHAR(48) NOT NULL,
  started_at  DATETIME(3) NOT NULL,
  finished_at DATETIME(3) NULL,
  status      TINYINT NOT NULL DEFAULT 0 COMMENT '0运行中 1成功 2失败',
  affected    INT NOT NULL DEFAULT 0 COMMENT '处理条数',
  message     VARCHAR(500) NULL,
  PRIMARY KEY (id),
  KEY idx_code_time (job_code, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务执行日志（运维排查）';
