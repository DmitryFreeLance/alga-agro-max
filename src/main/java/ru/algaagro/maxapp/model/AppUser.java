package ru.algaagro.maxapp.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "max_user_id", nullable = false, unique = true)
    private Long maxUserId;

    @Column(nullable = false)
    private String displayName;

    private String username;

    @Column(nullable = false)
    private boolean admin;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private Instant lastSeenAt;

    @Lob
    @Column(nullable = false)
    private String cartJson = "[]";

    @Lob
    @Column(nullable = false)
    private String checkoutDraftJson = "{}";

    private Instant cartUpdatedAt;

    private Instant checkoutDraftUpdatedAt;

    @PrePersist
    public void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        lastSeenAt = now;
        if (cartJson == null || cartJson.isBlank()) {
            cartJson = "[]";
        }
        if (checkoutDraftJson == null || checkoutDraftJson.isBlank()) {
            checkoutDraftJson = "{}";
        }
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getMaxUserId() {
        return maxUserId;
    }

    public void setMaxUserId(Long maxUserId) {
        this.maxUserId = maxUserId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public boolean isAdmin() {
        return admin;
    }

    public void setAdmin(boolean admin) {
        this.admin = admin;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public String getCartJson() {
        return cartJson;
    }

    public void setCartJson(String cartJson) {
        this.cartJson = cartJson;
    }

    public String getCheckoutDraftJson() {
        return checkoutDraftJson;
    }

    public void setCheckoutDraftJson(String checkoutDraftJson) {
        this.checkoutDraftJson = checkoutDraftJson;
    }

    public Instant getCartUpdatedAt() {
        return cartUpdatedAt;
    }

    public void setCartUpdatedAt(Instant cartUpdatedAt) {
        this.cartUpdatedAt = cartUpdatedAt;
    }

    public Instant getCheckoutDraftUpdatedAt() {
        return checkoutDraftUpdatedAt;
    }

    public void setCheckoutDraftUpdatedAt(Instant checkoutDraftUpdatedAt) {
        this.checkoutDraftUpdatedAt = checkoutDraftUpdatedAt;
    }
}
