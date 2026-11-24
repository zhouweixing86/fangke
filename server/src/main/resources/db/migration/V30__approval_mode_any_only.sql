-- C-79 审批签署方式收紧：产品上只保留「或签」，历史单签/会签统一改为或签
ALTER TABLE approval_node
    MODIFY COLUMN approve_mode VARCHAR(8) NOT NULL DEFAULT 'ANY' COMMENT '签署方式：固定或签（C-79 后不再区分单签/会签）';

UPDATE approval_node SET approve_mode = 'ANY' WHERE approve_mode <> 'ANY';
