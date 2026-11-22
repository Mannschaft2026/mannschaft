package com.mannschaft.app.activity.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;

import java.util.List;

/**
 * 活動テンプレート更新リクエストDTO。
 *
 * <p>{@code fields} は fieldKey で既存項目と突き合わせる。省略（null）のときは項目に触れない。
 * 指定するときは、テンプレートの既存項目の fieldKey のすべてが {@code fields} か {@code removedFieldKeys}
 * のどちらか一方に含まれなければならない（F06.4.1 設計書 §7.1）。</p>
 */
@Getter
public class UpdateTemplateRequest {

    @NotBlank
    @Size(max = 100)
    private final String name;

    @Size(max = 500)
    private final String description;

    @Size(max = 30)
    private final String icon;

    @Size(max = 7)
    private final String color;

    private final Boolean isParticipantRequired;

    private final String defaultVisibility;

    /** 項目定義。件数の上限（15）は Service で ACTIVITY_006 として検査する。ここの上限は巨大入力を弾くための防壁。 */
    @Size(max = 100)
    private final List<@NotNull @Valid CreateTemplateRequest.TemplateFieldInput> fields;

    /** 削除する既存項目の fieldKey（省略は空）。{@code fields} を省略したときの指定は 400。 */
    @Size(max = 100)
    private final List<@NotBlank @Size(max = 50) String> removedFieldKeys;

    @JsonCreator
    public UpdateTemplateRequest(String name, String description, String icon, String color,
                                 Boolean isParticipantRequired, String defaultVisibility,
                                 List<CreateTemplateRequest.TemplateFieldInput> fields,
                                 List<String> removedFieldKeys) {
        this.name = name;
        this.description = description;
        this.icon = icon;
        this.color = color;
        this.isParticipantRequired = isParticipantRequired;
        this.defaultVisibility = defaultVisibility;
        this.fields = fields;
        this.removedFieldKeys = removedFieldKeys;
    }

    /** removedFieldKeys を指定しない更新（テスト・内部呼び出し用）。 */
    public UpdateTemplateRequest(String name, String description, String icon, String color,
                                 Boolean isParticipantRequired, String defaultVisibility,
                                 List<CreateTemplateRequest.TemplateFieldInput> fields) {
        this(name, description, icon, color, isParticipantRequired, defaultVisibility, fields, null);
    }
}
