package com.fangke.visitor.device.remote;

import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.device.adapter.DeviceAdapterFactory;
import com.fangke.visitor.device.model.Device;
import com.fangke.visitor.device.service.DeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 二维码核验的「设备侧接线」：把 thirdQrUrl 指到本服务，让设备扫到码后回来问平台。
 *
 * <p>两条路都走一遍，覆盖面最广：
 * <ul>
 *   <li>入队参数下发 → 设备下一次心跳拉参数时自己写入（云端部署、平台连不到设备时唯一可行）</li>
 *   <li>直连 9090 立即写入 → 同网时立刻生效，不用等心跳</li>
 * </ul>
 */
@Service
public class DeviceQrConfigService {

    private static final Logger log = LoggerFactory.getLogger(DeviceQrConfigService.class);

    private final DeviceRepository deviceRepository;
    private final DeviceAdapterFactory adapterFactory;
    private final DeviceRemoteService remoteService;

    public DeviceQrConfigService(DeviceRepository deviceRepository,
                                 DeviceAdapterFactory adapterFactory,
                                 DeviceRemoteService remoteService) {
        this.deviceRepository = deviceRepository;
        this.adapterFactory = adapterFactory;
        this.remoteService = remoteService;
    }

    /** 给所有在用设备排一次参数下发；返回排上的台数（未配置平台地址时返回 0）。 */
    public int requestParamSync() {
        String url = remoteService.qrPushUrl();
        if (url == null) {
            log.warn("未配置 fangke.device.pull-public-base-url，设备二维码核验地址无法下发");
            return 0;
        }
        int queued = 0;
        for (Device device : deviceRepository.findAllActive()) {
            try {
                adapterFactory.resolve(device).writeParams(device, Map.of("thirdQrUrl", url));
                queued++;
            } catch (Exception e) {
                log.warn("二维码核验地址下发失败 device={}：{}", device.getDeviceSn(), e.getMessage());
            }
        }
        return queued;
    }

    /** 立即给这台设备写二维码核验配置（要求平台与设备同网）。 */
    public Map<String, Object> applyNow(Device device) {
        return remoteService.applyQrPushConfig(device);
    }

    /**
     * 现场一键接线：先直连写、再补一次参数下发。
     *
     * <p>直连失败不报错——云端部署连不到设备是常态，参数下发那条路仍然会生效。
     */
    public Map<String, Object> apply(Device device) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("qrPushUrl", remoteService.qrPushUrl());
        try {
            view.put("appliedNow", true);
            view.put("result", applyNow(device));
        } catch (BusinessException e) {
            view.put("appliedNow", false);
            view.put("appliedNowError", e.getMessage());
        }
        view.put("queuedDevices", requestParamSync());
        return view;
    }
}
