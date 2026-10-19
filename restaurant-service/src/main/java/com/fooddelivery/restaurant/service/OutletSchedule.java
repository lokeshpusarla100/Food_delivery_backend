package com.fooddelivery.restaurant.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class OutletSchedule {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    @org.springframework.context.annotation.Bean
    static Clock utcClock() { return Clock.systemUTC(); }
    public boolean isOpen(String outletId,String zone,boolean isAcceptingOrders) {
        if(!isAcceptingOrders) return false;
        ZoneId tz=ZoneId.of(zone);
        ZonedDateTime now=clock.instant().atZone(tz);
        LocalDateTime utc=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        Integer blackouts=jdbc.queryForObject(
            "SELECT COUNT(*) FROM outlet_blackouts WHERE outlet_id=? AND starts_at_utc<=? AND ends_at_utc>?",
            Integer.class,outletId,utc,utc);
        if(blackouts != null && blackouts>0) return false;
        int day=now.getDayOfWeek().getValue(), yesterday=day==1?7:day-1;
        List<ScheduleRow> rows=jdbc.query(
            "SELECT day_of_week,opens_at,closes_at,is_closed FROM outlet_weekly_hours "
                +"WHERE outlet_id=? AND day_of_week IN (?,?)",
            (rs,n)->new ScheduleRow(rs.getInt(1),rs.getTime(2).toLocalTime(),
                 rs.getTime(3).toLocalTime(),rs.getBoolean(4)),outletId,day,yesterday);
        LocalTime time=now.toLocalTime();
        for(ScheduleRow r:rows){
            if(r.closed()) continue;
            boolean overnight=!r.closes().isAfter(r.opens());
            if(r.day()==day && (overnight ? !time.isBefore(r.opens())
                             : !time.isBefore(r.opens()) && time.isBefore(r.closes()))) return true;
            if(r.day()==yesterday && overnight && time.isBefore(r.closes())) return true;
        }
        return false;
    }
    private record ScheduleRow(int day,LocalTime opens,LocalTime closes,boolean closed){}
}
