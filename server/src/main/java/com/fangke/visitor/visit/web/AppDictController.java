package com.fangke.visitor.visit.web;

import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.visit.model.VisitDict;
import com.fangke.visitor.visit.service.VisitService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 访客端基础字典（C-75）：预约页选「来访事由 / 来访区域」，只返回启用项，不含备注等后台字段以外的东西。
 */
@RestController
@RequestMapping("/api/app/dict")
public class AppDictController {

    private final VisitService visitService;

    public AppDictController(VisitService visitService) {
        this.visitService = visitService;
    }

    @GetMapping("/options")
    public ApiResponse<Map<String, Object>> options() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put(VisitDict.TYPE_CAUSE, visitService.dict(VisitDict.TYPE_CAUSE, true));
        view.put(VisitDict.TYPE_AREA, visitService.dict(VisitDict.TYPE_AREA, true));
        return ApiResponse.ok(view);
    }
}
