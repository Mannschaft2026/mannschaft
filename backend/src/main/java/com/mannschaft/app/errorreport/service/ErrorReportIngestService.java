package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.supportaccess.context.SupportAccessWritable;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Redis 観測を TX 外で済ませ、毎回別 REQUIRED TX を開始する指揮役。
 *
 * <p>F10.9 サポート閲覧（代理）中は DB 接続が READ ONLY になる。障害記録（error_reports）は運営の閲覧とは
 * 別の障害情報であり、代理中の例外・スロー検知の記録が READ ONLY で失敗してログだけになると障害が失われる。
 * そのため本クラスは代理中でも書込可能な接続で書く（{@link SupportAccessWritable}）。
 * 記録するのは例外・パス・IP 等の障害情報のみで、本人の業務データは書かない（userId は常に null）。</p>
 */
@Service
@RequiredArgsConstructor
@SupportAccessWritable
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ErrorReportIngestService {
    private final ErrorReportIngestTx ingestTx;
    private final ErrorReportWriteRetry retry;
    private final StringRedisTemplate redisTemplate;

    ErrorReportEntity ingest(ErrorReportEntity candidate, ErrorReportIngestTx.Source source) {
        int affected = -1;
        if (source == ErrorReportIngestTx.Source.FE && candidate.getUserId() != null) {
            String key = "error-report:affected:" + candidate.getErrorHash();
            redisTemplate.opsForSet().add(key, candidate.getUserId().toString());
            redisTemplate.expire(key, Duration.ofDays(90));
            Long size = redisTemplate.opsForSet().size(key);
            if (size != null) affected = size.intValue();
        }
        int observedAffected = affected;
        return retry.ingest(attempt -> ingestTx.ingestOnce(candidate, source, observedAffected, attempt));
    }
}
