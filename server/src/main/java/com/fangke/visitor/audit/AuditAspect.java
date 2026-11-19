package com.fangke.visitor.audit;

import com.fangke.visitor.security.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计切面：记录模块、动作、操作人、来源 IP 与结果。
 *
 * <p>审计失败绝不能影响业务主流程，因此写入异常只记日志。
 */
@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private static final String INSERT = "INSERT INTO audit_log "
            + "(user_id, user_name, module, action, target, request_uri, request_ip, result, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(3))";

    private final JdbcTemplate jdbc;

    public AuditAspect(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {
        boolean success = true;
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            success = false;
            throw t;
        } finally {
            write(auditLog, pjp, success);
        }
    }

    private void write(AuditLog auditLog, ProceedingJoinPoint pjp, boolean success) {
        try {
            HttpServletRequest request = currentRequest();
            Object uid = request == null ? null : request.getAttribute(AuthInterceptor.ATTR_USER_ID);
            Object userName = request == null ? null : request.getAttribute(AuthInterceptor.ATTR_USER_NAME);
            String target = pjp.getSignature().getName();
            jdbc.update(INSERT,
                    uid instanceof Long l ? l : null,
                    userName instanceof String s ? s : null,
                    auditLog.module(),
                    auditLog.action(),
                    target,
                    request == null ? null : request.getRequestURI(),
                    request == null ? null : request.getRemoteAddr(),
                    success ? 1 : 0);
        } catch (Exception e) {
            log.warn("审计日志写入失败 module={} action={}：{}", auditLog.module(), auditLog.action(), e.getMessage());
        }
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }
}
