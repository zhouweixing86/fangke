package com.fangke.visitor.visit.service;

import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.employee.service.EmployeeValidator;
import com.fangke.visitor.security.CryptoService;
import com.fangke.visitor.visit.model.VisitDict;
import com.fangke.visitor.visit.model.VisitRegister;
import com.fangke.visitor.visit.repository.VisitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 来访登记 + 基础字典（V26）：对齐参考站「来访信息 / 事由信息 / 区域信息 / 岗位管理」。
 */
@Service
public class VisitService {

    /** 登记请求（手机号明文传进来，落库前加密+掩码）。 */
    public record RegisterRequest(Long areaId, Long causeId, String visitorName, String phone,
                                  String plateNo, String company, Integer personCount,
                                  LocalDateTime arrivalTime, String remark) {
    }

    private final VisitRepository repository;
    private final CryptoService crypto;

    public VisitService(VisitRepository repository, CryptoService crypto) {
        this.repository = repository;
        this.crypto = crypto;
    }

    /* ---------------- 字典 ---------------- */

    public List<VisitDict> dict(String type, boolean enabledOnly) {
        if (!VisitDict.validType(type)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "字典类型只能是 CAUSE / AREA / POST");
        }
        return repository.listDict(type, enabledOnly);
    }

    @Transactional
    public long createDict(String type, String name, Integer sort, String remark) {
        if (!VisitDict.validType(type)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "字典类型只能是 CAUSE / AREA / POST");
        }
        String text = requireName(name);
        if (repository.existsDict(type, text, null)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "同一类型下名称不能重复：" + text);
        }
        return repository.insertDict(new VisitDict(null, type, text, sort == null ? 0 : sort, 1, trim(remark)));
    }

    @Transactional
    public void updateDict(Long id, String name, Integer sort, Integer status, String remark) {
        VisitDict dict = repository.findDict(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "字典不存在：" + id));
        String text = requireName(name);
        if (repository.existsDict(dict.dictType(), text, id)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "同一类型下名称不能重复：" + text);
        }
        repository.updateDict(id, text, sort == null ? dict.sort() : sort,
                status == null ? dict.status() : status, trim(remark));
    }

    @Transactional
    public void deleteDict(Long id) {
        repository.findDict(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "字典不存在：" + id));
        repository.deleteDict(id);
    }

    /* ---------------- 来访登记 ---------------- */

    @Transactional
    public long register(RegisterRequest request, Long operatorId) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "登记内容不能为空");
        }
        String name = request.visitorName() == null ? "" : request.visitorName().trim();
        if (name.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "姓名不能为空");
        }
        int count = request.personCount() == null || request.personCount() < 1 ? 1 : request.personCount();
        LocalDateTime arrival = request.arrivalTime() == null ? LocalDateTime.now() : request.arrivalTime();

        String phone = EmployeeValidator.validatePhone(request.phone());
        byte[] phoneEnc = phone == null ? null : crypto.encrypt(phone);
        String phoneMask = phone == null ? null : CryptoService.maskPhone(phone);

        VisitRegister row = new VisitRegister(null, request.areaId(), null, request.causeId(), null,
                name, phoneMask, trim(request.plateNo()), trim(request.company()), count,
                arrival, null, VisitRegister.STATUS_INSIDE, trim(request.remark()), null);
        return repository.insertRegister(row, phoneEnc, operatorId);
    }

    public PageResult<VisitRegister> search(String keyword, Long areaId, Long causeId, Integer status,
                                            LocalDate from, LocalDate to, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        List<VisitRegister> list = repository.search(keyword, areaId, causeId, status, from, to,
                (safePage - 1) * safeSize, safeSize);
        long total = repository.count(keyword, areaId, causeId, status, from, to);
        return PageResult.of(total, safePage, safeSize, list);
    }

    @Transactional
    public VisitRegister leave(Long id) {
        VisitRegister row = repository.findRegister(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "登记记录不存在：" + id));
        if (!row.inside()) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "该访客已离开");
        }
        repository.markLeave(id, LocalDateTime.now());
        return repository.findRegister(id).orElse(row);
    }

    private String requireName(String name) {
        String text = name == null ? "" : name.trim();
        if (text.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "名称不能为空");
        }
        if (text.length() > 64) {
            throw new BusinessException(ErrorCode.PARAM_TOO_LONG, "名称最长 64 字");
        }
        return text;
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : (text.length() > 255 ? text.substring(0, 255) : text);
    }
}
