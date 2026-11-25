package com.fangke.visitor.employee.service;

import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.common.PlateNo;
import com.fangke.visitor.employee.dto.EmployeeRequest;
import com.fangke.visitor.employee.dto.EmployeeView;
import com.fangke.visitor.employee.dto.ImportResult;
import com.fangke.visitor.employee.model.Employee;
import com.fangke.visitor.employee.model.EmployeeFace;
import com.fangke.visitor.employee.repository.DeptRepository;
import com.fangke.visitor.employee.repository.EmployeeFaceRepository;
import com.fangke.visitor.employee.repository.EmployeeRepository;
import com.fangke.visitor.security.CryptoService;
import com.fangke.visitor.storage.StorageService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 员工管理（M2）：增删改查、Excel 导入、照片校验压缩与绑定。
 */
@Service
public class EmployeeService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final EmployeeRepository repository;
    private final EmployeeFaceRepository faceRepository;
    private final DeptRepository deptRepository;
    private final CryptoService crypto;
    private final StorageService storage;
    private final PhotoValidator photoValidator;

    public EmployeeService(EmployeeRepository repository,
                           EmployeeFaceRepository faceRepository,
                           DeptRepository deptRepository,
                           CryptoService crypto,
                           StorageService storage,
                           PhotoValidator photoValidator) {
        this.repository = repository;
        this.faceRepository = faceRepository;
        this.deptRepository = deptRepository;
        this.crypto = crypto;
        this.storage = storage;
        this.photoValidator = photoValidator;
    }

    /* ---------------- 增删改查 ---------------- */

    @Transactional
    public Employee create(EmployeeRequest request, Long operatorId) {
        String empNo = EmployeeValidator.validateEmpNo(request.empNo());
        EmployeeValidator.requireText(request.name(), "姓名");
        if (repository.existsEmpNo(empNo, null)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "工号已存在：" + empNo);
        }

        Employee employee = new Employee();
        employee.setEmpNo(empNo);
        // 人员编号全局递增（与工号里的数字无关），避免不同工号算出同一个编号撞唯一键
        employee.setPersonNo(String.format("E%06d", repository.nextPersonSeq()));
        employee.setName(request.name().trim());
        requireDept(request.deptId());
        employee.setDeptId(request.deptId());
        employee.setLeaderId(request.leaderId());
        employee.setTitle(trimToNull(request.title()));
        employee.setPlateNo(normalizePlate(request.plateNo()));
        employee.setValidFrom(request.validFrom());
        employee.setValidTo(request.validTo());
        employee.setRemark(trimToNull(request.remark()));
        employee.setStatus(Employee.STATUS_ACTIVE);
        employee.setFaceStatus(Employee.FACE_NONE);
        applySensitiveFields(employee, request.phone(), request.idCard(), null);

        long id;
        for (int attempt = 0; ; attempt++) {
            try {
                id = repository.insert(employee);
                break;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发建档抢到同一个编号：重新取号再试（最多 3 次）
                if (attempt >= 2) {
                    throw e;
                }
                employee.setPersonNo(String.format("E%06d", repository.nextPersonSeq()));
            }
        }
        employee.setId(id);
        log.info("新增员工 empNo={} personNo={} operator={}", empNo, employee.getPersonNo(), operatorId);
        return employee;
    }

    @Transactional
    public Employee update(Long id, EmployeeRequest request, Long operatorId) {
        Employee employee = get(id);
        EmployeeValidator.requireText(request.name(), "姓名");
        employee.setName(request.name().trim());
        requireDept(request.deptId());
        employee.setDeptId(request.deptId());
        employee.setLeaderId(request.leaderId());
        employee.setTitle(trimToNull(request.title()));
        employee.setPlateNo(normalizePlate(request.plateNo()));
        employee.setValidFrom(request.validFrom());
        employee.setValidTo(request.validTo());
        employee.setRemark(trimToNull(request.remark()));

        // 手机号/证件号：传空表示不改
        applySensitiveFields(employee, request.phone(), request.idCard(), id);
        repository.update(employee);
        log.info("编辑员工 id={} operator={}", id, operatorId);
        return employee;
    }

    /** 停用：不再下发、不再可通行（BR-E-007）。 */
    @Transactional
    public Employee stop(Long id, Long operatorId) {
        Employee employee = get(id);
        repository.updateStatus(id, Employee.STATUS_DISABLED);
        employee.setStatus(Employee.STATUS_DISABLED);
        log.info("停用员工 id={} empNo={} operator={}", id, employee.getEmpNo(), operatorId);
        return employee;
    }

    /**
     * 恢复在职：把状态改回在职，授权与设备名单由调用方重新下发。
     *
     * <p>停用会撤销授权并把该人从设备删除，恢复等价于「重新新增一遍」——
     * {@code EmployeeDispatchService.sync} 会把已撤销的授权重新置为生效并重新推名单。
     */
    public Employee enable(Long id, Long operatorId) {
        Employee employee = get(id);
        if (employee.getStatus() == Employee.STATUS_ACTIVE) {
            return employee;
        }
        repository.updateStatus(id, Employee.STATUS_ACTIVE);
        employee.setStatus(Employee.STATUS_ACTIVE);
        log.info("恢复在职 id={} empNo={} operator={}", id, employee.getEmpNo(), operatorId);
        return employee;
    }

    public Employee get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "员工不存在：" + id));
    }

    public PageResult<EmployeeView> list(String keyword, Integer status, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<Employee> rows = repository.search(keyword, status, (safePage - 1) * safeSize, safeSize);
        long total = repository.count(keyword, status);
        return PageResult.of(total, safePage, safeSize, rows.stream().map(EmployeeView::of).toList());
    }

    /* ---------------- 照片 ---------------- */

    /**
     * 绑定照片：校验 → 压缩 → 上传对象存储 → 落库 → 标记已上传。
     *
     * @param rawKey 前端直传后的 key；为空则服务端把原图也上传一份
     */
    @Transactional
    public EmployeeFace bindFace(Long id, String rawKey, byte[] data, Long operatorId) {
        Employee employee = get(id);
        PhotoValidator.Prepared prepared = photoValidator.prepare(data);
        PhotoValidator.Info info = prepared.info();
        byte[] upload = prepared.data();
        byte[] compressed = photoValidator.compressForDevice(upload);
        String sha = sha256(upload);
        String datePath = DATE_PATH.format(LocalDate.now());

        String stdKey = "emp-face-std/" + id + "/" + datePath + "/" + sha.substring(0, 16) + ".jpg";
        storage.putObject(stdKey, compressed, "image/jpeg");

        String ossKey = rawKey;
        if (ossKey == null || ossKey.isBlank()) {
            ossKey = "emp-face/" + id + "/" + datePath + "/" + sha.substring(0, 16) + ".jpg";
            storage.putObject(ossKey, upload, "image/jpeg");
        }

        faceRepository.deactivateOthers(id);
        EmployeeFace face = new EmployeeFace(null, id, ossKey, stdKey,
                info.width(), info.height(), info.sizeBytes(), sha, true, operatorId, null);
        long faceId = faceRepository.insert(face);
        repository.updateFaceStatus(id, Employee.FACE_UPLOADED);
        log.info("员工照片绑定 employeeId={} empNo={} faceId={} {}×{} {}KB→{}KB",
                id, employee.getEmpNo(), faceId, info.width(), info.height(),
                info.sizeBytes() / 1024, compressed.length / 1024);
        return new EmployeeFace(faceId, id, ossKey, stdKey, info.width(), info.height(),
                info.sizeBytes(), sha, true, operatorId, null);
    }

    public List<EmployeeFace> faceHistory(Long id) {
        get(id);
        return faceRepository.listByEmployee(id);
    }

    /** 前端直传预签名（docs/11 §5.2）。 */
    public Map<String, Object> presignUpload(Long employeeId, String fileName) {
        get(employeeId);
        String datePath = DATE_PATH.format(LocalDate.now());
        String key = "emp-face/" + employeeId + "/" + datePath + "/" + java.util.UUID.randomUUID() + ".jpg";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ossKey", key);
        result.put("uploadUrl", storage.presignPut(key, "image/jpeg", null));
        result.put("expireSeconds", 300);
        result.put("fileName", fileName == null ? "" : fileName);
        return result;
    }

    /* ---------------- Excel 导入 ---------------- */

    /**
     * 模板列（第 1 行为表头）：
     * 工号 | 姓名 | 部门编码 | 职务 | 手机号 | 身份证号 | 有效期起 | 有效期止 | 备注 | 车牌号
     */
    public ImportResult importExcel(InputStream in, Long operatorId) {
        List<ImportResult.RowError> errors = new ArrayList<>();
        long total = 0;
        long success = 0;
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null || isBlankRow(row, formatter)) {
                    continue;
                }
                total++;
                try {
                    String deptCode = cell(row, 2, formatter);
                    Long deptId = deptRepository.findIdByCode(deptCode);
                    if (deptCode != null && !deptCode.isBlank() && deptId == null) {
                        throw new BusinessException(ErrorCode.PARAM_INVALID, "部门编码不存在：" + deptCode);
                    }
                    create(new EmployeeRequest(
                            cell(row, 0, formatter),
                            cell(row, 1, formatter),
                            deptId,
                            null,
                            cell(row, 3, formatter),
                            cell(row, 4, formatter),
                            cell(row, 5, formatter),
                            parseDate(cell(row, 6, formatter)),
                            parseDate(cell(row, 7, formatter)),
                            cell(row, 8, formatter),
                            cell(row, 9, formatter)), operatorId);
                    success++;
                } catch (BusinessException e) {
                    errors.add(new ImportResult.RowError(r + 1, e.getMessage()));
                } catch (Exception e) {
                    errors.add(new ImportResult.RowError(r + 1, "解析失败：" + e.getMessage()));
                }
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "Excel 解析失败：" + e.getMessage());
        }
        log.info("员工批量导入 total={} success={} failed={}", total, success, errors.size());
        return ImportResult.of(total, success, errors);
    }

    /* ---------------- 内部 ---------------- */

    private void applySensitiveFields(Employee employee, String phone, String idCard, Long excludeId) {
        if (phone != null && !phone.isBlank()) {
            String normalized = EmployeeValidator.validatePhone(phone);
            String hmac = crypto.hmac(normalized);
            if (repository.existsPhoneHmac(hmac, excludeId)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "手机号已被其他员工使用");
            }
            employee.setPhoneEnc(crypto.encrypt(normalized));
            employee.setPhoneHmac(hmac);
            employee.setPhoneMask(CryptoService.maskPhone(normalized));
        }
        if (idCard != null && !idCard.isBlank()) {
            String normalized = EmployeeValidator.validateIdCard(idCard);
            String hmac = crypto.hmac(normalized);
            if (repository.existsIdCardHmac(hmac, excludeId)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "身份证号已被其他员工使用");
            }
            employee.setIdCardEnc(crypto.encrypt(normalized));
            employee.setIdCardHmac(hmac);
            employee.setIdCardMask(CryptoService.maskIdCard(normalized));
        }
    }

    private String cell(Row row, int index, DataFormatter formatter) {
        Cell cell = row.getCell(index);
        if (cell == null) {
            return null;
        }
        String value = formatter.formatCellValue(cell);
        return value == null ? null : value.trim();
    }

    private boolean isBlankRow(Row row, DataFormatter formatter) {
        for (int i = 0; i < 9; i++) {
            String v = cell(row, i, formatter);
            if (v != null && !v.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim().replace('/', '-');
        try {
            return LocalDate.parse(normalized, DATE);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "日期格式应为 yyyy-MM-dd：" + text);
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 车牌统一成相机口径（大写、去空格）并校验长度。
     *
     * <p>否则员工存 {@code 苏a 12345}、相机读到 {@code 苏A12345}，按字符串比对永远匹配不上。
     */
    private static String normalizePlate(String plateNo) {
        String value = PlateNo.normalizeToNull(plateNo);
        if (value != null && value.length() > PlateNo.MAX_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_TOO_LONG, "车牌号最多 " + PlateNo.MAX_LENGTH + " 个字符");
        }
        return value;
    }

    /** 部门必须存在，否则员工会挂到一个已删除/不存在的部门上。 */
    private void requireDept(Long deptId) {
        if (deptId != null && deptRepository.findById(deptId).isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "部门不存在：" + deptId);
        }
    }

    private String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "照片摘要计算失败");
        }
    }
}
