package com.mannschaft.app.filesharing;

import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
import com.mannschaft.app.support.test.scopecontent.AbstractScopeContentAccessMatrixIT;
import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CMP-261124-0159 PR-D1: ファイル共有の Matrix IT の共通基底（非公開チームに、フォルダ 1 件とその中のファイル 1 件を置く）。
 */
abstract class AbstractFilesharingScopeContentMatrixIT extends AbstractScopeContentAccessMatrixIT {

    @Autowired private SharedFolderRepository folderRepository;
    @Autowired private SharedFileRepository fileRepository;
    @Autowired private PlatformTransactionManager filesTransactionManager;

    protected Long folderId;
    protected Long fileId;

    @Override
    protected void seed(ScopeContentFixture fixture) {
        folderId = folderRepository.save(SharedFolderEntity.builder()
                .scopeType(FileScopeType.TEAM).teamId(fixture.teamId).name("PRD1 フォルダ")
                .createdBy(fixture.user(ScopeContentFixture.Actor.ADMIN)).build()).getId();
        fileId = fileRepository.save(SharedFileEntity.builder()
                .folderId(folderId).name("prd1-secret.txt").fileKey("c59/prd1/" + folderId + "/secret.txt")
                .fileSize(10L).contentType("text/plain").description("PR-D1（権限なしに見せてはならない）")
                .createdBy(fixture.user(ScopeContentFixture.Actor.ADMIN)).build()).getId();
    }

    @Override
    protected String resolveParam(String name, String value) {
        return switch (value) {
            case "@folder" -> String.valueOf(folderId);
            case "@file" -> String.valueOf(fileId);
            default -> super.resolveParam(name, value);
        };
    }

    @AfterEach
    void deleteFileRows() {
        if (fixture == null || folderId == null) {
            return;
        }
        new TransactionTemplate(filesTransactionManager).executeWithoutResult(t -> {
            em.createNativeQuery("DELETE FROM shared_files WHERE folder_id = :id").setParameter("id", folderId)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM shared_folders WHERE team_id = :id").setParameter("id", fixture.teamId)
                    .executeUpdate();
        });
    }
}
