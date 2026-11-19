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
    private List<String> corsAllowedOrigins = new ArrayList<>(
            List.of("http://localhost:5173", "http://127.0.0.1:5173"));

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

    public List<String> getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins;
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
        /** 设备默认口令（出厂 123456），仅用于首次建档。 */
        private String defaultPassword = "123456";
        private int defaultPort = 8091;
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration requestTimeout = Duration.ofSeconds(10);
        /** 指令失败重试次数（指数退避）。 */
        private int maxRetry = 3;
        /** 单台设备指令队列积压上限，超过判定 4004。 */
        private int queueBacklogLimit = 200;
        /** 允许回调的来源网段（CIDR），为空表示不限制（仅限内网部署）。 */
        private List<String> callbackAllowedCidrs = new ArrayList<>();
        /** 设备回调口令校验开关；设备不带 password 字段时只能靠 IP 白名单。 */
        private boolean callbackVerifyPassword = true;
        /**
         * 访客下发时使用的 usertype。
         *
         * <p>实测：YC-P208 固件对 {@code visitor} 返回“添加成功”但**不落库**，
         * 用 {@code white} 才能生效，所以默认按白名单下发（见 docs/06 §0.3 第 6 条）。
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

        /** 平台对设备可达的根地址（下发给设备当 thirdQrUrl 前缀），如 http://192.168.10.50:8080 */
        private String pullPublicBaseUrl = "";

        public String getPullPublicBaseUrl() {
            return pullPublicBaseUrl;
        }

        public void setPullPublicBaseUrl(String pullPublicBaseUrl) {
            this.pullPublicBaseUrl = pullPublicBaseUrl;
        }

        public void setPullCommandTimeoutSeconds(int pullCommandTimeoutSeconds) {
            this.pullCommandTimeoutSeconds = pullCommandTimeoutSeconds;
        }

        public String getDefaultPassword() {
            return defaultPassword;
        }

        public void setDefaultPassword(String defaultPassword) {
            this.defaultPassword = defaultPassword;
        }

        public int getDefaultPort() {
            return defaultPort;
        }

        public void setDefaultPort(int defaultPort) {
            this.defaultPort = defaultPort;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
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

        public List<String> getCallbackAllowedCidrs() {
            return callbackAllowedCidrs;
        }

        public void setCallbackAllowedCidrs(List<String> callbackAllowedCidrs) {
            this.callbackAllowedCidrs = callbackAllowedCidrs;
        }

        public boolean isCallbackVerifyPassword() {
            return callbackVerifyPassword;
        }

        public void setCallbackVerifyPassword(boolean callbackVerifyPassword) {
            this.callbackVerifyPassword = callbackVerifyPassword;
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
}
