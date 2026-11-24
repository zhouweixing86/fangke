package com.fangke.visitor.auth.service;

import com.fangke.visitor.auth.repository.SysUserRepository;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 后台登录（docs/11 §5.9）。
 *
 * <p>包含最基础的暴力破解防护：同一账号 10 分钟内连续失败 5 次后锁定 10 分钟。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(10);

    public record LoginResult(String token, String username, String displayName,
                              List<String> roles, List<String> permissions, long expiresInSeconds) {
    }

    private record Attempt(int count, Instant lastAttempt) {
    }

    private final SysUserRepository repository;
    private final JwtService jwtService;
    private final com.fangke.visitor.admin.service.RoleAdminService roleAdminService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public AuthService(SysUserRepository repository, JwtService jwtService,
                       com.fangke.visitor.admin.service.RoleAdminService roleAdminService) {
        this.repository = repository;
        this.jwtService = jwtService;
        this.roleAdminService = roleAdminService;
    }

    public LoginResult login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "用户名和密码不能为空");
        }
        String key = username.trim();
        checkLock(key);

        SysUserRepository.UserRow user = repository.findByUsername(key)
                .orElseThrow(() -> {
                    recordFailure(key);
                    return new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
                });

        if (user.status() != 1) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
        if (!encoder.matches(password, user.passwordHash())) {
            recordFailure(key);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        attempts.remove(key);
        List<String> roles = repository.findRoles(user.id());
        List<String> permissions = roleAdminService.permissionsOfRoles(roles);
        String token = jwtService.issue(user.id(), user.username(), roles);
        log.info("登录成功 username={} roles={} perms={}", user.username(), roles, permissions);
        return new LoginResult(token, user.username(), user.realName(), roles, permissions,
                jwtService.ttlSeconds());
    }

    /** 初始化管理员用（只在账号表为空时调用）。 */
    public String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    private void checkLock(String username) {
        Attempt attempt = attempts.get(username);
        if (attempt == null) {
            return;
        }
        if (attempt.count() >= MAX_ATTEMPTS) {
            Duration elapsed = Duration.between(attempt.lastAttempt(), Instant.now());
            if (elapsed.compareTo(LOCK_DURATION) < 0) {
                long waitMinutes = LOCK_DURATION.minus(elapsed).toMinutes() + 1;
                throw new BusinessException(ErrorCode.UNAUTHORIZED,
                        "尝试次数过多，请 " + waitMinutes + " 分钟后再试");
            }
            attempts.remove(username);
        }
    }

    private void recordFailure(String username) {
        attempts.compute(username, (k, v) -> v == null
                ? new Attempt(1, Instant.now())
                : new Attempt(v.count() + 1, Instant.now()));
        log.warn("登录失败 username={} 累计={}", username, attempts.get(username).count());
    }
}
