package com.mannschaft.app.supportaccess.dto;

import java.util.List;

/**
 * F10.9 遷移トランザクションの結果。コミット後に監査を書くため、遷移後の grant と、
 * 同じトランザクションで失効させた grant（依頼時に見つけた期限切れ）を運ぶ。
 */
public record SupportAccessTransitionResult(SupportAccessGrantSnapshot grant,
                                            List<SupportAccessGrantSnapshot> expiredGrants) {
}
