package com.mannschaft.app.supportaccess.context;

import org.springframework.core.task.TaskDecorator;

/**
 * F10.9 代理閲覧の文脈を @Async の実行スレッドへ伝播する（受け手でも READ ONLY・拒否が効くように）。
 *
 * <p>submit 時点のスレッドの文脈を捕捉し、実行スレッドで set して、実行後は元の状態へ戻す
 * （プールのスレッドに文脈を残さない）。代理中でなければ何もしない。</p>
 */
public class SupportAccessContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        SupportAccessContext captured = SupportAccessContextHolder.get().orElse(null);
        if (captured == null) {
            return runnable;
        }
        return () -> {
            SupportAccessContext previous = SupportAccessContextHolder.get().orElse(null);
            SupportAccessContextHolder.set(captured);
            try {
                runnable.run();
            } finally {
                if (previous == null) {
                    SupportAccessContextHolder.clear();
                } else {
                    SupportAccessContextHolder.set(previous);
                }
            }
        };
    }
}
