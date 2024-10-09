package org.example.menuorder.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.JsonSyntaxException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;


import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class OrderController {

    @Autowired
    private JdbcTemplate jdbcTemplate;


    @Value("${stripe.webhook}")
    private String STRIPE_WEBHOOK_SECRET;

    @PostMapping("/api/orders/createCheckoutSession")
    public Map<String, Object> createCheckoutSession(@RequestBody Map<String, Object> paymentData) {
        int amount = (int) paymentData.get("amount");
        String currency = (String) paymentData.get("currency");
        String orderId = (String) paymentData.get("orderId");
        System.out.println(orderId);
        try {
            SessionCreateParams params = SessionCreateParams.builder()
                    .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
                    .addLineItem(
                            SessionCreateParams.LineItem.builder()
                                    .setPriceData(
                                            SessionCreateParams.LineItem.PriceData.builder()
                                                    .setCurrency(currency)
                                                    .setProductData(
                                                            SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                                                    .setName("Order " + orderId)
                                                                    .build()
                                                    )
                                                    .setUnitAmount((long) amount)
                                                    .build()
                                    )
                                    .setQuantity(1L)
                                    .build()
                    )
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setSuccessUrl("http://localhost:3000/order-record")
                    .setCancelUrl("http://localhost:3000/order-record")
                    .setPaymentIntentData(
                            SessionCreateParams.PaymentIntentData.builder()
                                    .putMetadata("orderId", orderId)
                                    .build()
                    )
                    .build();

            Session session = Session.create(params);

            Map<String, Object> response = new HashMap<>();
            response.put("sessionId", session.getId());
            return response;
        } catch (Exception e) {
            e.printStackTrace();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error creating checkout session");
        }
    }

    @PostMapping("/api/orders/stripe")
    public void handleStripeEvent(@RequestBody String payload, @RequestHeader("Stripe-Signature") String sigHeader) throws SignatureVerificationException {
        // Verify the webhook signature
        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, STRIPE_WEBHOOK_SECRET);
        } catch (JsonSyntaxException | SignatureVerificationException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payload");
        }

        // Handle the event
        switch (event.getType()) {
            case "payment_intent.succeeded":
                PaymentIntent paymentIntent = (PaymentIntent) event.getDataObjectDeserializer().getObject().orElse(null);
                if (paymentIntent != null) {
                    System.out.println("Payment Intent: " + paymentIntent);
                    String orderId = paymentIntent.getMetadata().get("orderId");
                    if (orderId != null) {
                        String sql = "UPDATE `order` SET isPaid = 1 WHERE id = ?";
                        jdbcTemplate.update(sql, orderId);
                    } else {
                        System.out.println("Payment intent metadata misses ID。");
                    }
                }
                break;


            default:
                System.out.println("Unhandled event type: {}");
                break;
        }
    }


    public int insertOrder(int userId, int totalPrice) {
        String sql = "INSERT INTO `order` (userId, totalPrice, isPaid) VALUES (?, ?, ?)";

        // use KeyHolder to get main key
        KeyHolder keyHolder = new GeneratedKeyHolder();
        int isPaid = 0;
        jdbcTemplate.update(new PreparedStatementCreator() {
            @Override
            public PreparedStatement createPreparedStatement(Connection connection) throws SQLException {
                PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
                ps.setInt(1, userId);
                ps.setInt(2, totalPrice);
                ps.setInt(3, isPaid);
                return ps;
            }
        }, keyHolder);

        return keyHolder.getKey().intValue();
    }

    @GetMapping("/api/orders/menu")
    public ResponseEntity<?> getMenu() {
        String sql = "SELECT * FROM menu ";
        List<Map<String, Object>> menu = jdbcTemplate.queryForList(sql);
        String jsonStr = JSON.toJSONString(menu);
        return ResponseEntity.ok(jsonStr);
    }

    @PostMapping("/api/orders/orderMenu")
    public ResponseEntity<?> orderMenus(@RequestBody Map<String, Object> jsonObj, @RequestHeader(value = "userId", required = false) String userId) {
        int totalPrice = (Integer) jsonObj.get("totalPrice");
        int orderId = insertOrder(Integer.parseInt(userId),totalPrice);
        List<Map<String, Object>> menu = (List<Map<String, Object>>) jsonObj.get("menu");
        for (int i = 0; i < menu.size(); i++){
            int menuId = (Integer) menu.get(i).get("menuId");
            int quantity = (Integer) menu.get(i).get("quantity");
            int singlePrice = (Integer) menu.get(i).get("singlePrice");
            int price = singlePrice * quantity;
            String sql = "INSERT INTO userOrder (orderId, price, menuId, quantity) VALUES (?, ?, ?, ?)";
            jdbcTemplate.update(sql, orderId, price,menuId,quantity);
        }
        JSONObject obj = new JSONObject();
        obj.put("message", "success");
        return ResponseEntity.ok(obj);
    }

    @GetMapping("/api/orders/ordered")
    public ResponseEntity<?> searchTours(@RequestHeader(value = "userId", required = false) String userId) {

        String sql = "SELECT o.id AS orderId, o.totalPrice, o.isPaid, uo.quantity, uo.price, m.name " +
                "FROM `order` o " +
                "LEFT JOIN userOrder uo ON uo.orderId = o.id " +
                "LEFT JOIN menu m ON uo.menuId = m.id " +
                "WHERE o.userId = ?";

        List<Map<String, Object>> order = jdbcTemplate.queryForList(sql, userId);
        String jsonStr = JSON.toJSONString(order);
        System.out.println(jsonStr);
        return ResponseEntity.ok(jsonStr);
    }
}
