package com.fangke.visitor.auth.service;

import com.fangke.visitor.auth.repository.SysUserRepository;
import com.fangke.visitor.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 首次启动初始化管理员账号（账号表为空时）。
 *
 * <p>初始密码来自 {@code fangke.bootstrap.admin-password}（生产用环境变量注入），
 * 创建后会打印告警，提醒尽快修改。
 */
@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final SysUserRepository repository;
    private final AuthService authService;
    private final AppProperties props;

    public BootstrapAdminInitializer(SysUserRepository repository, AuthService authService,
                                     AppProperties props) {
        this.repository = repository;
        this.authService = authService;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (repository.countUsers() > 0) {
                return;
            }
            String username = props.getBootstrap().getAdminUsername();
            String password = props.getBootstrap().getAdminPassword();
            if (username == null || username.isBlank() || password == null || password.isBlank()) {
                log.error("账号表为空但没有配置初始管理员口令，未创建任何账号。"
                        + "请设置环境变量 BOOTSTRAP_ADMIN_PASSWORD（或 fangke.bootstrap.admin-password）后重启。");
                return;
            }
            long id = repository.insert(username, authService.encode(password), "系统管理员", 1);
            repository.bindRole(id, "ADMIN");
            log.warn("已创建初始管理员账号 username={}，请立即修改初始密码（当前来自配置项 "
                    + "fangke.bootstrap.admin-password）", username);
        } catch (Exception e) {
            log.error("初始化管理员失败：{}", e.getMessage(), e);
        }
    }
}
