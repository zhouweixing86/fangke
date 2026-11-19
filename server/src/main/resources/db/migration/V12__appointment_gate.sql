-- 多门点授权（C-64）：访客可申请多个门点或全部门点，审批人可增可减并留痕。
-- 授权语义从「一单一门点」升级为「一单一个批准集合」：access_grant 每个批准门点一行。

ALTER TABLE appointment
    ADD COLUMN gate_scope TINYINT NOT NULL DEFAULT 1
        COMMENT '访客申请口径：1指定门点 2全部门点' AFTER gate_id;

CREATE TABLE appointment_gate (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  appointment_id BIGINT UNSIGNED NOT NULL,
  gate_id        BIGINT UNSIGNED NOT NULL,
  stage          TINYINT NOT NULL DEFAULT 1 COMMENT '1访客申请 2审批批准',
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_appt_gate_stage (appointment_id, gate_id, stage),
  KEY idx_appt_stage (appointment_id, stage),
  KEY idx_gate (gate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约门点集合（申请/批准）';

-- 历史回填①：申请集合取 appointment.gate_id（V11 之前的单据该列为 NULL，交由运行期兜底）
INSERT INTO appointment_gate (appointment_id, gate_id, stage)
SELECT id, gate_id, 1 FROM appointment WHERE gate_id IS NOT NULL;

-- 历史回填②：已下发过的按 access_grant 的实际门点回填批准集合，保证老单据跨门核验口径不变
INSERT IGNORE INTO appointment_gate (appointment_id, gate_id, stage)
SELECT appointment_id, gate_id, 2 FROM access_grant WHERE gate_id IS NOT NULL;
