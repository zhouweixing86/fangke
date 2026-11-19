package com.fangke.visitor.appointment.service;

import com.fangke.visitor.appointment.model.Visitor;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.adapter.DeviceAdapter;
import com.fangke.visitor.device.adapter.DeviceAdapterFactory;
import com.fangke.visitor.device.gateway.DeviceGateway;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.model.DeviceCapability;
import com.fangke.visitor.device.model.PersonPayload;
import com.fangke.visitor.device.service.DeviceCapabilityService;
import com.fangke.visitor.device.service.DeviceRepository;
import com.fangke.visitor.dispatch.model.AccessGrant;
import com.fangke.visitor.dispatch.repository.AccessGrantRepository;
import com.fangke.visitor.employee.service.PhotoValidator;
import com.fangke.visitor.security.CryptoService;
import com.fangke.visitor.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

/**
 * 访客照片采集（C-66）：采集后访客也能刷脸进门。
 *
 * <p>照片存 MinIO（压缩件），并把带照片的名单重推给当前**生效中**的授权门点设备；
 * 没有采集照片的访客仍可刷身份证/出示二维码（协议允许 {@code passAlgo=true}）。
 */
@Service
public class VisitorFaceService {

    private static final Logger log = LoggerFactory.getLogger(VisitorFaceService.class);
    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyyMMdd");

    public record FaceResult(Long visitorId, int faceStatus, int devices, int success, int fail) {
    }

    private final VisitorRepository visitorRepository;
    private final PhotoValidator photoValidator;
    private final StorageService storage;
    private final AccessGrantRepository grantRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceAdapterFactory adapterFactory;
    private final DeviceGateway gateway;
    private final DeviceCapabilityService capabilityService;
    private final CryptoService crypto;
    private final AppProperties props;

    public VisitorFaceService(VisitorRepository visitorRepository, PhotoValidator photoValidator,
                              StorageService storage, AccessGrantRepository grantRepository,
                              DeviceRepository deviceRepository, DeviceAdapterFactory adapterFactory,
                              DeviceGateway gateway, DeviceCapabilityService capabilityService,
                              CryptoService crypto, AppProperties props) {
        this.visitorRepository = visitorRepository;
        this.photoValidator = photoValidator;
        this.storage = storage;
        this.grantRepository = grantRepository;
        this.deviceRepository = deviceRepository;
        this.adapterFactory = adapterFactory;
        this.gateway = gateway;
        this.capabilityService = capabilityService;
        this.crypto = crypto;
        this.props = props;
    }

    /**
     * 上传/替换访客照片：校验 → 压缩 → 存对象存储 → 重推名单。
     *
     * @param visitorId 访客 ID（App 端取自 token 的 subjectId）
     */
    @Transactional
    public FaceResult upload(Long visitorId, byte[] data) {
        Visitor visitor = visitorRepository.findById(visitorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "访客不存在：" + visitorId));
        photoValidator.validate(data);
        byte[] compressed = photoValidator.compressForDevice(data);

        String key = "visitor-face/" + visitorId + "/" + DATE_PATH.format(LocalDate.now()) + "/"
                + sha256(data).substring(0, 16) + ".jpg";
        storage.putObject(key, compressed, "image/jpeg");
        visitorRepository.updateFace(visitorId, key, 1);
        log.info("访客照片已保存 visitor={} key={}", visitorId, key);

        return push(visitor, Base64.getEncoder().encodeToString(compressed));
    }

    /**
     * 把带照片的名单重推给该访客当前生效的授权门点设备。
     *
     * <p>没有生效授权时只保存照片——下次审批下发会自带照片。
     */
    private FaceResult push(Visitor visitor, String photoBase64) {
        List<AccessGrant> grants = grantRepository
                .findBySubject(AccessGrant.SUBJECT_VISITOR, visitor.getId()).stream()
                .filter(g -> g.status() == AccessGrant.STATUS_ACTIVE)
                .toList();

        int devices = 0;
        int success = 0;
        int fail = 0;
        for (AccessGrant grant : grants) {
            PersonPayload payload = payload(visitor, grant, photoBase64);
            for (Device device : deviceRepository.findActiveByGate(grant.gateId())) {
                devices++;
                try {
                    DeviceAdapter adapter = adapterFactory.resolve(device);
                    gateway.execute(device, "upsertVisitorPhoto:" + visitor.getPersonNo(), d -> {
                        capabilityService.require(d, DeviceCapability.PERSON_UPSERT, "下发访客名单");
                        adapter.upsertPerson(d, payload);
                        return null;
                    });
                    success++;
                } catch (Exception e) {
                    fail++;
                    log.warn("访客照片下发失败 device={} person={}：{}",
                            device.getDeviceSn(), visitor.getPersonNo(), e.getMessage());
                }
            }
        }
        return new FaceResult(visitor.getId(), 1, devices, success, fail);
    }

    private PersonPayload payload(Visitor visitor, AccessGrant grant, String photoBase64) {
        PersonPayload payload = new PersonPayload();
        payload.setPersonNo(visitor.getPersonNo());
        payload.setName(visitor.getName());
        payload.setIdCard(visitor.getIdCardEnc() == null ? null : crypto.decrypt(visitor.getIdCardEnc()));
        payload.setUserType(props.getDevice().getVisitorUserType());
        payload.setValidFrom(grant.validFrom().toLocalDate());
        payload.setValidTo(grant.validTo().toLocalDate());
        payload.setPhotoBase64(photoBase64);
        return payload;
    }

    private String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "计算照片摘要失败");
        }
    }
}
