package com.mannschaft.app.support.test.scopecontent;

import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.gallery.GalleryMediaType;
import com.mannschaft.app.gallery.GalleryProcessingStatus;
import com.mannschaft.app.gallery.entity.PhotoAlbumEntity;
import com.mannschaft.app.gallery.entity.PhotoEntity;
import com.mannschaft.app.gallery.repository.PhotoAlbumRepository;
import com.mannschaft.app.gallery.repository.PhotoRepository;
import org.junit.jupiter.api.AfterEach;
import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture.Actor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * CMP-261124-0159 PR-D2: ギャラリー（写真一覧・画像 URL 発行）の親チーム上限 Matrix IT（F00 §20.9 AC-0159-18）。
 *
 * <p>非公開チームでは ADMIN / DEPUTY_ADMIN / MEMBER だけが写真一覧を読め、画像の署名付き URL を受け取れる。
 * 在籍サポーター（是正前データ）・ゲスト・部外者・他チーム・他組織は部外者と同一の 403（COMMON_002）で、
 * 署名付き URL は発行されない（R2 は外部依存のためモック。発行が呼ばれたかではなく応答で判定する）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261124-0159 PR-D2 gallery の親チーム上限 Matrix IT（AC-0159-18）")
class GalleryScopeContentAccessMatrixIT extends AbstractScopeContentAccessMatrixIT {

    @MockitoBean private R2StorageService r2StorageService;
    @Autowired private PhotoAlbumRepository albumRepository;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private PlatformTransactionManager tm;

    private static final String ALBUM_TITLE = "上限試練のアルバム";

    private Long albumId;
    private Long photoId;

    @Override
    protected Path matrixFile() {
        return Paths.get("src", "test", "resources", "scope_content_matrix", "_gallery.yaml");
    }

    @Override
    protected String resolveParam(String name, String value) {
        return switch (value) {
            case "@album" -> String.valueOf(albumId);
            case "@photo" -> String.valueOf(photoId);
            default -> super.resolveParam(name, value);
        };
    }

    @Override
    protected void seed(ScopeContentFixture fx) {
        given(r2StorageService.generateDownloadUrl(any(), any())).willReturn("https://r2.example.com/signed");
        albumId = albumRepository.save(PhotoAlbumEntity.builder().teamId(fx.teamId).title(ALBUM_TITLE)
                .allowDownload(true).allowMemberUpload(false).build()).getId();
        photoId = photoRepository.save(PhotoEntity.builder().albumId(albumId)
                .r2Key("gallery/TEAM/" + fx.teamId + "/album-" + albumId + "/photo-c59.jpg")
                .originalFilename("c59.jpg").contentType("image/jpeg").fileSize(1024L)
                .mediaType(GalleryMediaType.PHOTO).processingStatus(GalleryProcessingStatus.READY).build()).getId();
    }

    @Test
    @DisplayName("AC-0159-18: アルバム一覧は上限を越える閲覧者に 1 件も返さず、権限あり（MEMBER 以上）には返す")
    void アルバム一覧は上限を越える閲覧者に1件も返さない() throws Exception {
        for (Actor actor : java.util.List.of(Actor.ADMIN, Actor.DEPUTY_ADMIN, Actor.MEMBER)) {
            assertThat(albumListBody(actor)).as("権限あり %s にはアルバムが見える（本戦役前どおり）", actor).contains(ALBUM_TITLE);
        }
        for (Actor actor : java.util.List.of(Actor.SUPPORTER, Actor.GUEST, Actor.OUTSIDER, Actor.OTHER_TEAM_MEMBER,
                Actor.OTHER_ORG_MEMBER)) {
            assertThat(albumListBody(actor)).as("権限なし %s にはアルバムを 1 件も返さない", actor).doesNotContain(ALBUM_TITLE);
        }
    }

    /** 一覧本文（レスポンス形状に依存しないよう、本文にアルバムのタイトルが含まれるかで判定する）。 */
    private String albumListBody(Actor actor) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/gallery/albums").param("teamId", String.valueOf(fixture.teamId))
                .with(user(String.valueOf(fixture.user(actor))))).andReturn();
        assertThat(result.getResponse().getStatus()).as("%s のアルバム一覧は 200（一覧は絞り込み）", actor).isEqualTo(200);
        return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 作成したテストデータだけを消す（フィクスチャ本体の後始末は基底クラス）。 */
    @AfterEach
    void deleteSeededRows() {
        if (fixture == null) {
            return;
        }
        new TransactionTemplate(tm).executeWithoutResult(t -> {
            em.createNativeQuery("DELETE FROM photos WHERE album_id IN (SELECT id FROM photo_albums WHERE team_id = :id)")
                    .setParameter("id", fixture.teamId).executeUpdate();
            em.createNativeQuery("DELETE FROM photo_albums WHERE team_id = :id")
                    .setParameter("id", fixture.teamId).executeUpdate();
        });
    }
}
