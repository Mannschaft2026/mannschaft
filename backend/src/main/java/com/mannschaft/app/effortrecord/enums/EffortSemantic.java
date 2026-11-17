package com.mannschaft.app.effortrecord.enums;

/** F06.4.1 項目の意味（API 契約 §2 FieldDefinition.semantic）。集計可否はこの値で決まる。 */
public enum EffortSemantic {
    DURATION,
    WEIGHT,
    INTENSITY,
    SUBJECT,
    MENU,
    NUMBER,
    TEXT,
    DATE,
    DATETIME,
    CHECKBOX
}
