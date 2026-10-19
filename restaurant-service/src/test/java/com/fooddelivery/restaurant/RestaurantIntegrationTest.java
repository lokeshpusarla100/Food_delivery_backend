package com.fooddelivery.restaurant;

import com.fasterxml.jackson.databind.*;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties={
  "security.jwt.secret=restaurant-ci-example-token-secret-with-at-least-32-characters",
  "security.restaurant.platform-key=restaurant-ci-example-platform-key-with-32-characters",
  "security.restaurant.internal-key=restaurant-ci-example-internal-key-with-32-characters",
  "eureka.client.enabled=false","spring.cloud.discovery.enabled=false",
  "spring.profiles.active=integration"
})
@AutoConfigureMockMvc
class RestaurantIntegrationTest {
  @Container static final MySQLContainer<?> DB=new MySQLContainer<>("mysql:8.0.36")
      .withDatabaseName("restaurant_db").withUsername("test").withPassword("test");
  @DynamicPropertySource static void database(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",DB::getJdbcUrl);
    r.add("spring.datasource.username",DB::getUsername);
    r.add("spring.datasource.password",DB::getPassword);
  }
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  private final String platformKey="restaurant-ci-example-platform-key-with-32-characters";
  private final String internalKey="restaurant-ci-example-internal-key-with-32-characters";
  private String token(String userId){
    String secret="restaurant-ci-example-token-secret-with-at-least-32-characters";
    JwtEncoder encoder=new NimbusJwtEncoder(new ImmutableSecret<>(
       new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256")));
    Instant now=Instant.now();
    return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
        JwtClaimsSet.builder().issuer("food-delivery-user-service").audience(List.of("food-delivery-api"))
            .subject(userId).issuedAt(now).expiresAt(now.plusSeconds(900)).build())).getTokenValue();
  }
  private String json(Object input)throws Exception {return mapper.writeValueAsString(input);}
  private JsonNode body(org.springframework.test.web.servlet.ResultActions result)throws Exception{
    return mapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
  }
  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder merchant(
       String path,String bearer,String content) {
    return post(path).contentType(MediaType.APPLICATION_JSON).header("Authorization","Bearer "+bearer).content(content);
  }

  @Test void brandOutletMenuPricingSecurityAndQuote()throws Exception{
    String owner=UUID.randomUUID().toString();
    String merchantToken=token(owner);
    String brandName="CI Brand "+UUID.randomUUID();
    var brandInput=Map.of("name",brandName,"ownerUserId",owner,
        "corporatePhone","+919999999999","cuisineType","Indian");
    mvc.perform(post("/api/v1/platform/brands").contentType(MediaType.APPLICATION_JSON)
        .content(json(brandInput))).andExpect(status().isForbidden());
    JsonNode b=body(mvc.perform(post("/api/v1/platform/brands")
        .header("X-Restaurant-Platform-Key",platformKey)
        .contentType(MediaType.APPLICATION_JSON).content(json(brandInput)))
        .andExpect(status().isCreated()));
    String brandId=b.path("brandId").asText();
    var outlet=Map.<String,Object>ofEntries(
        Map.entry("brandId",brandId),Map.entry("name","Hyderabad Test Outlet"),
        Map.entry("street","Road 1"),Map.entry("locality","Gachibowli"),
        Map.entry("city","Hyderabad"),Map.entry("state","Telangana"),
        Map.entry("postalCode","500032"),Map.entry("country","India"),
        Map.entry("latitude",17.4435),Map.entry("longitude",78.3772),
        Map.entry("timezone","Asia/Kolkata"),Map.entry("prepMinutes",30),
        Map.entry("deliveryRadiusKm",8.5));
    mvc.perform(post("/api/v1/manage/brands/"+brandId+"/outlets")
        .contentType(MediaType.APPLICATION_JSON).content(json(outlet)))
        .andExpect(status().isUnauthorized());
    String other=token(UUID.randomUUID().toString());
    mvc.perform(merchant("/api/v1/manage/brands/"+brandId+"/outlets",other,json(outlet)))
        .andExpect(status().isForbidden());
    JsonNode o=body(mvc.perform(merchant("/api/v1/manage/brands/"+brandId+"/outlets",
        merchantToken,json(outlet))).andExpect(status().isCreated()));
    String outletId=o.path("outletId").asText();
    assertFalse(o.path("isOpen").asBoolean());
    Map<String,Object> latLon=jdbc.queryForMap(
        "SELECT latitude,longitude FROM outlets WHERE outlet_id=?",outletId);
    assertEquals(17.4435,((Number)latLon.get("latitude")).doubleValue(),0.00001);
    assertEquals(78.3772,((Number)latLon.get("longitude")).doubleValue(),0.00001);
    mvc.perform(get("/api/v1/outlets/"+outletId)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/outlets/near").param("lat","17.4435").param("lon","78.3772"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].outletId").value(outletId));
    List<Map<String,Object>> days=new ArrayList<>();
    for(int day=1;day<=7;day++) days.add(Map.of(
         "dayOfWeek",day,"opensAt","00:00:00","closesAt","23:59:59","closed",false));
    mvc.perform(put("/api/v1/manage/outlets/"+outletId+"/hours")
        .header("Authorization","Bearer "+merchantToken)
        .contentType(MediaType.APPLICATION_JSON).content(json(days))).andExpect(status().isOk());
    mvc.perform(put("/api/v1/manage/outlets/"+outletId+"/accepting-orders")
        .header("Authorization","Bearer "+merchantToken)
        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("acceptingOrders",true))))
        .andExpect(status().isOk());
    JsonNode c=body(mvc.perform(merchant("/api/v1/manage/brands/"+brandId+"/categories",
       merchantToken,json(Map.of("name","Mains","displayOrder",1))))
       .andExpect(status().isCreated()));
    String categoryId=c.path("categoryId").asText();
    JsonNode i=body(mvc.perform(merchant("/api/v1/manage/items",merchantToken,
        json(Map.of("categoryId",categoryId,"name","Paneer Curry",
            "basePrice",200.00,"vegetarian",true))))
        .andExpect(status().isCreated()));
    String itemId=i.path("itemId").asText();
    mvc.perform(put("/api/v1/manage/items/"+itemId+"/price")
        .header("Authorization","Bearer "+merchantToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("price",220.00,"expectedVersion",0,"reason","Ingredient costs"))))
        .andExpect(status().isOk());
    mvc.perform(put("/api/v1/manage/items/"+itemId+"/price")
        .header("Authorization","Bearer "+merchantToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("price",230.00,"expectedVersion",0,"reason","Stale client"))))
        .andExpect(status().isConflict());
    mvc.perform(put("/api/v1/manage/outlets/"+outletId+"/items/"+itemId)
        .header("Authorization","Bearer "+merchantToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("status","AVAILABLE","overridePrice",210.00))))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/menus/"+outletId)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].effectivePrice").value(210.0));
    String quote=json(Map.of("items",List.of(Map.of("itemId",itemId,"quantity",2,
        "modifierIds",List.of()))));
    mvc.perform(post("/api/v1/internal/outlets/"+outletId+"/quote")
        .contentType(MediaType.APPLICATION_JSON).content(quote))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/internal/outlets/"+outletId+"/quote")
        .header("X-Restaurant-Internal-Key",internalKey)
        .contentType(MediaType.APPLICATION_JSON).content(quote))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.itemsTotal").value(420.0));
    Integer outbox=jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_outbox WHERE status='PENDING'",
        Integer.class);
    assertTrue(outbox!=null && outbox>3);
  }
}
