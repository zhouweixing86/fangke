package com.fangke.visitor.device.model;

import java.time.LocalDateTime;

/**
 * 设备档案（对应 docs/10 §4 device 表）。
 */
public class Device {

    /**
     * 通道类型：
     * <ul>
     *   <li>{@link #PULL}：HTTP 客户端（轮询）——设备主动来拉，当前唯一在用的真机通道；</li>
     *   <li>{@link #MOCK}：内置模拟设备，无硬件时自测用；</li>
     *   <li>{@link #CLOUD}：云 MQTT，占位未实现。</li>
     * </ul>
     */
    public enum Channel { PULL, MOCK, CLOUD }

    private Long id;
    private String deviceSn;
    private String mac;
    private String deviceName;
    private String model;
    private String firmware;
    private Long gateId;
    /** 0 出口 / 1 入口，对应设备参数 inout。 */
    private Integer direction = 1;
    private Channel channel = Channel.PULL;
    private String ip;
    private int port = 8091;
    private String apiPassword;
    private boolean online;
    private LocalDateTime lastHeartbeat;
    private Integer personCount;
    private Integer capacity;
    private int status = 1;
    /** device.capability 原始 JSON，单台设备的能力覆盖（null 表示跟随适配器）。 */
    private String capabilityJson;
    /** 备注：现场补的位置说明（如「A 栋东侧门内侧」）。 */
    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDeviceSn() {
        return deviceSn;
    }

    public void setDeviceSn(String deviceSn) {
        this.deviceSn = deviceSn;
    }

    public String getMac() {
        return mac;
    }

    public void setMac(String mac) {
        this.mac = mac;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getFirmware() {
        return firmware;
    }

    public void setFirmware(String firmware) {
        this.firmware = firmware;
    }

    public Long getGateId() {
        return gateId;
    }

    public void setGateId(Long gateId) {
        this.gateId = gateId;
    }

    public Integer getDirection() {
        return direction;
    }

    public void setDirection(Integer direction) {
        this.direction = direction;
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getApiPassword() {
        return apiPassword;
    }

    public void setApiPassword(String apiPassword) {
        this.apiPassword = apiPassword;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public LocalDateTime getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(LocalDateTime lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    public Integer getPersonCount() {
        return personCount;
    }

    public void setPersonCount(Integer personCount) {
        this.personCount = personCount;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getCapabilityJson() {
        return capabilityJson;
    }

    public void setCapabilityJson(String capabilityJson) {
        this.capabilityJson = capabilityJson;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String baseUrl() {
        return "http://" + ip + ":" + port;
    }
}
