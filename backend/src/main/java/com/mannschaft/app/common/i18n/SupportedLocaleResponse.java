package com.mannschaft.app.common.i18n;

/**
 * 対応言語マスタの1件分（コードと、その言語自身での表示名）。
 */
public record SupportedLocaleResponse(String code, String nativeName) {
}
