package com.fangke.visitor.storage;

import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;

/**
 * S3 兼容实现：MinIO（内网自建，默认）与云 OSS/COS 共用同一份代码。
 *
 * <p>MinIO 必须开启 <b>path-style</b>（{@code http://host:9000/bucket/key}），否则预签名 URL 会拼成
 * {@code http://bucket.host:9000/key} 而无法访问。
 */
@Service
public class S3StorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(S3StorageService.class);

    private final AppProperties.Storage cfg;
    private final S3Client client;
    private final S3Presigner presigner;
    private volatile boolean bucketReady;

    public S3StorageService(AppProperties props) {
        this.cfg = props.getStorage();
        if (!configured()) {
            this.client = null;
            this.presigner = null;
            return;
        }
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(nullSafe(cfg.getAccessKey()), nullSafe(cfg.getSecretKey())));

        S3Configuration s3Config = S3Configuration.builder()
                .pathStyleAccessEnabled(cfg.isPathStyle())
                .build();

        this.client = S3Client.builder()
                .endpointOverride(URI.create(cfg.getEndpoint()))
                .region(Region.of(cfg.getRegion()))
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .build();

        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(cfg.getEndpoint()))
                .region(Region.of(cfg.getRegion()))
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .build();
    }

    /** 启动时确保 bucket 存在；失败不阻塞启动（照片功能降级，业务照常）。 */
    @PostConstruct
    void initBucket() {
        if (!configured()) {
            log.warn("对象存储未配置（fangke.storage.*），照片相关接口将不可用");
            return;
        }
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(cfg.getBucket()).build());
            bucketReady = true;
            log.info("对象存储就绪 endpoint={} bucket={} pathStyle={}",
                    cfg.getEndpoint(), cfg.getBucket(), cfg.isPathStyle());
        } catch (S3Exception e) {
            if (cfg.isAutoCreateBucket()) {
                try {
                    client.createBucket(CreateBucketRequest.builder().bucket(cfg.getBucket()).build());
                    bucketReady = true;
                    log.info("已自动创建 bucket={}", cfg.getBucket());
                } catch (S3Exception createError) {
                    log.error("自动创建 bucket 失败 bucket={}：{}", cfg.getBucket(), createError.getMessage());
                }
            } else {
                log.error("bucket 不存在且未开启自动创建 bucket={}：{}", cfg.getBucket(), e.getMessage());
            }
        } catch (Exception e) {
            log.error("对象存储连接失败 endpoint={}：{}", cfg.getEndpoint(), e.getMessage());
        }
    }

    @Override
    public String putObject(String key, byte[] data, String contentType) {
        requireReady();
        try {
            client.putObject(PutObjectRequest.builder()
                            .bucket(cfg.getBucket())
                            .key(key)
                            .contentType(contentType == null ? "application/octet-stream" : contentType)
                            .build(),
                    RequestBody.fromBytes(data));
            return key;
        } catch (S3Exception e) {
            log.error("对象上传失败 key={}：{}", key, e.getMessage());
            throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE, "照片上传失败：" + e.getMessage());
        }
    }

    @Override
    public String putBase64(String key, String base64, String contentType) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(stripDataUrl(base64));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE, "照片 base64 解码失败");
        }
        return putObject(key, data, contentType);
    }

    @Override
    public byte[] getObject(String key) {
        requireReady();
        try {
            return client.getObjectAsBytes(software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
                    .bucket(cfg.getBucket())
                    .key(key)
                    .build()).asByteArray();
        } catch (S3Exception e) {
            log.error("对象读取失败 key={}：{}", key, e.getMessage());
            throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE, "照片读取失败：" + e.getMessage());
        }
    }

    @Override
    public String presignPut(String key, String contentType, Duration ttl) {        requireReady();
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(cfg.getBucket())
                .key(key)
                .contentType(contentType)
                .build();
        return presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(ttl == null ? cfg.getPresignTtl() : ttl)
                .putObjectRequest(request)
                .build()).url().toString();
    }

    @Override
    public String presignGet(String key, Duration ttl) {
        requireReady();
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(cfg.getBucket())
                .key(key)
                .build();
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl == null ? cfg.getPresignTtl() : ttl)
                .getObjectRequest(request)
                .build()).url().toString();
    }

    @Override
    public boolean exists(String key) {
        if (!configured()) {
            return false;
        }
        try {
            client.headObject(HeadObjectRequest.builder().bucket(cfg.getBucket()).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE, "对象存储查询失败：" + e.getMessage());
        }
    }

    @Override
    public boolean configured() {
        return notBlank(cfg.getEndpoint()) && notBlank(cfg.getBucket())
                && notBlank(cfg.getAccessKey()) && notBlank(cfg.getSecretKey());
    }

    private void requireReady() {
        if (!configured()) {
            throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE, "对象存储未配置");
        }
        if (!bucketReady) {
            // 启动时建桶失败的情况下再试一次，避免一次抖动导致照片长期不可用
            initBucket();
            if (!bucketReady) {
                throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE, "对象存储不可用（bucket 未就绪）");
            }
        }
    }

    /** 兼容设备/前端可能带上的 data URL 前缀。 */
    private String stripDataUrl(String base64) {
        int comma = base64.indexOf(',');
        return base64.startsWith("data:") && comma > 0 ? base64.substring(comma + 1) : base64;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
