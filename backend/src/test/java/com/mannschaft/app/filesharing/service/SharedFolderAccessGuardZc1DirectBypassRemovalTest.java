package com.mannschaft.app.filesharing.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.filesharing.FileScopeType;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
import com.mannschaft.app.filesharing.FileVisibilityRole;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 戦役 Z-C 第1陣（試練・CMP-261127-1709）: {@link SharedFolderAccessGuard} の SYSTEM_ADMIN 直書き素通し
 * （B: 最低可視ロール・C: ダウンロード禁止の貫通）を除去する受け入れテスト。
 *
 * <p>分類表 {@code src/test/resources/z_c_1/classification.tsv} の A 行（authorizeDownload:67・
 * applyMinVisibleRole:168・resolveVisibleFileLevels:195）に対応する。所属の無い運営アカウントは、
 * 同じ基本認可を通った一般の閲覧者（ロールなし）と<b>同じ応答</b>になる。基本認可（checkMembership・大会スコープの
 * 連絡スペース判定）はモックで通しているので、本テストが見るのはガード自身の短絡だけ。</p>
 *
 * <p>実装前（現状）は運営アカウントだけが貫通するので赤。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Z-C1: SharedFolderAccessGuard の SYSTEM_ADMIN 直書き素通しの除去")
class SharedFolderAccessGuardZc1DirectBypassRemovalTest {

    @Mock private AccessControlService accessControlService;
    @Mock private FolderScopeAccessGuard folderScopeAccessGuard;
    @InjectMocks private SharedFolderAccessGuard guard;

    /** 所属の無い運営アカウント。 */
    private static final Long PLATFORM = 900L;
    /** 基本認可は通ったがスコープのロールを持たない一般利用者（比較の基準）。 */
    private static final Long PLAIN_VIEWER = 901L;
    /** スコープ ADMIN（回帰）。 */
    private static final Long SCOPE_ADMIN = 902L;

    private static final Long TEAM_ID = 5L;
    private static final Long ORG_ID = 7L;
    private static final Long TOURNAMENT_REF_ID = 42L;

    @BeforeEach
    void setUp() {
        given(accessControlService.isSystemAdmin(PLATFORM)).willReturn(true);
        given(accessControlService.isSystemAdmin(PLAIN_VIEWER)).willReturn(false);
        given(accessControlService.isSystemAdmin(SCOPE_ADMIN)).willReturn(false);
        // 運営アカウント・一般利用者はどのスコープにもロールが無い（hasRoleOrAbove は SCOPE_ADMIN だけ true）。
        given(accessControlService.hasRoleOrAbove(any(), anyLong(), anyString(), anyString()))
                .willAnswer(inv -> SCOPE_ADMIN.equals(inv.getArgument(0)));
    }

    private static SharedFolderEntity teamFolder(FileVisibilityRole minRole, boolean dlDisabled) {
        return SharedFolderEntity.builder()
                .id(100L).scopeType(FileScopeType.TEAM).teamId(TEAM_ID)
                .minVisibleRole(minRole).downloadDisabled(dlDisabled)
                .name("チームフォルダ").createdBy(1L).build();
    }

    private static SharedFolderEntity tournamentFolder(boolean dlDisabled) {
        return SharedFolderEntity.builder()
                .id(101L).scopeType(FileScopeType.TOURNAMENT).organizationId(ORG_ID)
                .scopeRefId(TOURNAMENT_REF_ID).downloadDisabled(dlDisabled)
                .name("大会フォルダ").createdBy(1L).build();
    }

    private static SharedFileEntity fileIn(SharedFolderEntity folder, FileVisibilityRole minRole, boolean dlDisabled) {
        return SharedFileEntity.builder()
                .id(300L).folderId(folder.getId()).name("doc.pdf").fileKey("k").fileSize(10L)
                .contentType("application/pdf").createdBy(1L).currentVersion(1)
                .minVisibleRole(minRole).downloadDisabled(dlDisabled).build();
    }

    private static ErrorCode codeOf(ThrowingCallable call) {
        Throwable[] holder = new Throwable[1];
        try {
            call.call();
        } catch (Throwable t) {
            holder[0] = t;
        }
        return holder[0] instanceof BusinessException be ? be.getErrorCode() : null;
    }

    @Test
    @DisplayName("A(authorizeDownload): ダウンロード禁止のチームフォルダのファイルは、運営アカウントにも DOWNLOAD_DISABLED（一般の閲覧者と同じ）")
    void authorizeDownload_teamDlDisabled_platformIsDenied() {
        SharedFolderEntity folder = teamFolder(null, true);
        SharedFileEntity file = fileIn(folder, null, false);

        ErrorCode plain = codeOf(() -> guard.authorizeDownload(folder, file, PLAIN_VIEWER));
        assertThat(plain).as("前提: 一般の閲覧者は DOWNLOAD_DISABLED").isEqualTo(FileSharingErrorCode.DOWNLOAD_DISABLED);

        assertThatThrownBy(() -> guard.authorizeDownload(folder, file, PLATFORM))
                .as("運営アカウントがダウンロード禁止を貫通してはならない（署名 URL＝実データに届く）")
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(plain));
    }

    @Test
    @DisplayName("A(authorizeDownload): 大会フォルダ（連絡スペース判定を通った前提）でも、ファイル側の DL 禁止は運営アカウントに DOWNLOAD_DISABLED")
    void authorizeDownload_tournamentFileDlDisabled_platformIsDenied() {
        SharedFolderEntity folder = tournamentFolder(false);
        SharedFileEntity file = fileIn(folder, null, true);

        assertThat(codeOf(() -> guard.authorizeDownload(folder, file, PLAIN_VIEWER)))
                .isEqualTo(FileSharingErrorCode.DOWNLOAD_DISABLED);
        assertThat(codeOf(() -> guard.authorizeDownload(folder, file, PLATFORM)))
                .as("運営アカウントもファイル側の DL 禁止で止まる")
                .isEqualTo(FileSharingErrorCode.DOWNLOAD_DISABLED);
    }

    @Test
    @DisplayName("A(applyMinVisibleRole): 最低可視ロール ADMIN のフォルダは、ロールの無い運営アカウントに COMMON_002（一般の閲覧者と同じ）")
    void applyMinVisibleRole_platformIsDeniedLikePlainViewer() {
        SharedFolderEntity folder = teamFolder(FileVisibilityRole.ADMINS_AND_ABOVE, false);

        assertThat(codeOf(() -> guard.authorizeView(folder, PLAIN_VIEWER))).isEqualTo(CommonErrorCode.COMMON_002);
        assertThat(codeOf(() -> guard.authorizeView(folder, PLATFORM)))
                .as("運営アカウントは最低可視ロールを貫通しない")
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("A(applyMinVisibleRole): ファイル個別の最低可視ロール MEMBER も、運営アカウントに COMMON_002")
    void authorizeFileView_fileMinRole_platformIsDenied() {
        SharedFolderEntity folder = teamFolder(null, false);
        SharedFileEntity file = fileIn(folder, FileVisibilityRole.MEMBERS_AND_ABOVE, false);

        assertThat(codeOf(() -> guard.authorizeFileView(folder, file, PLATFORM)))
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("A(resolveVisibleFileLevels): 一覧の絞り込みは運営アカウントに「全許可(null)」を返さず、一般の閲覧者と同じ空集合")
    void resolveVisibleFileLevels_platformGetsSameAsPlainViewer() {
        SharedFolderEntity folder = teamFolder(null, false);

        assertThat(guard.resolveVisibleFileLevels(folder, PLAIN_VIEWER)).isEmpty();
        assertThat(guard.resolveVisibleFileLevels(folder, PLATFORM))
                .as("null（フィルタ不要＝最低可視ロール付きファイルのファイル名まで一覧に出る）を返してはならない")
                .isNotNull()
                .isEmpty();
    }

    @Test
    @DisplayName("回帰: スコープ ADMIN は従来どおり最低可視ロールを満たし、DL 禁止でないファイルは DL できる")
    void regression_scopeAdmin() {
        SharedFolderEntity folder = teamFolder(FileVisibilityRole.ADMINS_AND_ABOVE, false);
        SharedFileEntity file = fileIn(folder, null, false);

        assertThatCode(() -> guard.authorizeDownload(folder, file, SCOPE_ADMIN)).doesNotThrowAnyException();
        assertThat(guard.resolveVisibleFileLevels(folder, SCOPE_ADMIN))
                .isEqualTo(EnumSet.allOf(FileVisibilityRole.class));
    }

    @Test
    @DisplayName("回帰: スコープ ADMIN でも DL 禁止は貫通しない（従来どおり）")
    void regression_scopeAdminStillBlockedByDlDisabled() {
        SharedFolderEntity folder = teamFolder(null, true);
        SharedFileEntity file = fileIn(folder, null, false);

        assertThat(codeOf(() -> guard.authorizeDownload(folder, file, SCOPE_ADMIN)))
                .isEqualTo(FileSharingErrorCode.DOWNLOAD_DISABLED);
    }
}
