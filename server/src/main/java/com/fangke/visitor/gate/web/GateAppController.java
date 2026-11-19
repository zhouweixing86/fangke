package com.fangke.visitor.gate.web;

import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.gate.model.Gate;
import com.fangke.visitor.gate.service.GateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 访客端/员工端选门接口（docs/11 §2.1）。
 *
 * <p>只出启用且可作为授权门点的门点（排除「待分配」），且不暴露设备信息。
 * 访客首次预约在未登录状态下就要选门，因此该 GET 在 {@code AppAuthInterceptor} 里放行为公开接口。
 */
@RestController
@RequestMapping("/api/app/gate")
public class GateAppController {

    private final GateService gateService;

    public GateAppController(GateService gateService) {
        this.gateService = gateService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        List<Map<String, Object>> view = gateService.listSelectable().stream()
                .map(this::toView)
                .toList();
        return ApiResponse.ok(view);
    }

    private Map<String, Object> toView(Gate gate) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", gate.id());
        item.put("gateCode", gate.gateCode());
        item.put("gateName", gate.gateName());
        item.put("gateTypeText", gate.typeText());
        return item;
    }
}
