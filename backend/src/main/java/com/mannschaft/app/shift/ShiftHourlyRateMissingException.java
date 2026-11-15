package com.mannschaft.app.shift;

import com.mannschaft.app.common.BusinessException;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/**
 * 予算連動の勤務日に、有効な時給のない割当者がいるためシフト公開を拒否する例外（CMP-261115-1821・400）。
 *
 * <p>欠落した（ユーザー, 勤務日）を {@code error.details.missingHourlyRates} で返す。</p>
 */
@Getter
public class ShiftHourlyRateMissingException extends BusinessException {

    /** 欠落した（ユーザー, 勤務日）。 */
    public record Missing(Long userId, LocalDate slotDate) {
    }

    private final transient List<Missing> missingHourlyRates;

    public ShiftHourlyRateMissingException(List<Missing> missingHourlyRates) {
        super(ShiftErrorCode.HOURLY_RATE_MISSING_FOR_PUBLICATION);
        this.missingHourlyRates = List.copyOf(missingHourlyRates);
    }
}
