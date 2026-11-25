package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.common.BusinessException;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 確認済みの予算警告を、上書き指定なしで再確認しようとした例外（CMP-260910-1607 ③・409）。
 *
 * <p>先に確認した人と時刻を {@code error.details} で返し、利用者に「上書きするか」を確認できるようにする。</p>
 */
@Getter
public class BudgetAlertAlreadyAcknowledgedException extends BusinessException {

    private final Long acknowledgedBy;
    /** 確認者の氏名。引けなかった場合は null。 */
    private final String acknowledgedByName;
    private final LocalDateTime acknowledgedAt;

    public BudgetAlertAlreadyAcknowledgedException(Long acknowledgedBy, String acknowledgedByName,
                                                   LocalDateTime acknowledgedAt) {
        super(ShiftBudgetErrorCode.ALERT_ALREADY_ACKNOWLEDGED);
        this.acknowledgedBy = acknowledgedBy;
        this.acknowledgedByName = acknowledgedByName;
        this.acknowledgedAt = acknowledgedAt;
    }

    /**
     * 確認者の氏名を付け直した例外を返す。
     * 氏名の解決は他ドメインの Repository に届くため、{@code @Transactional} の Service ではなく
     * 非トランザクションの Controller で行う（D-3T）。
     */
    public BudgetAlertAlreadyAcknowledgedException withAcknowledgedByName(String name) {
        return new BudgetAlertAlreadyAcknowledgedException(acknowledgedBy, name, acknowledgedAt);
    }
}
