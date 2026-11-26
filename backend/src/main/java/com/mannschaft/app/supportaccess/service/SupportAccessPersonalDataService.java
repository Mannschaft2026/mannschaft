package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.supportaccess.dto.SupportAccessLogView;
import com.mannschaft.app.supportaccess.port.SupportAccessUserDirectory;
import com.mannschaft.app.supportaccess.repository.SupportAccessLogRepository;

import java.util.List;

/** F10.9 本人の閲覧記録（設定画面と GDPR エクスポートの両方で使う）。運営者のメールは出さない。試練スタブ。 */
public class SupportAccessPersonalDataService {

    private final SupportAccessLogRepository logRepository;
    private final SupportAccessUserDirectory userDirectory;

    public SupportAccessPersonalDataService(SupportAccessLogRepository logRepository,
                                            SupportAccessUserDirectory userDirectory) {
        this.logRepository = logRepository;
        this.userDirectory = userDirectory;
    }

    public List<SupportAccessLogView> listLogsForTarget(Long targetUserId) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }
}
