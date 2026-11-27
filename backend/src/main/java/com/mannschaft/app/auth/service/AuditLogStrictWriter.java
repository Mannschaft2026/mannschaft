package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.entity.AuditLogEntity;
import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.supportaccess.context.SupportAccessWritable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 監査ログを<b>呼び出し元の TX から独立した</b>書込可能な TX で書く（別 Bean・REQUIRES_NEW）。失敗は例外で返す。
 *
 * <p>{@link AuditLogService#recordSync} を業務 TX の中から呼ぶと、その TX の接続に参加する。F10.9 の代理閲覧中は
 * 業務 TX の接続が READ ONLY のため INSERT が失敗し、監査が消える。本クラスは REQUIRES_NEW で新しい接続を取り、
 * {@link SupportAccessWritable}（{@code SupportAccessWritableAspect} が TX 開始より外側で READ ONLY の強制を外す）で
 * 書込可能な接続にする。呼び出し元が巻き戻っても監査は残る。</p>
 *
 * <p>付与先は番人 {@code SupportAccessEnforcementGuardTest} が固定する（業務の書込には付けない）。</p>
 */
@Component
@SupportAccessWritable
@RequiredArgsConstructor
public class AuditLogStrictWriter {

    private final AuditLogRepository auditLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AuditLogEntity entity) {
        auditLogRepository.saveAndFlush(entity);
    }
}
