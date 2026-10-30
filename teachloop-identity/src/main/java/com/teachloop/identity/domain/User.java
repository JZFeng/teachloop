package com.teachloop.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    /** 微信先于邮箱存在：允许为空，资料补全后再写。 */
    @Column(unique = true)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    private String phone;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String locale = "zh-CN";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "wechat_openid")
    private String wechatOpenId;

    /**
     * 2026-10-27：生产环境允许邮箱 + 密码登录。
     * 默认 false（普通教师/学生仍走微信）；只有管理员用 CLI `--create-user` 建的测试账号是 true。
     */
    @Column(name = "password_login_enabled", nullable = false)
    private boolean passwordLoginEnabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 2026-10-27：改密码后置为 now()，早于该时间登录的会话全部作废。 */
    @Column(name = "session_invalid_before")
    private Instant sessionInvalidBefore;

    /**
     * 011 / FR-227：学生的 IANA 时区（如 {@code America/Los_Angeles}），可空。
     * 空值表示沿用回落链：请求头 X-Timezone → 产品默认 Asia/Shanghai（ZoneResolution）。
     */
    @Column(name = "timezone")
    private String timezone;

    protected User() {
    }

    public User(UUID id, UserRole role, String email, String passwordHash, String displayName, Instant createdAt) {
        this.id = id;
        this.role = role;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UserRole getRole() { return role; }
    public String getEmail() { return email; }
    public String getWechatOpenId() { return wechatOpenId; }
    public String getPasswordHash() { return passwordHash; }
    public String getPhone() { return phone; }
    public String getDisplayName() { return displayName; }
    public String getLocale() { return locale; }
    public UserStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isPasswordLoginEnabled() { return passwordLoginEnabled; }
    public Instant getSessionInvalidBefore() { return sessionInvalidBefore; }
    public String getTimezone() { return timezone; }

    public void setRole(UserRole role) { this.role = role; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setLocale(String locale) { this.locale = locale; }
    public void setStatus(UserStatus status) { this.status = status; }
    public void setEmail(String email) { this.email = email; }
    public void setWechatOpenId(String openId) { this.wechatOpenId = openId; }
    public void setPasswordLoginEnabled(boolean passwordLoginEnabled) { this.passwordLoginEnabled = passwordLoginEnabled; }
    public void setSessionInvalidBefore(Instant sessionInvalidBefore) { this.sessionInvalidBefore = sessionInvalidBefore; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
}
