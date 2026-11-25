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
    private final Vehicle vehicle = new Vehicle();
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

    public Vehicle getVehicle() {
        return vehicle;
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

        /** 车闸/相机协议抓包：兜底 /openapi/** 未知路径并留报文（临时排障，默认关）。 */
        private boolean openapiProbe = false;

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

        public boolean isOpenapiProbe() {
            return openapiProbe;
        }

        public void setOpenapiProbe(boolean openapiProbe) {
            this.openapiProbe = openapiProbe;
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

    /* ---------------- 车闸（云车场 OpenAPI，docs/19） ---------------- */

    public static class Vehicle {

        private final Cloud cloud = new Cloud();
        private final Mqtt mqtt = new Mqtt();

        public Cloud getCloud() {
            return cloud;
        }

        public Mqtt getMqtt() {
            return mqtt;
        }

        /**
         * 车闸相机的 MQTT 通道（docs/22）。
         *
         * <p>相机（信路通 PCM500 系列）作为 MQTT 客户端连我们云上的 broker：
         * 它把识别结果发到 {@code upload/{SN}}，我们往 {@code download/{SN}} 发指令（含白名单增删）。
         * 这样现场不需要电脑，也不需要相机有公网 IP。
         */
        public static class Mqtt {
            /** 总开关。 */
            private boolean enabled = false;
            /** broker 地址，如 tcp://127.0.0.1:1883。 */
            private String brokerUrl = "tcp://127.0.0.1:1883";
            private String username = "";
            private String password = "";
            /** 我们的客户端 ID（与相机不同即可）。 */
            private String clientId = "fangke-vehicle-gateway";
            /** 上行主题（相机发布识别结果），支持通配符。 */
            private String uploadTopic = "upload/+";
            /** 下行主题前缀（我们发布指令），完整主题 = 前缀 + 设备SN。 */
            private String downloadTopicPrefix = "download/";
            /**
             * 下发 QoS：1 = broker 重投直到相机确认。
             *
             * <p>实测 QoS0 下 50 条车牌只有 38 条回执——静默丢消息意味着现场可能开不了门，不能用 0。
             */
            private int qos = 1;
            /** 保留消息：相机重连后能立即收到最新指令。 */
            private boolean retained = false;

            /** 下行发布超时（毫秒）：Paho 卡住时最多等这么久，超时即判定连接半死并强制重连。 */
            private int publishTimeoutMs = 5000;

            /** 新增/更新车牌的报文模板（${plate} ${type} ${endDate} ${sn} ${requestId} ${time} 占位）。 */
            private String addTemplate = "{\"command\":\"AddCarInfo\",\"data\":\"{\\\"carInfoList\\\":["
                    + "{\\\"plate\\\":\\\"${plate}\\\",\\\"type\\\":${type},\\\"time\\\":\\\"${endDate}\\\"}]}\","
                    + "\"requestId\":\"${requestId}\",\"sn\":\"${sn}\",\"time\":\"${time}\",\"version\":\"1.0.1\"}";
            /** 删除车牌的报文模板。 */
            private String deleteTemplate = "{\"command\":\"DeleteCarInfo\",\"data\":\"{\\\"plateList\\\":[\\\"${plate}\\\"]}\","
                    + "\"requestId\":\"${requestId}\",\"sn\":\"${sn}\",\"time\":\"${time}\",\"version\":\"1.0.1\"}";
            /** 全量覆盖车牌的报文模板（${items} 展开为 CarInfo 数组片段）。 */
            private String importTemplate = "{\"command\":\"AddCarInfo\",\"data\":\"{\\\"carInfoList\\\":[${items}]}\","
                    + "\"requestId\":\"${requestId}\",\"sn\":\"${sn}\",\"time\":\"${time}\",\"version\":\"1.0.1\"}";
            /**
             * 固定车类型（协议 §5.1）：1 月租车 / 2 产权车 / 3 储值车 / 4 贵宾车 / 5 黑名单 / 6 自定义白名单。
             *
             * <p>访客用 1（月租车）：协议里它的 {@code time} 是「到期日期」，相机会按日期失效；
             * 实测 4（贵宾车）会把到期时间存成「无」= 永不过期，不适合有有效期的访客。
             */
            private int plateType = 1;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getBrokerUrl() {
                return brokerUrl;
            }

            public void setBrokerUrl(String brokerUrl) {
                this.brokerUrl = brokerUrl;
            }

            public String getUsername() {
                return username;
            }

            public void setUsername(String username) {
                this.username = username;
            }

            public String getPassword() {
                return password;
            }

            public void setPassword(String password) {
                this.password = password;
            }

            public String getClientId() {
                return clientId;
            }

            public void setClientId(String clientId) {
                this.clientId = clientId;
            }

            public String getUploadTopic() {
                return uploadTopic;
            }

            public void setUploadTopic(String uploadTopic) {
                this.uploadTopic = uploadTopic;
            }

            public String getDownloadTopicPrefix() {
                return downloadTopicPrefix;
            }

            public void setDownloadTopicPrefix(String downloadTopicPrefix) {
                this.downloadTopicPrefix = downloadTopicPrefix;
            }

            public int getQos() {
                return qos;
            }

            public void setQos(int qos) {
                this.qos = qos;
            }

            public boolean isRetained() {
                return retained;
            }

            public void setRetained(boolean retained) {
                this.retained = retained;
            }

            public String getAddTemplate() {
                return addTemplate;
            }

            public void setAddTemplate(String addTemplate) {
                this.addTemplate = addTemplate;
            }

            public String getDeleteTemplate() {
                return deleteTemplate;
            }

            public void setDeleteTemplate(String deleteTemplate) {
                this.deleteTemplate = deleteTemplate;
            }

            public String getImportTemplate() {
                return importTemplate;
            }

            public void setImportTemplate(String importTemplate) {
                this.importTemplate = importTemplate;
            }

            public int getPlateType() {
                return plateType;
            }

            public void setPlateType(int plateType) {
                this.plateType = plateType;
            }

            public int getPublishTimeoutMs() {
                return publishTimeoutMs;
            }

            public void setPublishTimeoutMs(int publishTimeoutMs) {
                this.publishTimeoutMs = publishTimeoutMs;
            }
        }
        /**
         * 云车场开放接口（`/thirdServer/v1/openApi`）。
         *
         * <p>「接口需要客户付费才可以调用」，凭据由车场方分配；未配置时只做 dry-run（算差异但不发送），
         * 现场仍可核对「应该下发什么」。
         */
        public static class Cloud {
            /** 总开关：拿到 park_id / secret 并开通接口后再打开。 */
            private boolean enabled = false;
            /** 形如 https://xxx.com/thirdServer/v1/openApi */
            private String baseUrl = "";
            /** 车场编号。 */
            private String parkId = "";
            /** 签名密钥（每个车场不同）。 */
            private String secret = "";
            /** 出口通道编号（query_channels 查出来填）。 */
            private int channelId = 0;
            /** visitor_sync 的二维码类型：0 访客预约码 / 1 入口登记码。 */
            private int visitorType = 0;
            /** 调用超时（秒）。 */
            private int timeoutSeconds = 8;
            /** 定时对账间隔（毫秒）。 */
            private long syncMs = 60_000L;
            /** 启动后首次对账延迟（毫秒），避免和启动任务抢资源。 */
            private long syncInitialDelayMs = 45_000L;
            /** 员工车牌走月卡接口（month_dataSync）而不是访客接口；车场确认后再开。 */
            private boolean employeeAsMonthCard = false;
            /** 月卡接口必填的车辆类型 id（employeeAsMonthCard=true 时用）。 */
            private int authType = 0;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getBaseUrl() {
                return baseUrl;
            }

            public void setBaseUrl(String baseUrl) {
                this.baseUrl = baseUrl;
            }

            public String getParkId() {
                return parkId;
            }

            public void setParkId(String parkId) {
                this.parkId = parkId;
            }

            public String getSecret() {
                return secret;
            }

            public void setSecret(String secret) {
                this.secret = secret;
            }

            public int getChannelId() {
                return channelId;
            }

            public void setChannelId(int channelId) {
                this.channelId = channelId;
            }

            public int getVisitorType() {
                return visitorType;
            }

            public void setVisitorType(int visitorType) {
                this.visitorType = visitorType;
            }

            public int getTimeoutSeconds() {
                return timeoutSeconds;
            }

            public void setTimeoutSeconds(int timeoutSeconds) {
                this.timeoutSeconds = timeoutSeconds;
            }

            public long getSyncMs() {
                return syncMs;
            }

            public void setSyncMs(long syncMs) {
                this.syncMs = syncMs;
            }

            public long getSyncInitialDelayMs() {
                return syncInitialDelayMs;
            }

            public void setSyncInitialDelayMs(long syncInitialDelayMs) {
                this.syncInitialDelayMs = syncInitialDelayMs;
            }

            public boolean isEmployeeAsMonthCard() {
                return employeeAsMonthCard;
            }

            public void setEmployeeAsMonthCard(boolean employeeAsMonthCard) {
                this.employeeAsMonthCard = employeeAsMonthCard;
            }

            public int getAuthType() {
                return authType;
            }

            public void setAuthType(int authType) {
                this.authType = authType;
            }

            /** 凭据齐全才认为可真实调用。 */
            public boolean configured() {
                return baseUrl != null && !baseUrl.isBlank()
                        && parkId != null && !parkId.isBlank()
                        && secret != null && !secret.isBlank();
            }
        }
    }
}
