package com.mannschaft.app.supporter.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * サポーター申請一括承認リクエスト。
 */
@Getter
@NoArgsConstructor
public class BulkApproveRequest {

    /** 承認する申請IDのリスト */
    @NotEmpty(message = "申請IDを1件以上指定してください")
    @Size(min = 1, max = 100, message = "一括承認は1件以上100件までです")
    private List<Long> applicationIds;
}
