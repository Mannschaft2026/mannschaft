package com.mannschaft.app.supportaccess.context;

import org.springframework.core.task.TaskDecorator;

/** F10.9 代理閲覧の文脈を @Async の実行スレッドへ伝播する（受け手でも READ ONLY・拒否が効くように）。試練スタブ。 */
public class SupportAccessContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }
}
