package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Repository;

/** Spring Data fragment。内容編集時の親・希望の current read だけを担う。 */
@Repository
@RequiredArgsConstructor
class ShiftRequestRepositoryImpl implements ShiftRequestRepositoryCustom {

    private final EntityManager entityManager;

    @Override
    public void refreshForPreferenceUpdate(ShiftScheduleEntity parentSchedule, ShiftRequestEntity request) {
        try {
            entityManager.refresh(parentSchedule, LockModeType.PESSIMISTIC_WRITE);
            entityManager.refresh(request, LockModeType.PESSIMISTIC_READ);
        } catch (EntityNotFoundException e) {
            // Repository 境界では業務例外を投げず、Service で希望の不在コードへ写像する。
            throw new EmptyResultDataAccessException("更新する希望または親スケジュールが存在しません", 1, e);
        }
    }
}
