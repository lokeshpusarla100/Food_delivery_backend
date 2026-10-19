package com.fooddelivery.user.service;

import com.fooddelivery.user.dto.AddressDtos.*;
import com.fooddelivery.user.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class AddressService {
    private final JdbcTemplate jdbc;
    private final UserService users;

    private static final String SELECT = "SELECT address_id,label,street_line1,street_line2,locality,city,state,zip_code,country,"
        +"ST_Y(location_point) latitude,ST_X(location_point) longitude,is_default "
        +"FROM saved_addresses WHERE user_id=? ";
    private static final RowMapper<AddressResponse> MAPPER=(rs,num)->new AddressResponse(
        rs.getLong("address_id"),rs.getString("label"),rs.getString("street_line1"),
        rs.getString("street_line2"),rs.getString("locality"),rs.getString("city"),
        rs.getString("state"),rs.getString("zip_code"),rs.getString("country"),
        rs.getDouble("latitude"),rs.getDouble("longitude"),rs.getBoolean("is_default"));

    @Transactional(readOnly=true)
    public List<AddressResponse> list(String userId, int limit, int offset) {
        users.requireActiveUser(userId);
        return jdbc.query(SELECT + "ORDER BY is_default DESC,address_id DESC LIMIT ? OFFSET ?",
            MAPPER,userId,limit,offset);
    }
    @Transactional
    public AddressResponse add(String userId,AddressRequest req) {
        lockUser(userId);
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM saved_addresses WHERE user_id=?",Long.class,userId);
        boolean makeDefault=req.makeDefault() || count==0;
        if(makeDefault) clearDefault(userId);
        KeyHolder keys=new GeneratedKeyHolder();
        jdbc.update(connection->{
            PreparedStatement ps=connection.prepareStatement(
                "INSERT INTO saved_addresses (user_id,label,street_line1,street_line2,locality,city,state,zip_code,country,location_point,is_default) "
                +"VALUES (?,?,?,?,?,?,?,?,?,ST_SRID(POINT(?,?),4326),?)",Statement.RETURN_GENERATED_KEYS);
            ps.setString(1,userId);ps.setString(2,req.label().trim());
            ps.setString(3,req.streetLine1());ps.setString(4,req.streetLine2());
            ps.setString(5,req.locality());ps.setString(6,req.city());
            ps.setString(7,req.state());ps.setString(8,req.zipCode());
            ps.setString(9,req.country());ps.setDouble(10,req.longitude());
            ps.setDouble(11,req.latitude());ps.setBoolean(12,makeDefault);
            return ps;
        },keys);
        if(keys.getKey()==null) throw new IllegalStateException("Address ID not returned");
        return get(userId,keys.getKey().longValue());
    }
    @Transactional
    public AddressResponse update(String userId,long addressId,AddressRequest req) {
        lockUser(userId);
        ensureExists(userId,addressId);
        if(req.makeDefault()) clearDefault(userId);
        int n=jdbc.update("UPDATE saved_addresses SET label=?,street_line1=?,street_line2=?,locality=?,city=?,state=?,"
            +"zip_code=?,country=?,location_point=ST_SRID(POINT(?,?),4326),"
            +"is_default=IF(?,TRUE,is_default) WHERE user_id=? AND address_id=?",
            req.label().trim(),req.streetLine1(),req.streetLine2(),req.locality(),req.city(),
            req.state(),req.zipCode(),req.country(),req.longitude(),req.latitude(),
            req.makeDefault(),userId,addressId);
        if(n!=1) throw notFound();
        return get(userId,addressId);
    }
    @Transactional
    public void setDefault(String userId,long id) {
        lockUser(userId);ensureExists(userId,id);
        clearDefault(userId);
        jdbc.update("UPDATE saved_addresses SET is_default=TRUE WHERE user_id=? AND address_id=?",userId,id);
    }
    @Transactional
    public void delete(String userId,long id) {
        lockUser(userId);
        List<AddressResponse> before=jdbc.query(SELECT+"AND address_id=?",MAPPER,userId,id);
        if(before.isEmpty()) throw notFound();
        jdbc.update("DELETE FROM saved_addresses WHERE user_id=? AND address_id=?",userId,id);
        if(before.get(0).isDefault()) {
            jdbc.update("UPDATE saved_addresses SET is_default=TRUE WHERE user_id=? ORDER BY address_id DESC LIMIT 1",userId);
        }
    }
    @Transactional(readOnly=true)
    public AddressResponse get(String userId,long id) {
        users.requireActiveUser(userId);
        return jdbc.query(SELECT+"AND address_id=?",MAPPER,userId,id).stream()
            .findFirst().orElseThrow(this::notFound);
    }
    private void ensureExists(String userId,long id) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM saved_addresses WHERE user_id=? AND address_id=?",
            Long.class,userId,id)==0) throw notFound();
    }
    private void clearDefault(String userId) {
        jdbc.update("UPDATE saved_addresses SET is_default=FALSE WHERE user_id=? AND is_default=TRUE",userId);
    }
    // A row lock serializes competing default-address writes across app instances.
    private void lockUser(String id) {
        if(jdbc.query("SELECT user_id FROM users WHERE user_id=? AND is_active=TRUE FOR UPDATE",
            (rs,n)->rs.getString(1),id).isEmpty())
            throw new ApiException(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED","Account not found or disabled");
    }
    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND,"ADDRESS_NOT_FOUND","Address not found");
    }
}
