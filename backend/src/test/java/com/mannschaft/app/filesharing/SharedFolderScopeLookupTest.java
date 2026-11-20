package com.mannschaft.app.filesharing;

import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
import com.mannschaft.app.filesharing.service.SharedFolderScopeLookup;
import com.mannschaft.app.filesharing.service.SharedFolderScopeLookup.FolderScopeRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * {@link SharedFolderScopeLookup} の単体テスト（CMP-261116-0726）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SharedFolderScopeLookup 単体テスト")
class SharedFolderScopeLookupTest {

    @Mock
    private SharedFolderRepository folderRepository;
    @Mock
    private SharedFileRepository fileRepository;

    @InjectMocks
    private SharedFolderScopeLookup lookup;

    private static final Long FOLDER_ID = 1L;
    private static final Long FILE_ID = 2L;

    private SharedFolderEntity tournamentFolder() {
        return SharedFolderEntity.builder()
                .scopeType(FileScopeType.TOURNAMENT).organizationId(5L).scopeRefId(100L).name("大会").build();
    }

    private SharedFileEntity file() {
        return SharedFileEntity.builder()
                .folderId(FOLDER_ID).name("a.pdf").fileKey("k").fileSize(1L).contentType("application/pdf").build();
    }

    @Test
    @DisplayName("フォルダ ID: スコープ種別とスコープ参照 ID を返す")
    void フォルダID解決() {
        given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(tournamentFolder()));

        assertThat(lookup.findByFolderId(FOLDER_ID))
                .contains(new FolderScopeRef(FileScopeType.TOURNAMENT, 100L));
    }

    @Test
    @DisplayName("フォルダ ID: 不在は空")
    void フォルダ不在は空() {
        given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.empty());

        assertThat(lookup.findByFolderId(FOLDER_ID)).isEmpty();
    }

    @Test
    @DisplayName("ファイル ID: ファイル→所属フォルダのスコープを返す")
    void ファイルID解決() {
        given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file()));
        given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(tournamentFolder()));

        assertThat(lookup.findByFileId(FILE_ID))
                .contains(new FolderScopeRef(FileScopeType.TOURNAMENT, 100L));
    }

    @Test
    @DisplayName("ファイル ID: ファイル不在は空")
    void ファイル不在は空() {
        given(fileRepository.findById(FILE_ID)).willReturn(Optional.empty());

        assertThat(lookup.findByFileId(FILE_ID)).isEmpty();
    }

    @Test
    @DisplayName("ファイル ID: ファイルは実在するが所属フォルダ不在なら空")
    void 所属フォルダ不在は空() {
        given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file()));
        given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.empty());

        assertThat(lookup.findByFileId(FILE_ID)).isEmpty();
    }
}
