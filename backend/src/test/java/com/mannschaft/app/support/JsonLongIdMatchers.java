package com.mannschaft.app.support;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;

/**
 * JSON 応答中の Long ID を型に依存せず比較する Matcher。
 *
 * <p>JsonPath は数値を値の大きさで Integer / Long に振り分けるため、{@code hasItem(id.intValue())} のように
 * int へ切り詰めて比べると、users.id などの AUTO_INCREMENT が int の範囲を超えた時点で不一致になる。
 * ここでは Number の longValue で比べる。</p>
 */
public final class JsonLongIdMatchers {

    private JsonLongIdMatchers() {
    }

    /** 配列要素のいずれかが指定 ID と等しい。 */
    public static Matcher<Object> hasLongId(Long expected) {
        return new BaseMatcher<>() {
            @Override
            public boolean matches(Object actual) {
                if (!(actual instanceof Iterable<?> items)) {
                    return false;
                }
                for (Object item : items) {
                    if (item instanceof Number n && n.longValue() == expected) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("an array containing id ").appendValue(expected);
            }
        };
    }

    /** 単一値が指定 ID と等しい。 */
    public static Matcher<Object> longId(Long expected) {
        return new BaseMatcher<>() {
            @Override
            public boolean matches(Object actual) {
                return actual instanceof Number n && n.longValue() == expected;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("id ").appendValue(expected);
            }
        };
    }
}
