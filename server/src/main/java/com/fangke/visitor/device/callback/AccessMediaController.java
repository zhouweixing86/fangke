package com.fangke.visitor.device.callback;

import com.fangke.visitor.common.ApiResponse;
import com.fangke.visitor.storage.StorageService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通行记录照片查看（docs/11 §5.6）。
 *
 * <p>私有桶不放直链，只发短时预签名 URL（docs/10 §13.2）。
 */
@RestController
@RequestMapping("/api/admin/access/event")
public class AccessMediaController {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final StorageService storage;

    public AccessMediaController(JdbcTemplate jdbc, StorageService storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    @GetMapping("/{eventId}/media")
    public ApiResponse<List<Map<String, Object>>> list(@PathVariable Long eventId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT id, media_type, oss_key, expire_at FROM access_event_media WHERE event_id = ? ORDER BY id",
                (rs, i) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getLong("id"));
                    item.put("mediaType", rs.getInt("media_type"));
                    item.put("ossKey", rs.getString("oss_key"));
                    item.put("expireAt", rs.getTimestamp("expire_at") == null
                            ? "" : DATETIME.format(rs.getTimestamp("expire_at").toLocalDateTime()));
                    item.put("url", storage.configured()
                            ? storage.presignGet(rs.getString("oss_key"), null)
                            : "");
                    return item;
                }, eventId);
        return ApiResponse.ok(new ArrayList<>(rows));
    }
}
