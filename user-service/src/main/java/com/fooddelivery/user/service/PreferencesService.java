package com.fooddelivery.user.service;

import com.fooddelivery.user.dto.PreferencesDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class PreferencesService {
    private final JdbcTemplate jdbc;
    private final UserService users;

    private static final String SQL="SELECT email_notifications,sms_notifications,push_notifications,language,"
        +"theme,currency,marketing_emails,data_sharing_consent FROM user_preferences WHERE user_id=?";
    private static final RowMapper<PreferencesResponse> MAPPER=(rs,n)->new PreferencesResponse(
        rs.getBoolean("email_notifications"),rs.getBoolean("sms_notifications"),
        rs.getBoolean("push_notifications"),rs.getString("language"),rs.getString("theme"),
        rs.getString("currency"),rs.getBoolean("marketing_emails"),rs.getBoolean("data_sharing_consent"));
    @Transactional(readOnly=true)
    public PreferencesResponse get(String userId) {
        users.requireActiveUser(userId);
        return jdbc.query(SQL,MAPPER,userId).stream().findFirst()
            .orElse(new PreferencesResponse(true,true,true,"en","light","INR",false,false));
    }
    @Transactional
    public PreferencesResponse put(String userId, PreferencesRequest req) {
        users.requireActiveUser(userId);
        jdbc.update("INSERT INTO user_preferences (user_id,email_notifications,sms_notifications,push_notifications,"
            +"language,theme,currency,marketing_emails,data_sharing_consent) VALUES (?,?,?,?,?,?,?,?,?) "
            +"ON DUPLICATE KEY UPDATE email_notifications=VALUES(email_notifications),"
            +"sms_notifications=VALUES(sms_notifications),push_notifications=VALUES(push_notifications),"
            +"language=VALUES(language),theme=VALUES(theme),currency=VALUES(currency),"
            +"marketing_emails=VALUES(marketing_emails),data_sharing_consent=VALUES(data_sharing_consent)",
            userId,req.emailNotifications(),req.smsNotifications(),req.pushNotifications(),
            req.language(),req.theme(),req.currency(),req.marketingEmails(),req.dataSharingConsent());
        return get(userId);
    }
}
