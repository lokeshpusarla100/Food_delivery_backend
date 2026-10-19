package com.fooddelivery.restaurant;

import com.fooddelivery.restaurant.service.OutletSchedule;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.sql.Time;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutletScheduleTest {
    @Test void failClosedWithoutSchedule() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        Clock now=Clock.fixed(Instant.parse("2026-10-19T05:00:00Z"),ZoneOffset.UTC);
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(),any(),any())).thenReturn(0);
        when(jdbc.query(anyString(),any(org.springframework.jdbc.core.RowMapper.class),
            any(),any(),any())).thenReturn(List.of());
        assertFalse(new OutletSchedule(jdbc,now).isOpen("outlet","Asia/Kolkata",true));
    }
    @Test void failClosedOnManualPause() {
        assertFalse(new OutletSchedule(mock(JdbcTemplate.class),Clock.systemUTC())
            .isOpen("outlet","Asia/Kolkata",false));
    }
}
