package com.resolveai.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Everything the demo mode can be told, in one place.
 *
 * <p><b>The demo mode is off unless switched on</b> ({@code resolveai.demo.enabled}), in
 * every profile - so a real deployment never grows public one-click logins by accident.
 *
 * <ul>
 *   <li>{@code tenant-slug} - the tenant the demo logins, showcase and storm act on.
 *       Locally that is the seeded {@code acme}; on the live demo it is a tenant of its
 *       own.</li>
 *   <li>{@code seed-tenant} - create that tenant if it does not exist. Off locally, where
 *       {@code IamSeeder} owns tenant creation.</li>
 *   <li>{@code password} - the demo tenant's shared password, <b>only</b> read when this
 *       seeder creates the tenant. Never the local seed password: the demo tenant is public,
 *       and a password reused from anywhere else would make it the weakest account there.</li>
 *   <li>{@code allow-admin} - offer a one-click ADMIN login. Off on the public demo: an
 *       admin can raise the AI budget, rewrite SLA policy and delete knowledge, none of
 *       which an anonymous visitor should be one click away from. On locally.</li>
 *   <li>{@code reset-cron} - rotate the demo tenant on a schedule, so visitors' edits and
 *       an ageing queue do not accumulate. {@code -} (the default) disables it.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "resolveai.demo.enabled", havingValue = "true")
public class DemoSettings {

    private final String tenantSlug;
    private final boolean seedTenant;
    private final String password;
    private final boolean stormOnSeed;
    private final int stormCooldownMinutes;
    private final boolean allowAdmin;

    public DemoSettings(
            @Value("${resolveai.demo.tenant-slug:demo}") String tenantSlug,
            @Value("${resolveai.demo.seed-tenant:true}") boolean seedTenant,
            @Value("${resolveai.demo.password:}") String password,
            @Value("${resolveai.demo.storm-on-seed:true}") boolean stormOnSeed,
            @Value("${resolveai.demo.storm-cooldown-minutes:10}") int stormCooldownMinutes,
            @Value("${resolveai.demo.allow-admin:false}") boolean allowAdmin) {
        this.tenantSlug = tenantSlug;
        this.seedTenant = seedTenant;
        this.password = password;
        this.stormOnSeed = stormOnSeed;
        this.stormCooldownMinutes = stormCooldownMinutes;
        this.allowAdmin = allowAdmin;
    }

    public String tenantSlug() { return tenantSlug; }
    public boolean seedTenant() { return seedTenant; }
    public String password() { return password; }
    public boolean stormOnSeed() { return stormOnSeed; }
    public int stormCooldownMinutes() { return stormCooldownMinutes; }
    public boolean allowAdmin() { return allowAdmin; }
}
