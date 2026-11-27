package com.mannschaft.app.filesharing.dto;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;

/**
 * 公開ファイルリンク（未認証）のアクセス応答DTO。
 *
 * <p>未認証の閲覧者へ返すため、<b>表示に必要な項目だけ</b>を持つ。ストレージキー（fileKey）・
 * 作成者ユーザーID（createdBy）・内部ID（id / folderId）・版番号・可視ロールなどの内部情報は
 * 含めない（認証済み向けの {@link FileResponse} とは別型にしている理由）。</p>
 */
@Getter
@RequiredArgsConstructor
public class PublicFileLinkResponse {

    private final String name;
    private final Long fileSize;
    private final String contentType;
    private final String description;
    /** 更新日時。起きた瞬間なので {@link Instant}（元の LocalDateTime 列は SERVER_ZONE の壁時計として解釈して変換）。 */
    private final Instant updatedAt;
    /** ダウンロード禁止フラグ（true の場合 FE はダウンロードボタンを出さない）。 */
    private final Boolean downloadDisabled;

    /** 認証済み向けの {@link FileResponse} から公開してよい項目だけを写す。 */
    public static PublicFileLinkResponse from(FileResponse file) {
        return new PublicFileLinkResponse(file.getName(), file.getFileSize(), file.getContentType(),
                file.getDescription(),
                file.getUpdatedAt() == null ? null : file.getUpdatedAt().atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant(),
                file.getDownloadDisabled());
    }
}
