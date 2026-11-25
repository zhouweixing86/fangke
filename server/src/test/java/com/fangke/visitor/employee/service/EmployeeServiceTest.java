package com.fangke.visitor.employee.service;

import com.fangke.visitor.config.AppProperties;
import com.fangke.visitor.employee.dto.EmployeeRequest;
import com.fangke.visitor.employee.dto.EmployeeView;
import com.fangke.visitor.employee.dto.ImportResult;
import com.fangke.visitor.employee.model.Employee;
import com.fangke.visitor.employee.model.EmployeeFace;
import com.fangke.visitor.employee.repository.DeptRepository;
import com.fangke.visitor.employee.repository.EmployeeFaceRepository;
import com.fangke.visitor.employee.repository.EmployeeRepository;
import com.fangke.visitor.common.BusinessException;
import com.fangke.visitor.common.ErrorCode;
import com.fangke.visitor.common.PageResult;
import com.fangke.visitor.security.CryptoService;
import com.fangke.visitor.storage.StorageService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmployeeServiceTest {

    /* ---------------- 测试替身 ---------------- */

    static class InMemoryEmployeeRepository implements EmployeeRepository {
        final Map<Long, Employee> store = new LinkedHashMap<>();
        final AtomicLong seq = new AtomicLong();

        @Override
        public long nextPersonSeq() {
            return store.size() + 1L;
        }

        @Override
        public long insert(Employee e) {
            long id = seq.incrementAndGet();
            e.setId(id);
            store.put(id, e);
            return id;
        }

        @Override
        public void update(Employee e) {
            store.put(e.getId(), e);
        }

        @Override
        public Optional<Employee> findById(Long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public Optional<Employee> findByEmpNo(String empNo) {
            return store.values().stream().filter(e -> e.getEmpNo().equals(empNo)).findFirst();
        }

        @Override
        public Optional<Employee> findByPhoneHmac(String phoneHmac) {
            return store.values().stream()
                    .filter(e -> phoneHmac != null && phoneHmac.equals(e.getPhoneHmac()))
                    .findFirst();
        }

        @Override
        public boolean existsEmpNo(String empNo, Long excludeId) {
            return store.values().stream()
                    .anyMatch(e -> e.getEmpNo().equals(empNo) && !e.getId().equals(excludeId));
        }

        @Override
        public boolean existsPhoneHmac(String hmac, Long excludeId) {
            return hmac != null && store.values().stream()
                    .anyMatch(e -> hmac.equals(e.getPhoneHmac()) && !e.getId().equals(excludeId));
        }

        @Override
        public boolean existsIdCardHmac(String hmac, Long excludeId) {
            return hmac != null && store.values().stream()
                    .anyMatch(e -> hmac.equals(e.getIdCardHmac()) && !e.getId().equals(excludeId));
        }

        @Override
        public List<Employee> search(String keyword, Integer status, int offset, int limit) {
            return store.values().stream()
                    .filter(e -> status == null || e.getStatus() == status)
                    .skip(offset).limit(limit).toList();
        }

        @Override
        public long count(String keyword, Integer status) {
            return store.values().stream().filter(e -> status == null || e.getStatus() == status).count();
        }

        @Override
        public void updateStatus(Long id, int status) {
            store.get(id).setStatus(status);
        }

        @Override
        public void updateFaceStatus(Long id, int faceStatus) {
            store.get(id).setFaceStatus(faceStatus);
        }

        @Override
        public List<Employee> findExpiringBefore(LocalDate deadline) {
            return store.values().stream()
                    .filter(e -> e.getValidTo() != null && !e.getValidTo().isAfter(deadline))
                    .toList();
        }
    }

    static class InMemoryFaceRepository implements EmployeeFaceRepository {
        final List<EmployeeFace> faces = new ArrayList<>();
        final AtomicLong seq = new AtomicLong();

        @Override
        public long insert(EmployeeFace f) {
            long id = seq.incrementAndGet();
            faces.add(new EmployeeFace(id, f.employeeId(), f.ossKey(), f.ossKeyStd(),
                    f.width(), f.height(), f.sizeBytes(), f.sha256(), f.current(), f.uploadedBy(), null));
            return id;
        }

        @Override
        public void deactivateOthers(Long employeeId) {
            faces.replaceAll(f -> f.employeeId().equals(employeeId) && f.current()
                    ? new EmployeeFace(f.id(), f.employeeId(), f.ossKey(), f.ossKeyStd(), f.width(),
                    f.height(), f.sizeBytes(), f.sha256(), false, f.uploadedBy(), f.createdAt())
                    : f);
        }

        @Override
        public Optional<EmployeeFace> findCurrent(Long employeeId) {
            return faces.stream().filter(f -> f.employeeId().equals(employeeId) && f.current()).findFirst();
        }

        @Override
        public List<EmployeeFace> listByEmployee(Long employeeId) {
            return faces.stream().filter(f -> f.employeeId().equals(employeeId)).toList();
        }
    }

    static class FakeDeptRepository implements DeptRepository {
        private final InMemoryDeptRepository delegate = new InMemoryDeptRepository()
                .put(1L, "IT", "信息部", 0L, 1, 0);

        @Override
        public List<Dept> findAll() {
            return delegate.findAll();
        }

        @Override
        public Optional<Dept> findById(Long id) {
            return delegate.findById(id);
        }

        @Override
        public Long findIdByCode(String code) {
            return delegate.findIdByCode(code);
        }

        @Override
        public long insert(String code, String name, Long parentId, int sortNo) {
            return delegate.insert(code, name, parentId, sortNo);
        }

        @Override
        public void update(Long id, String name, Long parentId, int sortNo) {
            delegate.update(id, name, parentId, sortNo);
        }

        @Override
        public void softDelete(Long id) {
            delegate.softDelete(id);
        }

        @Override
        public boolean existsCode(String code, Long excludeId) {
            return delegate.existsCode(code, excludeId);
        }
    }

    static class FakeStorage implements StorageService {
        final Map<String, byte[]> objects = new LinkedHashMap<>();

        @Override
        public String putObject(String key, byte[] data, String contentType) {
            objects.put(key, data);
            return key;
        }

        @Override
        public String putBase64(String key, String base64, String contentType) {
            objects.put(key, base64.getBytes());
            return key;
        }

        @Override
        public byte[] getObject(String key) {
            return objects.get(key);
        }

        @Override
        public String presignPut(String key, String contentType, Duration ttl) {
            return "http://minio.local/" + key + "?sig=test";
        }

        @Override
        public String presignGet(String key, Duration ttl) {
            return "http://minio.local/get/" + key;
        }

        @Override
        public boolean exists(String key) {
            return objects.containsKey(key);
        }

        @Override
        public boolean configured() {
            return true;
        }
    }

    /* ---------------- 固定数据 ---------------- */

    private static final String VALID_ID_CARD = "110101199003074477";
    private static final String VALID_PHONE = "13800138000";

    private InMemoryEmployeeRepository repo;
    private InMemoryFaceRepository faceRepo;
    private FakeStorage storage;
    private EmployeeService service;

    @BeforeEach
    void setUp() {
        repo = new InMemoryEmployeeRepository();
        faceRepo = new InMemoryFaceRepository();
        storage = new FakeStorage();
        AppProperties props = new AppProperties();
        props.getCrypto().setAesKey("unit-aes");
        props.getCrypto().setHmacKey("unit-hmac");
        service = new EmployeeService(repo, faceRepo, new FakeDeptRepository(),
                new CryptoService(props), storage, new PhotoValidator());
    }

    private EmployeeRequest request(String empNo, String phone, String idCard) {
        return new EmployeeRequest(empNo, "张三", 1L, null, "工程师",
                phone, idCard, LocalDate.of(2026, 1, 1), LocalDate.of(2028, 12, 31), null, null);
    }

    private byte[] jpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(java.awt.Color.ORANGE);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    /* ---------------- 用例 ---------------- */

    @Test
    @DisplayName("新增员工：personNo 规则、敏感字段加密脱敏、HMAC 入库")
    void createEmployee() {
        Employee e = service.create(request("000123", VALID_PHONE, VALID_ID_CARD), 1L);

        assertEquals("E000001", e.getPersonNo());
        assertEquals("138****8000", e.getPhoneMask());
        assertEquals("110101********4477", e.getIdCardMask());
        assertNotNull(e.getPhoneEnc());
        assertNotNull(e.getIdCardEnc());
        assertEquals(64, e.getPhoneHmac().length());
        assertEquals(Employee.STATUS_ACTIVE, e.getStatus());
        assertNull(e.getPhonePlain(), "明文不应残留在实体里");
    }

    @Test
    @DisplayName("车牌号：去空格转大写入库，空白存 null（二期车辆识别直接匹配）")
    void plateNoNormalized() {
        Employee withPlate = service.create(new EmployeeRequest("000901", "有车族", 1L, null, "工程师",
                "13800000901", null, null, null, null, " 京a 12345 "), 1L);
        assertEquals("京A12345", withPlate.getPlateNo());

        Employee noPlate = service.create(new EmployeeRequest("000902", "无车族", 1L, null, "工程师",
                "13800000902", null, null, null, null, "   "), 1L);
        assertNull(noPlate.getPlateNo());
    }

    @Test
    @DisplayName("工号重复被拒")
    void duplicateEmpNo() {
        service.create(request("000123", VALID_PHONE, VALID_ID_CARD), 1L);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(request("000123", "13900139000", null), 1L));
        assertEquals(ErrorCode.PARAM_INVALID.code(), e.getCode());
    }

    @Test
    @DisplayName("手机号格式错误被拒（1005）")
    void invalidPhone() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(request("000124", "12345", null), 1L));
        assertEquals(ErrorCode.PHONE_INVALID.code(), e.getCode());
    }

    @Test
    @DisplayName("身份证校验位错误被拒（1004）")
    void invalidIdCard() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(request("000125", null, "110101199003074478"), 1L));
        assertEquals(ErrorCode.ID_CARD_INVALID.code(), e.getCode());
    }

    @Test
    @DisplayName("手机号重复被拒（HMAC 唯一）")
    void duplicatePhone() {
        service.create(request("000126", VALID_PHONE, VALID_ID_CARD), 1L);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(request("000127", VALID_PHONE, null), 1L));
        assertEquals(ErrorCode.PARAM_INVALID.code(), e.getCode());
    }

    @Test
    @DisplayName("编辑：换手机号会重新加密并更新脱敏值")
    void updatePhone() {
        Employee created = service.create(request("000128", VALID_PHONE, VALID_ID_CARD), 1L);
        String oldHmac = created.getPhoneHmac();

        Employee updated = service.update(created.getId(),
                request("000128", "13900139000", VALID_ID_CARD), 1L);

        assertEquals("139****9000", updated.getPhoneMask());
        assertFalse(oldHmac.equals(updated.getPhoneHmac()));
        assertEquals("张三", updated.getName());
    }

    @Test
    @DisplayName("停用后不可刷脸通行")
    void stopEmployee() {
        Employee created = service.create(request("000129", VALID_PHONE, null), 1L);
        service.stop(created.getId(), 1L);

        Employee stopped = service.get(created.getId());
        assertEquals(Employee.STATUS_DISABLED, stopped.getStatus());
        assertFalse(stopped.canPassByFace(LocalDate.now()));
    }

    @Test
    @DisplayName("照片绑定：上传压缩件、写 face 记录、标记已上传")
    void bindFace() throws IOException {
        Employee created = service.create(request("000130", VALID_PHONE, null), 1L);
        byte[] photo = jpeg(500, 600);

        EmployeeFace face = service.bindFace(created.getId(), null, photo, 1L);

        assertTrue(face.current());
        assertTrue(storage.objects.keySet().stream().anyMatch(k -> k.startsWith("emp-face-std/")));
        assertTrue(storage.objects.keySet().stream().anyMatch(k -> k.startsWith("emp-face/")));
        assertEquals(Employee.FACE_UPLOADED, service.get(created.getId()).getFaceStatus());
        assertEquals(1, faceRepo.listByEmployee(created.getId()).size());
    }

    @Test
    @DisplayName("替换照片：旧记录置为非当前，历史保留（BR-E-005）")
    void replaceFaceKeepsHistory() throws IOException {
        Employee created = service.create(request("000131", VALID_PHONE, null), 1L);
        service.bindFace(created.getId(), null, jpeg(500, 600), 1L);
        service.bindFace(created.getId(), null, jpeg(520, 620), 2L);

        List<EmployeeFace> history = faceRepo.listByEmployee(created.getId());
        assertEquals(2, history.size());
        assertEquals(1, history.stream().filter(EmployeeFace::current).count());
    }

    @Test
    @DisplayName("预签名上传返回 key 与 PUT URL")
    void presign() {
        Employee created = service.create(request("000132", VALID_PHONE, null), 1L);
        Map<String, Object> result = service.presignUpload(created.getId(), "photo.jpg");

        assertTrue(String.valueOf(result.get("ossKey")).startsWith("emp-face/"));
        assertTrue(String.valueOf(result.get("uploadUrl")).contains("sig=test"));
    }

    @Test
    @DisplayName("列表只返回脱敏值；无照片时 canPassByFace=false")
    void listIsMasked() {
        service.create(request("000133", VALID_PHONE, VALID_ID_CARD), 1L);
        PageResult<EmployeeView> page = service.list(null, null, 1, 20);

        assertEquals(1, page.getTotal());
        EmployeeView view = page.getList().get(0);
        assertEquals("138****8000", view.phoneMask());
        assertEquals("110101********4477", view.idCardMask());
        assertFalse(view.canPassByFace());
        assertFalse(view.toString().contains(VALID_ID_CARD), "返回体不能含完整证件号");
    }

    @Test
    @DisplayName("Excel 导入：合法行成功、非法行返回行号与原因")
    void importExcel() throws IOException {
        byte[] excel;
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("员工");
            Row header = sheet.createRow(0);
            String[] titles = {"工号", "姓名", "部门编码", "职务", "手机号", "身份证号", "有效期起", "有效期止", "备注"};
            for (int i = 0; i < titles.length; i++) {
                header.createCell(i).setCellValue(titles[i]);
            }
            Row ok = sheet.createRow(1);
            ok.createCell(0).setCellValue("000201");
            ok.createCell(1).setCellValue("李四");
            ok.createCell(2).setCellValue("IT");
            ok.createCell(4).setCellValue(VALID_PHONE);
            ok.createCell(5).setCellValue(VALID_ID_CARD);
            ok.createCell(6).setCellValue("2026-01-01");
            ok.createCell(7).setCellValue("2028-12-31");

            Row bad = sheet.createRow(2);
            bad.createCell(0).setCellValue("000202");
            bad.createCell(1).setCellValue("王五");
            bad.createCell(4).setCellValue("12345");

            wb.write(out);
            excel = out.toByteArray();
        }

        ImportResult result = service.importExcel(new ByteArrayInputStream(excel), 1L);

        assertEquals(2, result.total());
        assertEquals(1, result.success());
        assertEquals(1, result.errors().size());
        assertEquals(3, result.errors().get(0).row());
        assertTrue(result.errors().get(0).message().contains("手机号"));
        assertEquals("E000001", repo.findByEmpNo("000201").orElseThrow().getPersonNo());
    }

    @Test
    @DisplayName("工号里的数字相同也不会生成同一个人员编号（原来会撞唯一键，建档直接失败）")
    void personNoIsUniqueForSimilarEmpNos() {
        Employee a = service.create(request("JS0001", VALID_PHONE, VALID_ID_CARD), 1L);
        Employee b = service.create(request("FINAL001", "13900000002", null), 1L);

        assertNotEquals(a.getPersonNo(), b.getPersonNo(), "不同工号必须拿到不同人员编号");
        assertTrue(a.getPersonNo().matches("E\\d{6}"));
        assertTrue(b.getPersonNo().matches("E\\d{6}"));
    }
}
