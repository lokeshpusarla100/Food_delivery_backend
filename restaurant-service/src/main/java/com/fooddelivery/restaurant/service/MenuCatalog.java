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
import java.util.*;

@Service @RequiredArgsConstructor
public class MenuCatalog {
    private final JdbcTemplate jdbc;
    private final RestaurantAccess access;
    private final RestaurantCatalog restaurants;
    private final OutboxWriter outbox;

    private static DomainException bad(String code,String message) {
        return new DomainException(HttpStatus.BAD_REQUEST,code,message);
    }
    private static DomainException missing(String name) {
        return new DomainException(HttpStatus.NOT_FOUND,"NOT_FOUND",name+" not found");
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private static void pagination(int limit,int offset) {
        if(limit<1 || limit>100 || offset<0 || offset>10000) throw bad("BAD_PAGE","Invalid pagination");
    }
    private static String trim(String s) { return s==null?null:s.trim(); }
    private static final RowMapper<CategoryView> CATEGORY=(r,i)->new CategoryView(
        r.getString("category_id"),r.getString("brand_id"),r.getString("name"),
        r.getInt("display_order"),r.getBoolean("is_active"));
    private static final RowMapper<ItemView> ITEM=(r,i)->new ItemView(
        r.getString("item_id"),r.getString("category_id"),r.getString("name"),
        r.getString("description"),r.getBigDecimal("base_price"),r.getString("image_url"),
        r.getBoolean("is_vegetarian"),r.getString("allergens"),r.getBoolean("is_active"),
        r.getLong("version"));

    private String categoryBrand(String categoryId) {
        return jdbc.query("SELECT brand_id FROM menu_categories WHERE category_id=?",
           (r,n)->r.getString(1),categoryId).stream().findFirst()
           .orElseThrow(()->missing("Category"));
    }
    @Transactional
    public CategoryView addCategory(String brandId,CategoryInput input,Authentication principal) {
        access.requireStaff(brandId,principal,false);
        String categoryId=id();
        jdbc.update("INSERT INTO menu_categories(category_id,brand_id,name,display_order,is_active) VALUES(?,?,?,?,TRUE)",
            categoryId,brandId,trim(input.name()),input.displayOrder());
        outbox.append(brandId,"CATEGORY_ADDED",Map.of("categoryId",categoryId));
        return getCategory(categoryId);
    }
    @Transactional(readOnly=true)
    public CategoryView getCategory(String categoryId) {
        return jdbc.query("SELECT * FROM menu_categories WHERE category_id=? AND is_active=TRUE",
           CATEGORY,categoryId).stream().findFirst().orElseThrow(()->missing("Category"));
    }
    @Transactional(readOnly=true)
    public List<CategoryView> categories(String brandId) {
        restaurants.brand(brandId);
        return jdbc.query("SELECT * FROM menu_categories WHERE brand_id=? AND is_active=TRUE "
                +"ORDER BY display_order,category_id",CATEGORY,brandId);
    }
    @Transactional
    public void disableCategory(String categoryId,Authentication principal) {
        String brandId=categoryBrand(categoryId);
        access.requireStaff(brandId,principal,false);
        jdbc.update("UPDATE menu_categories SET is_active=FALSE WHERE category_id=?",categoryId);
        outbox.append(brandId,"CATEGORY_DISABLED",Map.of("categoryId",categoryId));
    }
    @Transactional
    public ItemView addItem(ItemInput input,Authentication principal) {
        String brandId=categoryBrand(input.categoryId());
        access.requireStaff(brandId,principal,false);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM menu_categories WHERE category_id=? AND is_active=TRUE",
            Integer.class,input.categoryId())==0) throw missing("Category");
        String itemId=id();
        jdbc.update("INSERT INTO menu_items(item_id,category_id,name,description,base_price,image_url,"
            +"is_vegetarian,allergens,is_active) VALUES(?,?,?,?,?,?,?,?,TRUE)",
            itemId,input.categoryId(),trim(input.name()),trim(input.description()),input.basePrice(),
            trim(input.imageUrl()),input.vegetarian(),trim(input.allergens()));
        outbox.append(brandId,"MENU_ITEM_CREATED",Map.of("itemId",itemId));
        return item(itemId);
    }
    @Transactional(readOnly=true)
    public ItemView item(String itemId) {
        return jdbc.query("SELECT i.* FROM menu_items i JOIN menu_categories c ON c.category_id=i.category_id "
            +"JOIN brands b ON b.brand_id=c.brand_id WHERE i.item_id=? AND i.is_active=TRUE "
            +"AND c.is_active=TRUE AND b.is_active=TRUE",ITEM,itemId)
            .stream().findFirst().orElseThrow(()->missing("Item"));
    }
    @Transactional
    public ItemView changePrice(String itemId,BigDecimal price,long version,String reason,
                               Authentication principal) {
        String brandId=access.requireItem(itemId,principal);
        if(price==null||price.signum()<=0||price.scale()>2)
            throw bad("INVALID_PRICE","A positive two-decimal price is required");
        if(reason==null||reason.isBlank()||reason.length()>255)
            throw bad("REASON_REQUIRED","A change reason (max 255 characters) is required");
        BigDecimal old=jdbc.queryForObject("SELECT base_price FROM menu_items WHERE item_id=?",
            BigDecimal.class,itemId);
        int updated=jdbc.update("UPDATE menu_items SET base_price=?,version=version+1 "
            +"WHERE item_id=? AND version=? AND is_active=TRUE",price,itemId,version);
        if(updated!=1)throw new DomainException(HttpStatus.CONFLICT,"VERSION_CONFLICT","Refresh item version before updating");
        jdbc.update("INSERT INTO menu_item_price_history(item_id,old_price,new_price,changed_by,change_reason)"
            +"VALUES(?,?,?,?,?)",itemId,old,price,access.user(principal),reason.trim());
        outbox.append(brandId,"MENU_ITEM_PRICE_CHANGED",Map.of("itemId",itemId,"old",old,"new",price));
        return item(itemId);
    }
    @Transactional
    public void disableItem(String itemId,Authentication principal) {
        String brandId=access.requireItem(itemId,principal);
        jdbc.update("UPDATE menu_items SET is_active=FALSE,version=version+1 WHERE item_id=?",itemId);
        outbox.append(brandId,"MENU_ITEM_DISABLED",Map.of("itemId",itemId));
    }
    @Transactional
    public void overrideItem(String outletId,String itemId,StatusInput req,Authentication principal) {
        String brandId=access.requireOutlet(outletId,principal);
        String itemBrand=access.requireItem(itemId,principal);
        if(!brandId.equals(itemBrand))
            throw bad("CROSS_BRAND_ITEM","Item does not belong to this outlet's brand");
        jdbc.update("INSERT INTO outlet_item_availability(outlet_id,item_id,status,override_price) "
            +"VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE status=VALUES(status),override_price=VALUES(override_price)",
            outletId,itemId,req.status().name(),req.overridePrice());
        outbox.append(outletId,"OUTLET_ITEM_CHANGED",Map.of("itemId",itemId));
    }

    @Transactional
    public GroupView addGroup(String brandId,ModifierGroupInput input,Authentication principal) {
        access.requireStaff(brandId,principal,false);
        if(input.minSelection()>input.maxSelection())throw bad("INVALID_GROUP","minSelection exceeds maxSelection");
        String groupId=id();
        jdbc.update("INSERT INTO modifier_groups(group_id,brand_id,name,min_selection,max_selection) VALUES(?,?,?,?,?)",
            groupId,brandId,trim(input.name()),input.minSelection(),input.maxSelection());
        outbox.append(brandId,"MODIFIER_GROUP_CREATED",Map.of("groupId",groupId));
        return group(groupId);
    }
    @Transactional(readOnly=true)
    public GroupView group(String groupId) {
        List<GroupView> result=jdbc.query("SELECT * FROM modifier_groups WHERE group_id=?",
            (r,n)->new GroupView(r.getString("group_id"),r.getString("brand_id"),
                r.getString("name"),r.getInt("min_selection"),r.getInt("max_selection"),
                modifiers(r.getString("group_id"))),groupId);
        return result.stream().findFirst().orElseThrow(()->missing("Modifier group"));
    }
    @Transactional
    public ModifierView addModifier(String groupId,ModifierInput req,Authentication principal) {
        GroupView group=group(groupId);
        access.requireStaff(group.brandId(),principal,false);
        String modifierId=id();
        jdbc.update("INSERT INTO modifiers(modifier_id,group_id,name,price_adjustment) VALUES(?,?,?,?)",
            modifierId,groupId,trim(req.name()),req.priceAdjustment());
        outbox.append(group.brandId(),"MODIFIER_ADDED",Map.of("modifierId",modifierId));
        return new ModifierView(modifierId,groupId,trim(req.name()),req.priceAdjustment());
    }
    @Transactional
    public void attachGroup(String itemId,String groupId,Authentication principal) {
        String itemBrand=access.requireItem(itemId,principal);
        GroupView group=group(groupId);
        if(!itemBrand.equals(group.brandId()))throw bad("CROSS_BRAND_MODIFIER","Modifier group belongs to another brand");
        jdbc.update("INSERT INTO item_modifier_mappings(item_id,group_id) VALUES(?,?) "
            +"ON DUPLICATE KEY UPDATE group_id=VALUES(group_id)",itemId,groupId);
        outbox.append(itemBrand,"ITEM_MODIFIERS_CHANGED",Map.of("itemId",itemId,"groupId",groupId));
    }
    @Transactional(readOnly=true)
    public List<ModifierView> modifiers(String groupId) {
        return jdbc.query("SELECT * FROM modifiers WHERE group_id=? ORDER BY name,modifier_id",
            (r,n)->new ModifierView(r.getString("modifier_id"),r.getString("group_id"),
                r.getString("name"),r.getBigDecimal("price_adjustment")),groupId);
    }

    private List<GroupView> groupsForItem(String itemId) {
        List<String> ids=jdbc.query("SELECT group_id FROM item_modifier_mappings WHERE item_id=? ORDER BY group_id",
            (r,n)->r.getString(1),itemId);
        return ids.stream().map(this::group).toList();
    }
    @Transactional(readOnly=true)
    public MenuView menu(String outletId,int limit,int offset) {
        pagination(limit,offset);
        OutletView outlet=restaurants.outlet(outletId);
        List<MenuItemView> items=jdbc.query(
            "SELECT i.*,COALESCE(a.override_price,i.base_price) effective_price,"
            +"COALESCE(a.status,'AVAILABLE') availability "
            +"FROM menu_items i JOIN menu_categories c ON c.category_id=i.category_id "
            +"LEFT JOIN outlet_item_availability a ON a.item_id=i.item_id AND a.outlet_id=? "
            +"WHERE c.brand_id=? AND c.is_active=TRUE AND i.is_active=TRUE "
            +"ORDER BY c.display_order,i.name,i.item_id LIMIT ? OFFSET ?",
            (rs,n)->new MenuItemView(ITEM.mapRow(rs,n),rs.getBigDecimal("effective_price"),
                rs.getString("availability"),groupsForItem(rs.getString("item_id"))),
            outletId,outlet.brandId(),limit,offset);
        return new MenuView(outlet,items);
    }
    @Transactional(readOnly=true)
    public QuoteResult quote(String outletId,QuoteRequest request) {
        OutletView outlet=restaurants.outlet(outletId);
        if(!outlet.isOpen())
            throw new DomainException(HttpStatus.CONFLICT,"OUTLET_CLOSED","Outlet is not accepting orders");
        BigDecimal total=BigDecimal.ZERO;
        List<QuoteLineResult> response=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(QuoteLine line:request.items()) {
            if(!seen.add(line.itemId()))throw bad("DUPLICATE_ITEM","Send one line per item");
            List<Map<String,Object>> rows=jdbc.queryForList(
                "SELECT i.item_id,i.base_price,COALESCE(a.override_price,i.base_price) price,"
                +"COALESCE(a.status,'AVAILABLE') status FROM menu_items i "
                +"JOIN menu_categories c ON c.category_id=i.category_id "
                +"LEFT JOIN outlet_item_availability a ON a.outlet_id=? AND a.item_id=i.item_id "
                +"WHERE i.item_id=? AND c.brand_id=? AND c.is_active=TRUE AND i.is_active=TRUE",
                outletId,line.itemId(),outlet.brandId());
            if(rows.isEmpty()||!"AVAILABLE".equals(rows.get(0).get("status")))
                throw new DomainException(HttpStatus.CONFLICT,"ITEM_UNAVAILABLE","Item unavailable");
            BigDecimal unit=(BigDecimal)rows.get(0).get("price");
            Set<String> unique=new HashSet<>(line.modifierIds());
            if(unique.size()!=line.modifierIds().size())throw bad("DUPLICATE_MODIFIER","Duplicate modifier");
            List<GroupView> groups=groupsForItem(line.itemId());
            Set<String> known=new HashSet<>();
            for(GroupView group:groups) {
                int selected=0;
                for(ModifierView modifier:group.modifiers()) {
                    known.add(modifier.modifierId());
                    if(unique.contains(modifier.modifierId())) {
                        selected++;
                        Integer blocked=jdbc.queryForObject(
                            "SELECT COUNT(*) FROM outlet_modifier_availability WHERE outlet_id=? "
                                +"AND modifier_id=? AND status<>'AVAILABLE'",Integer.class,
                            outletId,modifier.modifierId());
                        if(blocked!=null&&blocked>0)
                            throw new DomainException(HttpStatus.CONFLICT,"MODIFIER_UNAVAILABLE","Modifier unavailable");
                        unit=unit.add(modifier.priceAdjustment());
                    }
                }
                if(selected<group.minSelection()||selected>group.maxSelection())
                    throw bad("INVALID_SELECTION","Modifier selection violates min/max requirements");
            }
            if(!known.containsAll(unique))throw bad("INVALID_MODIFIER","Modifier is not mapped to the item");
            BigDecimal lineTotal=unit.multiply(BigDecimal.valueOf(line.quantity()));
            total=total.add(lineTotal);
            response.add(new QuoteLineResult(line.itemId(),line.quantity(),unit,lineTotal,
                List.copyOf(line.modifierIds())));
        }
        return new QuoteResult(outletId,"INR",response,total,
            "Catalog quote only; final checkout must revalidate price and availability");
    }
}
