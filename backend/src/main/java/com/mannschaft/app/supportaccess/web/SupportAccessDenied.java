package com.mannschaft.app.supportaccess.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** F10.9 代理閲覧中は GET でも拒否する Controller/メソッド（SUPPORT_ACCESS_004）。認証系・決済・エクスポートに付ける。 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SupportAccessDenied {
}
