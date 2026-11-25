package com.fangke.visitor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用配置（application.yml 前缀 fangke）。
 */
@ConfigurationProperties(prefix = "fangke")
public class AppProperties {

    private String tenantCode = "T01";
    private final Jwt jwt = new Jwt();
    private final Device device = new Device();
    private final Storage storage = new Storage();
    private final Qr qr = new Qr();
    private final Crypto crypto = new Crypto();
    private final Gate gate = new Gate();
    private final Bootstrap bootstrap = new Bootstrap();
    private final App app = new App();
    private final Wx wx = new Wx();
    private List<String> corsAllowedOrigins = new ArrayList<>(
            List.of("http://localhost:5173", "http://127.0.0.1:5173"));

    /**
     * 跨域来源通配（局域网演示用），如 {@code http://192.168.*:*}。
     *
     * <p>只在开发/联调环境配置；生产建议同域部署或由网关统一处理。
     */
    private List<String> corsAllowedOriginPatterns = new ArrayList<>();

    public String getTenantCode() {
        return tenantCode;
    }

    public void setTenantCode(String tenantCode) {
        this.tenantCode = tenantCode;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public Device getDevice() {
        return device;
    }

    public Storage getStorage() {
        return storage;
    }

    public Qr getQr() {
        return qr;
    }

    public Crypto getCrypto() {
        return crypto;
    }

    public Gate getGate() {
        return gate;
    }

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    public App getApp() {
        return app;
    }

    public Wx getWx() {
        return wx;
    }

    public List<String> getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins;
    }

    public List<String> getCorsAllowedOriginPatterns() {
        return corsAllowedOriginPatterns;
    }

    public void setCorsAllowedOriginPatterns(List<String> corsAllowedOriginPatterns) {
        this.corsAllowedOriginPatterns = corsAllowedOriginPatterns;
    }

    /* ---------------- 初始化管理员 ---------------- */

    public static class Bootstrap {
        /** 首次启动且账号表为空时创建的超级管理员。 */
        private String adminUsername = "admin";
        /**
         * 初始口令**不编译进二进制**：只从配置读取（application.yml 默认
         * {@code ${BOOTSTRAP_ADMIN_PASSWORD:Admin@123456}}，生产必须用环境变量覆盖）。
         * 留空时不会创建管理员，改为打印错误提示，避免出现"没人知道密码在哪"的账号。
         */
        private String adminPassword = "";

        public String getAdminUsername() {
            return adminUsername;
        }

        public void setAdminUsername(String adminUsername) {
            this.adminUsername = adminUsername;
        }

        public String getAdminPassword() {
            return adminPassword;
        }

        public void setAdminPassword(String adminPassword) {
            this.adminPassword = adminPassword;
        }
    }

    /* ---------------- 门点 ---------------- */

    public static class Gate {
        /** 本期单门点：公司大门。多门点扩展时改为按预约/区域解析。 */
        private Long defaultId = 1L;

        public Long getDefaultId() {
            return defaultId;
        }

        public void setDefaultId(Long defaultId) {
            this.defaultId = defaultId;
        }
    }

    /* ---------------- 敏感字段加密 ---------------- */

    public static class Crypto {
        /** AES-256-GCM 密钥材料（生产用环境变量注入；服务内部 SHA-256 派生 32 字节）。 */
        private String aesKey = "dev-only-aes-key-change-me";
        /** HMAC-SHA256 密钥，必须与 AES 密钥分离（docs/10 §11.1）。 */
        private String hmacKey = "dev-only-hmac-key-change-me";

        public String getAesKey() {
            return aesKey;
        }

        public void setAesKey(String aesKey) {
            this.aesKey = aesKey;
        }

        public String getHmacKey() {
            return hmacKey;
        }

        public void setHmacKey(String hmacKey) {
            this.hmacKey = hmacKey;
        }
    }

    /* ---------------- 凭证（二维码） ---------------- */

    public static class Qr {
        /** 码签名密钥，按环境隔离（docs/11 §2.4）。 */
        private String secret = "change-me-qr-secret";
        /** 动态码有效期（秒），BR-V-019：60 秒。 */
        private int ttlSeconds = 60;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public int getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(int ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }
    }

    /* ---------------- JWT ---------------- */

    public static class Jwt {
        /** HS256 密钥，生产环境必须改为环境变量注入。 */
        private String secret = "change-me-in-production";
        private Duration ttl = Duration.ofHours(8);

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }
    }

    /* ---------------- 设备 ---------------- */

    public static class Device {
        /** 指令失败重试次数（指数退避）。 */
        private int maxRetry = 3;
        /** 单台设备指令队列积压上限，超过判定 4004。 */
        private int queueBacklogLimit = 200;
        /**
         * 访客下发时使用的 usertype。
         *
         * <p>设备文档写的是 white/black/visitor，但**本机固件实测不支持 visitor**：
         * 下发后不进白名单、反而命中黑名单（访客会被拒）。因此默认仍用 white，
         * 员工与访客靠 company/department 字段区分。
         */
        private String visitorUserType = "white";

        /* ---------------- 新终端：HTTP 客户端（轮询）通道 ---------------- */

        /** 设备请求签名 key（设备侧 thirdHttpkey）。 */
        private String pullSignKey = "fangke-pull-key";
        /** 是否校验设备请求签名；联调期可关，生产必须开。 */
        private boolean pullVerifySign = true;
        /** 心跳间隔（秒），下发给设备。 */
        private int pullHeartSeconds = 30;
        /** 指令回执超时（秒），超时重新排队。 */
        private int pullCommandTimeoutSeconds = 300;
        /** 平台对设备可达的根地址（下发给设备当 thirdQrUrl 前缀），如 http://192.168.10.50:8080 */
        private String pullPublicBaseUrl = "";

        /* ---------------- 远程管理：平台直连设备的 9090 下行接口 ---------------- */

        /** 设备 HTTP 管理端口（设备作为服务端，Web 后台与下行接口同一个端口）。 */
        private int remotePort = 9090;
        /** 设备管理员密码（多数接口用它代替 token）。 */
        private String remotePassword = "888888";
        /** 远程调用超时（秒）：设备可能离线，不能把平台线程拖死。 */
        private int remoteTimeoutSeconds = 5;

        public int getRemotePort() {
            return remotePort;
        }

        public void setRemotePort(int remotePort) {
            this.remotePort = remotePort;
        }

        public String getRemotePassword() {
            return remotePassword;
        }

        public void setRemotePassword(String remotePassword) {
            this.remotePassword = remotePassword;
        }

        public int getRemoteTimeoutSeconds() {
            return remoteTimeoutSeconds;
        }

        public void setRemoteTimeoutSeconds(int remoteTimeoutSeconds) {
            this.remoteTimeoutSeconds = remoteTimeoutSeconds;
        }

        public String getPullSignKey() {
            return pullSignKey;
        }

        public void setPullSignKey(String pullSignKey) {
            this.pullSignKey = pullSignKey;
        }

        public boolean isPullVerifySign() {
            return pullVerifySign;
        }

        public void setPullVerifySign(boolean pullVerifySign) {
            this.pullVerifySign = pullVerifySign;
        }

        public int getPullHeartSeconds() {
            return pullHeartSeconds;
        }

        public void setPullHeartSeconds(int pullHeartSeconds) {
            this.pullHeartSeconds = pullHeartSeconds;
        }

        public int getPullCommandTimeoutSeconds() {
            return pullCommandTimeoutSeconds;
        }

        public void setPullCommandTimeoutSeconds(int pullCommandTimeoutSeconds) {
            this.pullCommandTimeoutSeconds = pullCommandTimeoutSeconds;
        }

        public String getPullPublicBaseUrl() {
            return pullPublicBaseUrl;
        }

        public void setPullPublicBaseUrl(String pullPublicBaseUrl) {
            this.pullPublicBaseUrl = pullPublicBaseUrl;
        }

        public int getMaxRetry() {
            return maxRetry;
        }

        public void setMaxRetry(int maxRetry) {
            this.maxRetry = maxRetry;
        }

        public int getQueueBacklogLimit() {
            return queueBacklogLimit;
        }

        public void setQueueBacklogLimit(int queueBacklogLimit) {
            this.queueBacklogLimit = queueBacklogLimit;
        }

        public String getVisitorUserType() {
            return visitorUserType;
        }

        public void setVisitorUserType(String visitorUserType) {
            this.visitorUserType = visitorUserType;
        }
    }
    /* ---------------- 存储 ---------------- */

    public static class Storage {
        /** S3 兼容端点：MinIO 默认 http://127.0.0.1:9000，云 OSS/COS 填各自 endpoint。 */
        private String endpoint = "";
        private String bucket = "";
        private String accessKey = "";
        private String secretKey = "";
        /** MinIO 固定 us-east-1；云厂商按实际区域填。 */
        private String region = "us-east-1";
        /** MinIO 必须 path-style；部分云厂商也支持。 */
        private boolean pathStyle = true;
        /** 启动时 bucket 不存在则自动创建（仅开发/首次部署便利）。 */
        private boolean autoCreateBucket = true;
        /** 预签名有效期（docs/10 §13.2：5 分钟）。 */
        private Duration presignTtl = Duration.ofMinutes(5);

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public boolean isPathStyle() {
            return pathStyle;
        }

        public void setPathStyle(boolean pathStyle) {
            this.pathStyle = pathStyle;
        }

        public boolean isAutoCreateBucket() {
            return autoCreateBucket;
        }

        public void setAutoCreateBucket(boolean autoCreateBucket) {
            this.autoCreateBucket = autoCreateBucket;
        }

        public Duration getPresignTtl() {
            return presignTtl;
        }

        public void setPresignTtl(Duration presignTtl) {
            this.presignTtl = presignTtl;
        }
    }

    /* ---------------- 访客端/员工端（H5、小程序） ---------------- */

    public static class App {
        /** 开发模式：验证码直接回显给前端（生产必须 false 并接入短信网关）。 */
        private boolean smsMock = true;
        /** 验证码有效期（秒）。 */
        private int codeTtlSeconds = 300;
        /**
         * 是否允许用 {@code X-Employee-Id} 请求头模拟员工身份。
         *
         * <p>默认关闭：App token 已就绪，该头只用于联调/自动化测试。
         * 生产误开会让任何能发请求的人以任意员工身份审批，因此必须显式开启。
         */
        private boolean allowEmployeeHeader = false;

        public boolean isSmsMock() {
            return smsMock;
        }

        public void setSmsMock(boolean smsMock) {
            this.smsMock = smsMock;
        }

        public int getCodeTtlSeconds() {
            return codeTtlSeconds;
        }

        public void setCodeTtlSeconds(int codeTtlSeconds) {
            this.codeTtlSeconds = codeTtlSeconds;
        }

        public boolean isAllowEmployeeHeader() {
            return allowEmployeeHeader;
        }

        public void setAllowEmployeeHeader(boolean allowEmployeeHeader) {
            this.allowEmployeeHeader = allowEmployeeHeader;
        }
    }

    /**
     * 微信小程序一键登录（访客端）。
     *
     * <p>mock=true 时不调用微信接口，直接把 code 当作 openid（仅开发/联调用）；
     * 生产必须配置 app-id / secret 并把 mock 关掉。
     */
    public static class Wx {
        private String appId = "";
        private String secret = "";
        private boolean mock = true;
        /** 小程序码版本：release / trial / develop（生成角色小程序码时使用）。 */
        private String envVersion = "release";

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public boolean isMock() {
            return mock;
        }

        public void setMock(boolean mock) {
            this.mock = mock;
        }

        /** 小程序码版本：release（正式）/ trial（体验）/ develop（开发）。 */
        public String getEnvVersion() {
            return envVersion;
        }

        public void setEnvVersion(String envVersion) {
            this.envVersion = envVersion;
        }
    }
}
