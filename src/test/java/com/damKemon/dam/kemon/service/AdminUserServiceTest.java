package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.User;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AdminUserServiceTest {

    @Test
    void percentageIsRoundedAndSafeForEmptyTraffic() {
        assertEquals(12.35, AdminUserService.percentage(10, 81));
        assertEquals(0, AdminUserService.percentage(10, 0));
    }

    @Test
    void adminPayloadNeverExposesCredentialsOrTokens() {
        User user = User.builder()
                .id("u1")
                .email("buyer@example.com")
                .passwordHash("secret-hash")
                .googleSub("google-id")
                .verifyToken("verify-secret")
                .resetToken("reset-secret")
                .build();

        Map<String, Object> safe = AdminUserService.safeUser(user);

        assertEquals("buyer@example.com", safe.get("email"));
        assertFalse(safe.containsKey("passwordHash"));
        assertFalse(safe.containsKey("googleSub"));
        assertFalse(safe.containsKey("verifyToken"));
        assertFalse(safe.containsKey("resetToken"));
    }

    @Test
    void normalizesMongoAndStringUserIdsForActivationJoins() {
        ObjectId id = new ObjectId();
        assertEquals(id.toHexString(), AdminUserService.idString(id));
        assertEquals("u1", AdminUserService.idString("u1"));
    }
}
