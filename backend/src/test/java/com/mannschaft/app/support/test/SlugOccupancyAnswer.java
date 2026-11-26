package com.mannschaft.app.support.test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * slug 占有状態（現役・論理削除済み・slug 履歴）を表す Mockito の既定 Answer（CMP-261021-0838 試練用）。
 *
 * <p>実装役がどの照会メソッド名で「論理削除済みを含む存在確認」を書くかを試練側で固定しないため、
 * メソッド名と引数から占有判定を返す。判定規則:</p>
 * <ul>
 *   <li>名前に {@code OldSlug} を含む（slug 履歴照会）→ {@link #retired} に含まれれば占有。</li>
 *   <li>名前に {@code Slug} を含み {@code DeletedAtIsNull} を含む → {@link #active} のみで判定（現役限定）。</li>
 *   <li>名前に {@code Slug} を含むその他 → {@link #active} ∪ {@link #deleted} で判定（論理削除済みを含む）。</li>
 *   <li>{@code save}/{@code saveAndFlush} は引数をそのまま返す。それ以外は Mockito 既定値。</li>
 * </ul>
 * <p>戻り値型が boolean / 件数（long・int）/ Optional のいずれでも占有を表現する。</p>
 */
public final class SlugOccupancyAnswer implements Answer<Object> {

    /** 現役（deleted_at IS NULL）の行が持つ slug。 */
    public final Set<String> active = new HashSet<>();

    /** 論理削除済みの行が持つ slug（DB の一意索引には残る）。 */
    public final Set<String> deleted = new HashSet<>();

    /** slug 履歴（team_slug_history / organization_slug_history）に予約された旧 slug。 */
    public final Set<String> retired = new HashSet<>();

    @Override
    public Object answer(InvocationOnMock invocation) throws Throwable {
        Method method = invocation.getMethod();
        String name = method.getName();
        Object[] args = invocation.getArguments();
        if ((name.equals("save") || name.equals("saveAndFlush")) && args.length == 1) {
            return args[0];
        }
        String slug = firstString(args);
        if (slug != null && name.toLowerCase().contains("slug")) {
            boolean occupied;
            if (name.contains("OldSlug")) {
                occupied = retired.contains(slug);
            } else if (name.contains("DeletedAtIsNull")) {
                occupied = active.contains(slug);
            } else {
                occupied = active.contains(slug) || deleted.contains(slug);
            }
            Object converted = convert(method, occupied);
            if (converted != null) {
                return converted;
            }
        }
        return Mockito.RETURNS_DEFAULTS.answer(invocation);
    }

    private static String firstString(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof String s) {
                return s;
            }
        }
        return null;
    }

    private static Object convert(Method method, boolean occupied) {
        Class<?> type = method.getReturnType();
        if (type == boolean.class || type == Boolean.class) {
            return occupied;
        }
        if (type == long.class || type == Long.class) {
            return occupied ? 1L : 0L;
        }
        if (type == int.class || type == Integer.class) {
            return occupied ? 1 : 0;
        }
        if (type == Optional.class) {
            if (!occupied) {
                return Optional.empty();
            }
            Type generic = method.getGenericReturnType();
            if (generic instanceof ParameterizedType p && p.getActualTypeArguments()[0] instanceof Class<?> c) {
                if (c == Long.class) {
                    return Optional.of(-1L);
                }
                return Optional.of(Mockito.mock(c));
            }
        }
        return null;
    }
}
