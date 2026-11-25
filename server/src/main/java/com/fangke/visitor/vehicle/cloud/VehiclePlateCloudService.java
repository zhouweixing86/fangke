package com.fangke.visitor.vehicle.cloud;

import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.device.model.PlatePayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 车闸车牌「跟随业务自动下发」（docs/19 §3）。
 *
 * <p>做法是**差分对账**而不是事件推送：平台把「员工白名单 + 当天已授权访客」的车牌算成期望清单，
 * 与 {@code vehicle_plate_push} 里上一次成功下发的快照比对，只发变化的：
 * <ul>
 *   <li>新增车牌 → {@code visitor_sync} {@code operate_type=1}</li>
 *   <li>姓名/有效期变了 → {@code operate_type=2}</li>
 *   <li>快照里消失（预约过期/作废、员工停用/换车） → {@code operate_type=3}</li>
 * </ul>
 *
 * <p>这样无论车牌是从员工档案、预约审批还是手动改的，下一轮对账都会自动跟上，不用在每个业务点埋下发代码。
 */
@Service
public class VehiclePlateCloudService {

    private static final Logger log = LoggerFactory.getLogger(VehiclePlateCloudService.class);
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static final int OP_ADD = 1;
    public static final int OP_MODIFY = 2;
    public static final int OP_REMOVE = 3;

    /** 一条差分。 */
    public record Change(String plateNo, int operateType, PlatePayload desired) {
    }

    /** 一轮对账结果。 */
    public record SyncResult(boolean enabled, boolean dryRun, int added, int modified, int removed,
                             int failed, int unchanged, String message, List<String> errors) {
    }

    private final AppProperties props;
    private final CloudParkingClient client;
    private final VehiclePlatePushRepository repository;

    public VehiclePlateCloudService(AppProperties props, CloudParkingClient client,
                                    VehiclePlatePushRepository repository) {
        this.props = props;
        this.client = client;
        this.repository = repository;
    }

    public boolean enabled() {
        return props.getVehicle().getCloud().isEnabled();
    }

    public boolean configured() {
        return client.configured();
    }

    /** 执行一轮对账；{@code enabled=false} 只算不发（dry-run），便于现场先核对清单。 */
    public SyncResult reconcile(List<PlatePayload> desired) {
        List<Change> changes = diff(desired, repository.findAll());
        if (!enabled()) {
            return new SyncResult(false, true, count(changes, OP_ADD), count(changes, OP_MODIFY),
                    count(changes, OP_REMOVE), 0, 0,
                    "车场接口未启用（fangke.vehicle.cloud.enabled=false）：已算出差异，未发送", List.of());
        }
        if (!client.configured()) {
            return new SyncResult(true, true, count(changes, OP_ADD), count(changes, OP_MODIFY),
                    count(changes, OP_REMOVE), 0, 0,
                    "车场接口缺少配置（base-url / park-id / secret）：已算出差异，未发送", List.of());
        }
        int added = 0;
        int modified = 0;
        int removed = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        for (Change change : changes) {
            try {
                if (change.operateType() == OP_REMOVE) {
                    VehiclePlatePush last = findByPlate(change.plateNo());
                    client.visitorSync(change.plateNo(),
                            last == null ? visitorIdOf(change.plateNo()) : last.getVisitorId(),
                            last == null ? "访客" : nullSafeName(last.getPersonName()),
                            null,
                            last == null || last.getBeginTime() == null ? LocalDateTime.now().minusDays(1) : last.getBeginTime(),
                            last == null || last.getEndTime() == null ? LocalDateTime.now() : last.getEndTime(),
                            OP_REMOVE, "平台删除车牌", change.plateNo());
                    repository.delete(change.plateNo());
                    removed++;
                } else {
                    PlatePayload plate = change.desired();
                    VehiclePlatePush row = toPush(plate);
                    repository.save(row);
                    client.visitorSync(plate.plateNo(), row.getVisitorId(), nullSafeName(row.getPersonName()),
                            null, row.getBeginTime(), row.getEndTime(), change.operateType(),
                            describe(plate), plate.plateNo());
                    row.setState(VehiclePlatePush.STATE_PUSHED);
                    row.setLastError(null);
                    row.setPushedAt(LocalDateTime.now());
                    repository.save(row);
                    if (change.operateType() == OP_ADD) {
                        added++;
                    } else {
                        modified++;
                    }
                }
            } catch (CloudParkingException e) {
                failed++;
                errors.add(change.plateNo() + "：" + e.getMessage());
                if (change.operateType() != OP_REMOVE) {
                    repository.save(failedRow(change.desired(), e.getMessage()));
                }
                log.warn("车闸车牌下发失败 plate={} op={}：{}", change.plateNo(), change.operateType(), e.getMessage());
            }
        }
        String message = changes.isEmpty() ? "车牌无变化，无需下发" : "下发完成";
        log.info("车闸车牌对账 {}：新增={} 修改={} 删除={} 失败={} 无变化={}",
                message, added, modified, removed, failed, countUnchanged(desired, repository.findAll()));
        return new SyncResult(true, false, added, modified, removed, failed,
                countUnchanged(desired, repository.findAll()), message, errors);
    }

    /** 管理端展示：已下发快照。 */
    public List<Map<String, Object>> stateRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (VehiclePlatePush push : repository.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("plateNo", push.getPlateNo());
            row.put("ownerType", push.getOwnerType());
            row.put("personName", push.getPersonName() == null ? "" : push.getPersonName());
            row.put("beginTime", push.getBeginTime() == null ? "" : DATETIME.format(push.getBeginTime()));
            row.put("endTime", push.getEndTime() == null ? "" : DATETIME.format(push.getEndTime()));
            row.put("state", push.getState());
            row.put("lastError", push.getLastError() == null ? "" : push.getLastError());
            row.put("pushedAt", push.getPushedAt() == null ? "" : DATETIME.format(push.getPushedAt()));
            rows.add(row);
        }
        return rows;
    }

    /* ---------------- 差分（可单测） ---------------- */

    /** 算出「期望清单」相对「已下发快照」的差异。 */
    public static List<Change> diff(List<PlatePayload> desired, List<VehiclePlatePush> existing) {
        Map<String, VehiclePlatePush> snapshot = new LinkedHashMap<>();
        for (VehiclePlatePush push : existing) {
            snapshot.put(push.getPlateNo(), push);
        }
        List<Change> changes = new ArrayList<>();
        Map<String, PlatePayload> wanted = new LinkedHashMap<>();
        for (PlatePayload plate : desired) {
            if (plate == null || plate.plateNo() == null || plate.plateNo().isBlank()) {
                continue;
            }
            wanted.put(plate.plateNo(), plate);
            VehiclePlatePush last = snapshot.get(plate.plateNo());
            if (last == null) {
                changes.add(new Change(plate.plateNo(), OP_ADD, plate));
            } else if (VehiclePlatePush.STATE_FAILED.equals(last.getState()) && last.getPushedAt() == null) {
                changes.add(new Change(plate.plateNo(), OP_ADD, plate));
            } else if (!fingerprint(plate).equals(last.getFingerprint())) {
                changes.add(new Change(plate.plateNo(), OP_MODIFY, plate));
            }
        }
        for (VehiclePlatePush last : existing) {
            if (!wanted.containsKey(last.getPlateNo())) {
                changes.add(new Change(last.getPlateNo(), OP_REMOVE, null));
            }
        }
        return changes;
    }

    /** 内容指纹：姓名/有效期/归属变化才算「需要重发」。 */
    public static String fingerprint(PlatePayload plate) {
        String raw = plate.ownerType() + '|' + plate.ownerId() + '|' + nullSafeName(plate.ownerName()) + '|'
                + plate.validFrom() + '|' + plate.validTo();
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    public static String visitorIdOf(String plateNo) {
        return "FK-" + plateNo;
    }

    private VehiclePlatePush toPush(PlatePayload plate) {
        VehiclePlatePush row = new VehiclePlatePush();
        row.setPlateNo(plate.plateNo());
        row.setVisitorId(visitorIdOf(plate.plateNo()));
        row.setOwnerType(plate.ownerType());
        row.setOwnerRef(plate.ownerId());
        row.setPersonName(nullSafeName(plate.ownerName()));
        row.setBeginTime(plate.validFrom() == null ? LocalDateTime.now().minusDays(1) : plate.validFrom());
        row.setEndTime(plate.validTo() == null ? LocalDateTime.of(2099, 12, 31, 23, 59, 59) : plate.validTo());
        row.setFingerprint(fingerprint(plate));
        row.setState(VehiclePlatePush.STATE_PUSHED);
        return row;
    }

    private VehiclePlatePush failedRow(PlatePayload plate, String error) {
        VehiclePlatePush row = toPush(plate);
        row.setState(VehiclePlatePush.STATE_FAILED);
        row.setLastError(error == null ? null : (error.length() > 500 ? error.substring(0, 500) : error));
        return row;
    }

    private VehiclePlatePush findByPlate(String plateNo) {
        for (VehiclePlatePush push : repository.findAll()) {
            if (plateNo.equals(push.getPlateNo())) {
                return push;
            }
        }
        return null;
    }

    private static String describe(PlatePayload plate) {
        String type = PlatePayload.OWNER_EMPLOYEE.equals(plate.ownerType()) ? "员工" : "访客";
        return "平台同步：" + type + " " + nullSafeName(plate.ownerName());
    }

    private static String nullSafeName(String name) {
        if (name == null || name.isBlank()) {
            return "车牌用户";
        }
        return name.trim().length() > 32 ? name.trim().substring(0, 32) : name.trim();
    }

    private static int count(List<Change> changes, int operateType) {
        return (int) changes.stream().filter(c -> c.operateType() == operateType).count();
    }

    private static int countUnchanged(List<PlatePayload> desired, List<VehiclePlatePush> existing) {
        Map<String, VehiclePlatePush> snapshot = new LinkedHashMap<>();
        for (VehiclePlatePush push : existing) {
            snapshot.put(push.getPlateNo(), push);
        }
        int same = 0;
        for (PlatePayload plate : desired) {
            VehiclePlatePush last = snapshot.get(plate.plateNo());
            if (last != null && VehiclePlatePush.STATE_PUSHED.equals(last.getState())
                    && fingerprint(plate).equals(last.getFingerprint())) {
                same++;
            }
        }
        return same;
    }
}
