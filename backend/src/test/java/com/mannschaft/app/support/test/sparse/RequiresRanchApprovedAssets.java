package com.mannschaft.app.support.test.sparse;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * BE 同梱の牧場承認済み実素材（{@code backend/src/main/resources/ranch/approved}）に依存するテスト単位に付ける。
 *
 * <p>足軽 worktree の sparse checkout で当該ディレクトリが意図的に未展開のときだけ、その単位を skip する。
 * CI・全展開・手で削除した欠落・部分展開では skip せず、素材が無ければ従来どおり失敗する。
 * Spring 起動を伴うクラスでも、{@code ExecutionCondition} はコンテキスト生成より前に評価される。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ExtendWith(RanchApprovedAssetsCondition.class)
public @interface RequiresRanchApprovedAssets {
}
