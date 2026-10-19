package com.fooddelivery.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties={
    "security.jwt.secret=ci-only-random-example-signing-key-at-least-32-characters",
    "eureka.client.enabled=false",
    "spring.cloud.discovery.enabled=false",
    "spring.profiles.active=integration"
})
@AutoConfigureMockMvc
class UserServiceIntegrationTest {
    @Container
    static final MySQLContainer<?> DB = new MySQLContainer<>("mysql:8.0.36")
        .withDatabaseName("user_db").withUsername("test").withPassword("test");

    @DynamicPropertySource static void mysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",DB::getJdbcUrl);
        registry.add("spring.datasource.username",DB::getUsername);
        registry.add("spring.datasource.password",DB::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test void lifecycleAndSpatialAddressRoundTrip() throws Exception {
        String email="integration+"+UUID.randomUUID()+"@example.com";
        String pw="Strong-Passphrase-For-Test-123";
        String registration=json.writeValueAsString(java.util.Map.of(
            "email",email,"phoneNumber","+919"+String.valueOf((int)(Math.random()*900000000+100000000)),
            "fullName","Integration User","password",pw));
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(registration)).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
        String logged=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(java.util.Map.of("email",email,"password",pw,"deviceId","test-device"))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode login=json.readTree(logged).get("data");
        String access=login.get("accessToken").asText();
        String oldRefresh=login.get("refreshToken").asText();
        mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+access))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value(email));
        String address=json.writeValueAsString(java.util.Map.ofEntries(
            java.util.Map.entry("label","Home"),java.util.Map.entry("streetLine1","Street 2"),
            java.util.Map.entry("locality","Gachibowli"),java.util.Map.entry("city","Hyderabad"),
            java.util.Map.entry("state","Telangana"),java.util.Map.entry("zipCode","500032"),
            java.util.Map.entry("country","India"),java.util.Map.entry("latitude",17.4435),
            java.util.Map.entry("longitude",78.3772),java.util.Map.entry("makeDefault",true)));
        mvc.perform(post("/api/v1/users/me/addresses").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(address))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.latitude").value(17.4435))
            .andExpect(jsonPath("$.data.longitude").value(78.3772));
        var coordinates=jdbc.queryForMap("SELECT latitude,longitude FROM saved_addresses LIMIT 1");
        assertEquals(17.4435,((Number)coordinates.get("latitude")).doubleValue(),0.00001);
        assertEquals(78.3772,((Number)coordinates.get("longitude")).doubleValue(),0.00001);
        mvc.perform(get("/api/v1/users/me/addresses").header("Authorization","Bearer "+access))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].isDefault").value(true));
        String refresh=json.writeValueAsString(java.util.Map.of("refreshToken",oldRefresh));
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
            .content(refresh)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
            .content(refresh)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/logout-all").header("Authorization","Bearer "+access))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+access))
            .andExpect(status().isUnauthorized());
    }
}
