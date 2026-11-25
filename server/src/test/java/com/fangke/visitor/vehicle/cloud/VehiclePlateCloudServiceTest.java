package com.fangke.visitor.vehicle.cloud;

import com.fangke.visitor.device.model.PlatePayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 车牌差分对账（docs/19 §3）：新增/修改/删除只发变化的那几条。 */
class VehiclePlateCloudServiceTest {

    private static PlatePayload plate(String plateNo, String name, LocalDateTime end) {
        return new PlatePayload(plateNo, PlatePayload.OWNER_VISITOR, 1L, name, null,
                end.minusHours(2), end);
    }

    private static VehiclePlatePush pushed(PlatePayload plate) {
        VehiclePlatePush row = new VehiclePlatePush();
        row.setPlateNo(plate.plateNo());
        row.setVisitorId(VehiclePlateCloudService.visitorIdOf(plate.plateNo()));
        row.setOwnerType(plate.ownerType());
        row.setPersonName(plate.ownerName());
        row.setBeginTime(plate.validFrom());
        row.setEndTime(plate.validTo());
        row.setFingerprint(VehiclePlateCloudService.fingerprint(plate));
        row.setState(VehiclePlatePush.STATE_PUSHED);
        row.setPushedAt(LocalDateTime.now());
        return row;
    }

    @Test
    @DisplayName("新车牌 → 新增；内容变化 → 修改；快照里消失 → 删除；没变不发")
    void diffOnlyEmitsChanges() {
        LocalDateTime end = LocalDateTime.of(2026, 11, 26, 12, 0);
        PlatePayload a = plate("京A11111", "张三", end);
        PlatePayload b = plate("京B22222", "李四", end);
        PlatePayload c = plate("京C33333", "王五", end);

        List<VehiclePlatePush> existing = new ArrayList<>();
        existing.add(pushed(a));
        existing.add(pushed(b));
        existing.add(pushed(plate("京D44444", "赵六", end)));

        List<VehiclePlateCloudService.Change> changes = VehiclePlateCloudService.diff(
                List.of(a, plate("京B22222", "李四改名", end), c), existing);

        assertEquals(3, changes.size());
        VehiclePlateCloudService.Change add = changes.stream()
                .filter(x -> x.operateType() == VehiclePlateCloudService.OP_ADD).findFirst().orElseThrow();
        assertEquals("京C33333", add.plateNo());

        VehiclePlateCloudService.Change modify = changes.stream()
                .filter(x -> x.operateType() == VehiclePlateCloudService.OP_MODIFY).findFirst().orElseThrow();
        assertEquals("京B22222", modify.plateNo());

        VehiclePlateCloudService.Change remove = changes.stream()
                .filter(x -> x.operateType() == VehiclePlateCloudService.OP_REMOVE).findFirst().orElseThrow();
        assertEquals("京D44444", remove.plateNo());
        assertNull(remove.desired());
    }

    @Test
    @DisplayName("上次失败且从未成功过的车牌按新增重试")
    void failedRowIsRetriedAsAdd() {
        PlatePayload a = plate("京A11111", "张三", LocalDateTime.of(2026, 11, 26, 12, 0));
        VehiclePlatePush row = pushed(a);
        row.setState(VehiclePlatePush.STATE_FAILED);
        row.setPushedAt(null);

        List<VehiclePlateCloudService.Change> changes = VehiclePlateCloudService.diff(List.of(a), List.of(row));
        assertEquals(1, changes.size());
        assertEquals(VehiclePlateCloudService.OP_ADD, changes.get(0).operateType());
    }
}
