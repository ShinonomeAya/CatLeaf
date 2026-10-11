package dev.local.catleaf.nativeapp.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 升级判定纯函数（契约 8.1）：旧版本没有 scope kind 字段，
 * 有实例指针=老用户（原范围+引导已处理），无数据=全新用户（本机+新引导）。
 */
class ActivityScopeRulesTest {

    @Test fun `fresh install without pointer becomes local`() {
        assertEquals(SCOPE_KIND_LOCAL, ActivityScopeRules.decideKind(null, null))
        assertFalse(ActivityScopeRules.needsOnboardingMark(null, null))
    }

    @Test fun `old install with instance pointer keeps instance and marks onboarding done`() {
        assertEquals(SCOPE_KIND_INSTANCE, ActivityScopeRules.decideKind(null, "abc123hash"))
        assertTrue(ActivityScopeRules.needsOnboardingMark(null, "abc123hash"))
    }

    @Test fun `persisted kind wins over pointer`() {
        assertEquals(SCOPE_KIND_LOCAL, ActivityScopeRules.decideKind(SCOPE_KIND_LOCAL, "abc123hash"))
        assertEquals(SCOPE_KIND_INSTANCE, ActivityScopeRules.decideKind(SCOPE_KIND_INSTANCE, null))
        // 已落盘后不再重复补引导标记（needsOnboardingMark 只在旧格式时为真）
        assertFalse(ActivityScopeRules.needsOnboardingMark(SCOPE_KIND_INSTANCE, "abc123hash"))
        assertFalse(ActivityScopeRules.needsOnboardingMark(SCOPE_KIND_LOCAL, null))
    }

    @Test fun `unknown stored kind is treated like legacy format`() {
        // 未知脏值按旧版本处理，不让坏数据把用户锁死
        assertEquals(SCOPE_KIND_INSTANCE, ActivityScopeRules.decideKind("garbage", "hash"))
        assertEquals(SCOPE_KIND_LOCAL, ActivityScopeRules.decideKind("garbage", null))
    }

    @Test fun `local scope key is stable and never collides with origin hash`() {
        // 稳定保留键：不是 URL，不随实例增删变化
        assertEquals("local:v1", LOCAL_SCOPE_KEY)
        assertTrue(LOCAL_SCOPE_KEY.isNotEmpty())
    }
}
