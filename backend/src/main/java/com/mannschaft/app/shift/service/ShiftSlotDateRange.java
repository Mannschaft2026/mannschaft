package com.mannschaft.app.shift.service;

import java.time.LocalDate;

/**
 * スケジュールの生存枠の日付の最小値・最大値（CMP-261115-1821）。枠が無ければ両方 null。
 *
 * @param from 最小の枠日付
 * @param to   最大の枠日付
 */
public record ShiftSlotDateRange(LocalDate from, LocalDate to) {

    /** 枠が 1 件も無いか。 */
    public boolean isEmpty() {
        return from == null;
    }
}
