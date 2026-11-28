package com.mannschaft.app.team.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.team.dto.DeputyShiftViewToggles;
import com.mannschaft.app.team.dto.TeamShiftSettingsResponse;
import com.mannschaft.app.team.dto.UpdateTeamShiftSettingsRequest;
import com.mannschaft.app.team.entity.TeamShiftSettingsEntity;
import com.mannschaft.app.team.repository.TeamShiftSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * チームシフト設定サービス。リマインド間隔カスタマイズの取得・更新・初期化を担う。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TeamShiftSettingsService {

    private final TeamShiftSettingsRepository settingsRepository;

    /**
     * チームシフト設定を取得する。
     * 設定が存在しない場合はデフォルト値で自動作成する。
     */
    @Transactional
    public TeamShiftSettingsResponse getSettings(Long teamId) {
        TeamShiftSettingsEntity settings = settingsRepository.findByTeamId(teamId)
                .orElseGet(() -> {
                    log.info("チームシフト設定が存在しないためデフォルト作成: teamId={}", teamId);
                    return settingsRepository.save(TeamShiftSettingsEntity.createDefault(teamId));
                });
        return TeamShiftSettingsResponse.from(settings);
    }

    /**
     * 副管理者への閲覧許可の切替を取得する（読み取り専用。設定行が無ければ作らずに既定の OFF を返す）。
     *
     * <p>shift ドメインの認可が閲覧可否の判定に使う（CMP-261125-2114）。</p>
     */
    @Transactional(readOnly = true)
    public DeputyShiftViewToggles findDeputyViewToggles(Long teamId) {
        return settingsRepository.findByTeamId(teamId)
                .map(s -> new DeputyShiftViewToggles(s.isDeputyShiftViewEnabled(), s.isDeputyHourlyRateViewEnabled()))
                .orElse(DeputyShiftViewToggles.OFF);
    }

    /**
     * チームシフト設定を更新する（部分更新。リクエストの {@code null} の項目は現在値を維持する）。
     * 設定が存在しない場合は作成してから更新する。
     *
     * <p>「少なくとも 1 つのリマインドが有効」は<b>更新後の状態</b>で検証する。満たさなければ 400
     * {@code COMMON_001}（保存しない）。</p>
     */
    @Transactional
    public TeamShiftSettingsResponse updateSettings(Long teamId, UpdateTeamShiftSettingsRequest request) {
        TeamShiftSettingsEntity settings = settingsRepository.findByTeamId(teamId)
                .orElseGet(() -> TeamShiftSettingsEntity.createDefault(teamId));
        settings.update(
                request.getReminder48hEnabled(),
                request.getReminder24hEnabled(),
                request.getReminder12hEnabled());
        if (!settings.isAnyReminderEnabled()) {
            throw new BusinessException(CommonErrorCode.COMMON_001, List.of(new ErrorResponse.FieldError(
                    "reminder48hEnabled", "少なくとも1つのリマインドを有効にする必要があります")));
        }
        settings.updateDeputyViewToggles(request.getDeputyShiftViewEnabled(), request.getDeputyHourlyRateViewEnabled());
        return TeamShiftSettingsResponse.from(settingsRepository.save(settings));
    }

    /**
     * チーム作成時にデフォルト設定を自動生成する。
     * TeamService のチーム作成フローから呼ばれる。
     */
    @Transactional
    public void initializeDefaultSettings(Long teamId) {
        if (settingsRepository.findByTeamId(teamId).isEmpty()) {
            settingsRepository.save(TeamShiftSettingsEntity.createDefault(teamId));
            log.info("チームシフト設定を初期化: teamId={}", teamId);
        }
    }
}
