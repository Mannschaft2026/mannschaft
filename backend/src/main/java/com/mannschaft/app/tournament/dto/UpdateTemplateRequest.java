package com.mannschaft.app.tournament.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * テンプレート更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateTemplateRequest {

    @Size(max = 100)
    private final String name;

    @Size(max = 500)
    private final String description;

    @Size(max = 30)
    private final String icon;

    private final String supportedFormats;
    @Min(0) @Max(255)
    private final Integer winPoints;
    @Min(0) @Max(255)
    private final Integer drawPoints;
    @Min(0) @Max(255)
    private final Integer lossPoints;
    private final Boolean hasDraw;
    private final Boolean hasSets;
    private final Integer setsToWin;
    private final Boolean hasExtraTime;
    private final Boolean hasPenalties;

    @Size(max = 20)
    private final String scoreUnitLabel;

    private final String bonusPointRules;

    @NotNull
    private final Long version;

    private final List<TiebreakerRequest> tiebreakers;
    private final List<StatDefRequest> statDefs;
}
