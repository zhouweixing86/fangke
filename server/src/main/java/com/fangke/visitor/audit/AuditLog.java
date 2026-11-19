package com.fangke.visitor.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计标注：加在需要留痕的写操作上（docs/10 §10 audit_log）。
 *
 * <p>审计日志只 INSERT，应用账号无权 DELETE（BR-D-010）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /** 模块名，如 DEVICE / EMPLOYEE / APPROVAL。 */
    String module();

    /** 动作名，如 OPEN_DOOR / UPLOAD_FACE。 */
    String action();
}
