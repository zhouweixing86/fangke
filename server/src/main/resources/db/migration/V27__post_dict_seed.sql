-- 岗位字典种子（C-74 补充）：对齐参考站「岗位管理」，并给员工档案的「岗位」下拉准备默认值。
-- 已存在同名岗位时跳过（uk_type_name 唯一键）。

INSERT IGNORE INTO visit_dict (dict_type, name, sort, remark) VALUES
  ('POST', '保安', 1, NULL),
  ('POST', '库管员', 2, NULL),
  ('POST', '司机', 3, NULL),
  ('POST', '行政', 4, NULL),
  ('POST', '装卸工', 5, NULL);
