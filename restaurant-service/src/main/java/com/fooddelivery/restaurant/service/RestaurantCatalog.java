package com.fooddelivery.restaurant.service;

import com.fooddelivery.restaurant.api.RestaurantDtos.*;
import com.fooddelivery.restaurant.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class RestaurantCatalog {
    private final JdbcTemplate jdbc;
    private final RestaurantAccess access;
    private final OutletSchedule hours;
    private final OutboxWriter outbox;

    private static final String OUTLET_COLUMNS="o.outlet_id,o.brand_id,o.location_name,o.street,o.locality,o.city,o.state,"
        +"o.postal_code,o.country,o.latitude,o.longitude,o.timezone,o.prep_minutes,o.delivery_radius_km,"
        +"o.is_active,o.is_accepting_orders,o.version";
    private static final String OUTLET_BASE="SELECT "+OUTLET_COLUMNS+" FROM outlets o ";
    private final RowMapper<OutletView> outletMapper=(rs,n)->{
        String id=rs.getString("outlet_id"),zone=rs.getString("timezone");
        boolean active=rs.getBoolean("is_active");
        boolean accepting=rs.getBoolean("is_accepting_orders");
        return new OutletView(id,rs.getString("brand_id"),rs.getString("location_name"),
            rs.getString("street"),rs.getString("locality"),rs.getString("city"),
            rs.getString("state"),rs.getString("postal_code"),rs.getString("country"),
            rs.getBigDecimal("latitude"),rs.getBigDecimal("longitude"),zone,
            rs.getInt("prep_minutes"),rs.getBigDecimal("delivery_radius_km"),
            active,accepting,active && hours.isOpen(id,zone,accepting),rs.getLong("version"));
    };

    private static DomainException notFound(String type) {
        return new DomainException(HttpStatus.NOT_FOUND,type.toUpperCase()+"_NOT_FOUND",type+" not found");
    }
    private static void uuid(String id) {
        try { UUID.fromString(id); }
        catch(RuntimeException e) {throw new DomainException(HttpStatus.BAD_REQUEST,"INVALID_ID","A UUID is required");}
    }
    private static String clean(String value) {return value==null?null:value.trim();}
    private static void page(int limit,int offset) {
        if(limit<1 || limit>50 || offset<0 || offset>10000)
            throw new DomainException(HttpStatus.BAD_REQUEST,"BAD_PAGE","Limit 1-50 and offset 0-10000 required");
    }

    @Transactional
    public BrandView createBrand(CreateBrand input) {
        uuid(input.ownerUserId());
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO brands(brand_id,name,logo_url,corporate_phone,cuisine_type) VALUES(?,?,?,?,?)",
            id,clean(input.name()),clean(input.logoUrl()),clean(input.corporatePhone()),clean(input.cuisineType()));
        jdbc.update("INSERT INTO restaurant_staff(brand_id,user_id,role) VALUES(?,?,'OWNER')",id,input.ownerUserId());
        outbox.append(id,"BRAND_CREATED",Map.of("brandId",id));
        return brand(id);
    }
    @Transactional(readOnly=true)
    public BrandView brand(String id) {
        uuid(id);
        return jdbc.query("SELECT * FROM brands WHERE brand_id=? AND is_active=TRUE",
            (rs,n)->new BrandView(rs.getString("brand_id"),rs.getString("name"),
                rs.getString("logo_url"),rs.getString("corporate_phone"),
                rs.getString("cuisine_type"),rs.getBoolean("is_active")),id)
            .stream().findFirst().orElseThrow(()->notFound("brand"));
    }
    @Transactional(readOnly=true)
    public PageView<BrandView> brands(int limit,int offset) {
        page(limit,offset);
        List<BrandView> data=jdbc.query("SELECT * FROM brands WHERE is_active=TRUE ORDER BY name,brand_id LIMIT ? OFFSET ?",
            (rs,n)->new BrandView(rs.getString("brand_id"),rs.getString("name"),
                rs.getString("logo_url"),rs.getString("corporate_phone"),
                rs.getString("cuisine_type"),rs.getBoolean("is_active")),limit,offset);
        return new PageView<>(data,limit,offset);
    }
    @Transactional
    public void deactivateBrand(String brandId) {
        uuid(brandId);
        if(jdbc.update("UPDATE brands SET is_active=FALSE WHERE brand_id=? AND is_active=TRUE",brandId)==0)
            throw notFound("brand");
        jdbc.update("UPDATE outlets SET is_accepting_orders=FALSE,version=version+1 WHERE brand_id=?",brandId);
        outbox.append(brandId,"BRAND_DISABLED",Map.of("brandId",brandId));
    }
    @Transactional
    public void addStaff(String brandId,String userId,String role,Authentication principal) {
        access.requireStaff(brandId,principal,true);
        uuid(userId);
        if(!List.of("MANAGER","EDITOR").contains(role))
            throw new DomainException(HttpStatus.BAD_REQUEST,"INVALID_ROLE","Only MANAGER or EDITOR can be assigned");
        jdbc.update("INSERT INTO restaurant_staff(brand_id,user_id,role) VALUES(?,?,?) "
            +"ON DUPLICATE KEY UPDATE role=VALUES(role)",brandId,userId,role);
        outbox.append(brandId,"RESTAURANT_STAFF_CHANGED",Map.of("userId",userId,"role",role));
    }
    @Transactional
    public void removeStaff(String brandId,String userId,Authentication principal) {
        access.requireStaff(brandId,principal,true);
        jdbc.update("DELETE FROM restaurant_staff WHERE brand_id=? AND user_id=? AND role<>'OWNER'",brandId,userId);
        outbox.append(brandId,"RESTAURANT_STAFF_REMOVED",Map.of("userId",userId));
    }

    @Transactional
    public OutletView createOutlet(CreateOutlet input,Authentication principal) {
        access.requireStaff(input.brandId(),principal,false);
        ZoneId.of(input.timezone());
        String id=UUID.randomUUID().toString();
        String wkt="POINT("+input.longitude()+" "+input.latitude()+")";
        jdbc.update("INSERT INTO outlets(outlet_id,brand_id,location_name,street,locality,city,state,postal_code,country,"
          +"location_point,timezone,prep_minutes,delivery_radius_km,is_active,is_accepting_orders) "
          +"VALUES(?,?,?,?,?,?,?,?,?,ST_GeomFromText(?,4326,'axis-order=long-lat'),?,?,?,TRUE,FALSE)",
          id,input.brandId(),clean(input.name()),clean(input.street()),clean(input.locality()),
          clean(input.city()),clean(input.state()),clean(input.postalCode()),clean(input.country()),
          wkt,input.timezone(),input.prepMinutes(),input.deliveryRadiusKm());
        outbox.append(id,"OUTLET_CREATED",Map.of("brandId",input.brandId(),"outletId",id));
        return outlet(id);
    }
    @Transactional(readOnly=true)
    public OutletView outlet(String id) {
        uuid(id);
        return jdbc.query(OUTLET_BASE+"JOIN brands b ON b.brand_id=o.brand_id "
           +"WHERE o.outlet_id=? AND o.is_active=TRUE AND b.is_active=TRUE",
           outletMapper,id).stream().findFirst().orElseThrow(()->notFound("outlet"));
    }
    @Transactional(readOnly=true)
    public boolean outletExists(String id) {
        uuid(id);
        Integer c=jdbc.queryForObject("SELECT COUNT(*) FROM outlets o JOIN brands b ON b.brand_id=o.brand_id "
            +"WHERE o.outlet_id=? AND o.is_active=TRUE AND b.is_active=TRUE",Integer.class,id);
        return c!=null&&c>0;
    }
    @Transactional(readOnly=true)
    public PageView<OutletView> outletsForBrand(String brandId,int limit,int offset) {
        uuid(brandId);page(limit,offset);
        var list=jdbc.query(OUTLET_BASE+"JOIN brands b ON b.brand_id=o.brand_id WHERE o.brand_id=? "
            +"AND o.is_active=TRUE AND b.is_active=TRUE ORDER BY o.outlet_id LIMIT ? OFFSET ?",
            outletMapper,brandId,limit,offset);
        return new PageView<>(list,limit,offset);
    }
    @Transactional(readOnly=true)
    public PageView<OutletView> byCity(String city,int limit,int offset) {
        page(limit,offset);
        if(city==null||city.isBlank()) throw new DomainException(HttpStatus.BAD_REQUEST,"CITY_REQUIRED","City required");
        var list=jdbc.query(OUTLET_BASE+"JOIN brands b ON b.brand_id=o.brand_id "
            +"WHERE o.city=? AND o.is_active=TRUE AND b.is_active=TRUE "
            +"ORDER BY o.outlet_id LIMIT ? OFFSET ?",outletMapper,city.trim(),limit,offset);
        return new PageView<>(list,limit,offset);
    }
    @Transactional(readOnly=true)
    public PageView<OutletView> near(BigDecimal latitude,BigDecimal longitude,BigDecimal radiusKm,int limit) {
        page(limit,0);
        if(latitude==null||longitude==null||radiusKm==null
            ||latitude.abs().compareTo(BigDecimal.valueOf(90))>0
            ||longitude.abs().compareTo(BigDecimal.valueOf(180))>0
            ||radiusKm.compareTo(BigDecimal.valueOf(0.1))<0
            ||radiusKm.compareTo(BigDecimal.valueOf(25))>0)
            throw new DomainException(HttpStatus.BAD_REQUEST,"INVALID_GEO","Invalid coordinate or radius (0.1-25 km)");
        double lat=latitude.doubleValue(),lon=longitude.doubleValue(),range=radiusKm.doubleValue();
        double latDelta=range/111.0;
        double lonDelta=Math.min(180,range/(111.0*Math.max(0.01,Math.cos(Math.toRadians(lat)))));
        String wkt="POINT("+lon+" "+lat+")";
        var result=jdbc.query(OUTLET_BASE+"JOIN brands b ON b.brand_id=o.brand_id "
            +"WHERE o.is_active=TRUE AND b.is_active=TRUE "
            +"AND o.latitude BETWEEN ? AND ? AND o.longitude BETWEEN ? AND ? "
            +"AND ST_Distance_Sphere(o.location_point,ST_GeomFromText(?,4326,'axis-order=long-lat'))<=? "
            +"ORDER BY ST_Distance_Sphere(o.location_point,ST_GeomFromText(?,4326,'axis-order=long-lat')),o.outlet_id LIMIT ?",
            outletMapper,lat-latDelta,lat+latDelta,lon-lonDelta,lon+lonDelta,wkt,range*1000,wkt,limit);
        return new PageView<>(result,limit,0);
    }
    @Transactional
    public OutletView acceptingOrders(String id,boolean accepting,Authentication principal) {
        access.requireOutlet(id,principal);
        int updated=jdbc.update("UPDATE outlets SET is_accepting_orders=?,version=version+1 WHERE outlet_id=? AND is_active=TRUE",
            accepting,id);
        if(updated==0)throw notFound("outlet");
        outbox.append(id,"OUTLET_AVAILABILITY_CHANGED",Map.of("accepting",accepting));
        return outlet(id);
    }
    @Transactional
    public void disableOutlet(String id,Authentication principal) {
        access.requireOutlet(id,principal);
        jdbc.update("UPDATE outlets SET is_active=FALSE,is_accepting_orders=FALSE,version=version+1 WHERE outlet_id=?",id);
        outbox.append(id,"OUTLET_DISABLED",Map.of("outletId",id));
    }
    @Transactional
    public void setHours(String id,List<HoursInput> schedule,Authentication principal) {
        access.requireOutlet(id,principal);
        if(schedule==null || schedule.size()>7 || schedule.stream().map(HoursInput::dayOfWeek).distinct().count()!=schedule.size())
            throw new DomainException(HttpStatus.BAD_REQUEST,"INVALID_HOURS","Each weekday can appear only once");
        for(HoursInput h:schedule) {
            if(h.dayOfWeek()==null || h.dayOfWeek()<1 || h.dayOfWeek()>7 || h.opensAt()==null || h.closesAt()==null)
                throw new DomainException(HttpStatus.BAD_REQUEST,"INVALID_HOURS","Invalid schedule");
            jdbc.update("INSERT INTO outlet_weekly_hours(outlet_id,day_of_week,opens_at,closes_at,is_closed) "
                +"VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE opens_at=VALUES(opens_at),closes_at=VALUES(closes_at),"
                +"is_closed=VALUES(is_closed)",id,h.dayOfWeek(),h.opensAt(),h.closesAt(),h.closed());
        }
        outbox.append(id,"OUTLET_HOURS_CHANGED",Map.of("outletId",id));
    }
    @Transactional
    public void blackout(String id,Instant from,Instant until,String reason,Authentication principal) {
        access.requireOutlet(id,principal);
        if(from==null||until==null||!until.isAfter(from)||until.isAfter(from.plus(Duration.ofDays(90))))
            throw new DomainException(HttpStatus.BAD_REQUEST,"INVALID_BLACKOUT","Invalid blackout period");
        jdbc.update("INSERT INTO outlet_blackouts(outlet_id,starts_at_utc,ends_at_utc,reason) VALUES(?,?,?,?)",
           id,LocalDateTime.ofInstant(from,ZoneOffset.UTC),LocalDateTime.ofInstant(until,ZoneOffset.UTC),reason);
        outbox.append(id,"OUTLET_BLACKOUT_ADDED",Map.of("outletId",id));
    }
}
