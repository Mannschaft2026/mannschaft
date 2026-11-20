package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;

/** 希望内容編集時の current read に限定した永続化操作。 */
public interface ShiftRequestRepositoryCustom {

    /**
     * 親の排他ロック取得後、親→希望の順に最新の管理状態へ読み直す。
     *
     * @param parentSchedule ロック済みの親スケジュール
     * @param request 内容編集対象の希望
     * @throws org.springframework.dao.EmptyResultDataAccessException 永続化行が消えた場合
     */
    void refreshForPreferenceUpdate(ShiftScheduleEntity parentSchedule, ShiftRequestEntity request);
}
