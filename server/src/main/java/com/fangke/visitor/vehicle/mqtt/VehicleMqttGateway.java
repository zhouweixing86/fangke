package com.fangke.visitor.vehicle.mqtt;

import com.fangke.visitor.config.AppProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 车闸 MQTT 连接（docs/22）。
 *
 * <p>相机是 MQTT 客户端：它连我们云上的 broker，发布识别结果到 {@code upload/{SN}}，
 * 订阅 {@code download/{SN}} 收平台指令。现场不需要电脑，也不需要相机有公网 IP。
 */
@Component
public class VehicleMqttGateway {

    private static final Logger log = LoggerFactory.getLogger(VehicleMqttGateway.class);

    /** 上行消息回调（由业务层注册，避免循环依赖）。 */
    public interface UploadListener {
        void onUpload(String topic, String payload);
    }

    private final AppProperties props;
    private volatile MqttClient client;
    private volatile boolean connected;
    private volatile String lastError;
    private UploadListener listener;

    public VehicleMqttGateway(AppProperties props) {
        this.props = props;
    }

    public void setListener(UploadListener listener) {
        this.listener = listener;
    }

    private AppProperties.Vehicle.Mqtt config() {
        return props.getVehicle().getMqtt();
    }

    @PostConstruct
    public void start() {
        AppProperties.Vehicle.Mqtt cfg = config();
        if (!cfg.isEnabled()) {
            log.info("车闸 MQTT 通道未启用（fangke.vehicle.mqtt.enabled=false）");
            return;
        }
        try {
            client = new MqttClient(cfg.getBrokerUrl(), cfg.getClientId(), new MemoryPersistence());
            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    connected = true;
                    lastError = null;
                    log.info("车闸 MQTT 已连接 broker={} reconnect={}", serverURI, reconnect);
                    subscribe();
                }

                @Override
                public void connectionLost(Throwable cause) {
                    connected = false;
                    lastError = cause == null ? "connection lost" : cause.getMessage();
                    log.warn("车闸 MQTT 断开：{}", lastError);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                    try {
                        if (listener != null) {
                            listener.onUpload(topic, payload);
                        }
                    } catch (Exception e) {
                        log.warn("车闸上行报文处理失败 topic={}：{}", topic, e.getMessage());
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    // 下行不追踪回执（相机是否处理由日志与现场验证）
                }
            });
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(30);
            if (cfg.getUsername() != null && !cfg.getUsername().isBlank()) {
                options.setUserName(cfg.getUsername());
                options.setPassword(cfg.getPassword() == null ? new char[0] : cfg.getPassword().toCharArray());
            }
            client.connect(options);
            connected = true;
            subscribe();
            log.info("车闸 MQTT 通道启动 broker={} upload={}", cfg.getBrokerUrl(), cfg.getUploadTopic());
        } catch (Exception e) {
            connected = false;
            lastError = e.getMessage();
            log.warn("车闸 MQTT 连接失败：{}（可在管理端重连）", e.getMessage());
        }
    }

    private void subscribe() {
        AppProperties.Vehicle.Mqtt cfg = config();
        try {
            client.subscribe(cfg.getUploadTopic(), cfg.getQos());
            log.info("车闸 MQTT 订阅上行主题 {}", cfg.getUploadTopic());
        } catch (Exception e) {
            log.warn("车闸 MQTT 订阅失败 {}：{}", cfg.getUploadTopic(), e.getMessage());
        }
    }

    /** 重新连接（管理端「重连」按钮）。 */
    public Map<String, Object> reconnect() {
        close();
        start();
        return status();
    }

    public boolean isConnected() {
        return connected && client != null && client.isConnected();
    }

    /** 发布到某台设备的下行主题。 */
    public void publish(String deviceSn, String payload) {
        AppProperties.Vehicle.Mqtt cfg = config();
        if (client == null) {
            throw new IllegalStateException("车闸 MQTT 未启用或未初始化（fangke.vehicle.mqtt.enabled）");
        }
        String topic = cfg.getDownloadTopicPrefix() + deviceSn;
        try {
            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            message.setQos(cfg.getQos());
            message.setRetained(cfg.isRetained());
            client.publish(topic, message);
            log.info("车闸下行 device={} topic={} len={}", deviceSn, topic, payload.length());
        } catch (Exception e) {
            throw new IllegalStateException("MQTT 发布失败：" + e.getMessage(), e);
        }
    }

    public Map<String, Object> status() {
        AppProperties.Vehicle.Mqtt cfg = config();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", cfg.isEnabled());
        view.put("brokerUrl", cfg.getBrokerUrl());
        view.put("clientId", cfg.getClientId());
        view.put("uploadTopic", cfg.getUploadTopic());
        view.put("downloadTopicPrefix", cfg.getDownloadTopicPrefix());
        view.put("connected", isConnected());
        view.put("lastError", lastError == null ? "" : lastError);
        return view;
    }

    @PreDestroy
    public void close() {
        try {
            if (client != null && client.isConnected()) {
                client.disconnect();
            }
        } catch (Exception ignored) {
            // 关闭阶段忽略
        }
        try {
            if (client != null) {
                client.close();
            }
        } catch (Exception ignored) {
            // 关闭阶段忽略
        }
        client = null;
        connected = false;
    }
}
