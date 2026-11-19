package com.fangke.visitor.device.pull;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 轮询式设备（HTTP 客户端版）的指令队列 —— 复用 {@code device_cmd} 表。
 *
 * <p>为什么必须排队：这类设备**不能被动接收**请求，只能由它自己按心跳来问
 * "有没有要我做的事"。所以平台侧的「下发名单 / 远程开门 / 校时」都变成
 * "入队 → 心跳时交付 action → 设备回执 → 出队"。
 */
public interface PullCommandRepository {

    String TYPE_ADD_PERSON = "ADD_PERSON";
    String TYPE_UPDATE_PERSON = "UPDATE_PERSON";
    String TYPE_DEL_PERSON = "DEL_PERSON";
    String TYPE_OPEN_DOOR = "OPEN_DOOR";
    String TYPE_SYNC_TIME = "SYNC_TIME";
    String TYPE_PARAM_SYNC = "PARAM_SYNC";

    record Row(Long id, String cmdType, Integer priority, String payload, int retryCount) {
    }

    /** 入队；同设备同类型同 personNo 的未完成指令去重（避免重复刷名单）。 */
    long enqueue(Long deviceId, String cmdType, int priority, String payload, Long batchId);

    /** 待执行（status=0）的指令，按优先级、id 升序。 */
    List<Row> listPending(Long deviceId, String cmdType, int limit);

    /** 指定类型是否有待执行指令（心跳决策用）。 */
    boolean hasPending(Long deviceId, String cmdType);

    /** 把指令标记为「执行中」并累加重试次数。 */
    void markDispatching(List<Long> ids, LocalDateTime at);

    /** 指令完成（1 成功 / 2 失败）。 */
    void markDone(List<Long> ids, int status, String errorMsg);

    /** 按人员编号完成（设备回报 syncWhiteResult 时用）。 */
    int markDoneByPerson(Long deviceId, String cmdType, String personNo, int status, String message);

    /** 超时未回执的「执行中」指令重新排队；超过重试上限置失败。 */
    int requeueStale(Long deviceId, LocalDateTime dispatchedBefore, int maxRetry);

    /** 待执行指令数（看板/告警用）。 */
    long countPending(Long deviceId);

    Optional<Row> findById(Long id);
}
