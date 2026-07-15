package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.User;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class AdminUserServiceTest {

    @Test
    void percentageIsRoundedAndSafeForEmptyTraffic() {
        assertEquals(12.35, AdminUserService.percentage(10, 81));
        assertEquals(0, AdminUserService.percentage(10, 0));
    }

    @Test
    void normalizesMongoUserIdsForAnalyticsMatching() {
        ObjectId id = new ObjectId();

        assertEquals(id.toHexString(), AdminUserService.idString(id));
        assertEquals("string-id", AdminUserService.idString("string-id"));
        assertNull(AdminUserService.idString(42));
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
}
