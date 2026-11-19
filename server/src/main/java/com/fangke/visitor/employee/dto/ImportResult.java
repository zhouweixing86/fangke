package com.fangke.visitor.employee.dto;

import java.util.List;

/**
 * Excel 批量导入结果：成功的条数 + 每行的错误原因（便于用户逐行修正）。
 */
public record ImportResult(long total, long success, List<RowError> errors) {

    public record RowError(int row, String message) {
    }

    public static ImportResult of(long total, long success, List<RowError> errors) {
        return new ImportResult(total, success, errors);
    }
}
