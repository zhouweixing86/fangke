package com.fangke.visitor.appointment.service;

import com.fangke.visitor.appointment.model.Visitor;
import com.fangke.visitor.appointment.repository.VisitorRepository;
import com.fangke.visitor.security.CryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 访客档案：按身份证号复用（同一个人多次来访只有一条档案）。
 */
@Service
public class VisitorService {

    private static final Logger log = LoggerFactory.getLogger(VisitorService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final VisitorRepository repository;
    private final CryptoService crypto;

    public VisitorService(VisitorRepository repository, CryptoService crypto) {
        this.repository = repository;
        this.crypto = crypto;
    }

    /**
     * 按身份证号查已有档案；没有则新建。
     *
     * @return 档案实体（id 一定有值）
     */
    @Transactional
    public Visitor resolveOrCreate(String name, String idCard, String phone, String company) {
        String hmac = crypto.hmac(idCard);
        Optional<Visitor> existing = repository.findByIdCardHmac(hmac);
        if (existing.isPresent()) {
            Visitor v = existing.get();
            boolean changed = false;
            if (name != null && !name.isBlank() && !name.equals(v.getName())) {
                v.setName(name.trim());
                changed = true;
            }
            if (company != null && !company.isBlank() && !company.equals(v.getCompany())) {
                v.setCompany(company.trim());
                changed = true;
            }
            if (phone != null && !phone.isBlank() && v.getPhoneHmac() == null) {
                v.setPhoneEnc(crypto.encrypt(phone));
                v.setPhoneHmac(crypto.hmac(phone));
                v.setPhoneMask(CryptoService.maskPhone(phone));
                changed = true;
            }
            if (changed) {
                repository.update(v);
            }
            return v;
        }

        Visitor visitor = new Visitor();
        visitor.setPersonNo(nextPersonNo());
        visitor.setName(name.trim());
        visitor.setIdCardEnc(crypto.encrypt(idCard));
        visitor.setIdCardHmac(hmac);
        visitor.setIdCardMask(CryptoService.maskIdCard(idCard));
        if (phone != null && !phone.isBlank()) {
            visitor.setPhoneEnc(crypto.encrypt(phone));
            visitor.setPhoneHmac(crypto.hmac(phone));
            visitor.setPhoneMask(CryptoService.maskPhone(phone));
        }
        visitor.setCompany(company);
        long id = repository.insert(visitor);
        visitor.setId(id);
        log.info("新建访客档案 personNo={} idCard={}", visitor.getPersonNo(), visitor.getIdCardMask());
        return visitor;
    }

    /** 记录一次到访（首次通行或提交预约时调用）。 */
    @Transactional
    public void touchVisit(Visitor visitor) {
        LocalDateTime now = LocalDateTime.now();
        if (visitor.getFirstVisitAt() == null) {
            visitor.setFirstVisitAt(now);
        }
        visitor.setLastVisitAt(now);
        visitor.setVisitCount(visitor.getVisitCount() + 1);
        repository.update(visitor);
    }

    public void agree(Long visitorId, int consentType, String version, String ip, String ua) {
        repository.recordConsent(visitorId, consentType, version, ip, ua);
        log.info("访客同意记录 visitorId={} type={} version={}", visitorId, consentType, version);
    }

    private String nextPersonNo() {
        String prefix = "V" + DAY.format(LocalDate.now());
        Optional<String> max = repository.findMaxPersonNoByPrefix(prefix);
        int seq = max.map(s -> {
            String tail = s.substring(prefix.length());
            try {
                return Integer.parseInt(tail) + 1;
            } catch (NumberFormatException e) {
                return 1;
            }
        }).orElse(1);
        return prefix + String.format("%04d", seq);
    }
}
