package dev.local.catleaf.nativeapp.data.settings

import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase

/**
 * 活动范围（契约 8.1，ADR 003）：内部使用范围只有**本机**或**一个实例**，一次只有一个。
 *
 * 各表的 `instanceKey` 列直接复用为 scope key（契约允许，不做无意义全库重命名）：
 * - 本机范围用稳定保留键 [LOCAL_SCOPE_KEY]——它不是 HTTP 地址、不与任何 origin 哈希冲突，
 *   也不存在对应的 [dev.local.catleaf.nativeapp.data.local.InstanceEntity]（禁止为本机构造假实例/假密钥）；
 * - 实例范围沿用既有 origin 稳定哈希，旧 localId/feedId/凭据索引原样保留。
 */
const val LOCAL_SCOPE_KEY = "local:v1"

const val SCOPE_KIND_LOCAL = "local"
const val SCOPE_KIND_INSTANCE = "instance"

enum class ScopeKind { LOCAL, INSTANCE }

data class ActivityScope(
    val kind: ScopeKind,
    /** 本机=local:v1；实例=origin 稳定哈希（即各表 instanceKey 列的取值） */
    val key: String,
    /**
     * kind=INSTANCE 时实例行是否仍在。false 表示 DataStore 指针悬空（实例行被删）：
     * 走 P01 修复，绝不创建空范围覆盖旧数据（契约 8.1）。
     */
    val instanceReady: Boolean,
)

/**
 * 升级判定纯函数（JVM 可测）：旧版本 DataStore 没有 scope kind 字段，
 * 首次按既有数据推断——有实例指针就是老用户（原实例范围 + 引导已处理），
 * 无任何数据才是全新用户（本机范围，引导待 L04 显示）。
 */
object ActivityScopeRules {

    fun decideKind(storedKind: String?, instancePointer: String?): String = when {
        storedKind == SCOPE_KIND_LOCAL -> SCOPE_KIND_LOCAL
        storedKind == SCOPE_KIND_INSTANCE -> SCOPE_KIND_INSTANCE
        // 旧版本升级：有实例指针 → 原范围；否则全新 → 本机
        instancePointer != null -> SCOPE_KIND_INSTANCE
        else -> SCOPE_KIND_LOCAL
    }

    /** 老用户升级（旧格式 + 有实例指针）要补「引导已处理」，不重复新引导 */
    fun needsOnboardingMark(storedKind: String?, instancePointer: String?): Boolean =
        storedKind == null && instancePointer != null
}

/**
 * 当前生效的范围键（不发网络、不读 Room）：本机=local:v1，实例=origin 哈希。
 * 任务完成时与冻结值比对即可判断「范围是否已切换」（local:v1 与任何 origin 哈希不相等）。
 */
suspend fun SettingsStore.currentScopeKey(): String {
    val snap = current()
    val kind = ActivityScopeRules.decideKind(snap.activeScopeKind, snap.currentInstanceKey)
    return if (kind == SCOPE_KIND_INSTANCE) snap.currentInstanceKey.orEmpty() else LOCAL_SCOPE_KEY
}

/**
 * 活动范围解析（会写入首次迁移结果）。VM 与根导航共用；只读 Room/DataStore，不发网络。
 */
class ActivityScopeResolver(
    private val settings: SettingsStore,
    private val db: CatLeafDatabase,
) {

    suspend fun resolve(): ActivityScope {
        val snap = settings.current()
        val kindName = ActivityScopeRules.decideKind(snap.activeScopeKind, snap.currentInstanceKey)
        if (snap.activeScopeKind == null) {
            // 首次迁移落盘，之后判定稳定（不随密钥可读性漂移——契约 8.1 禁止因密钥问题覆盖范围）
            settings.setActiveScopeKind(kindName)
            if (ActivityScopeRules.needsOnboardingMark(snap.activeScopeKind, snap.currentInstanceKey)) {
                settings.setOnboardingDone(true)
            }
        }
        return when (kindName) {
            SCOPE_KIND_INSTANCE -> {
                val key = snap.currentInstanceKey.orEmpty()
                ActivityScope(ScopeKind.INSTANCE, key, instanceReady = db.instances().get(key) != null)
            }
            else -> ActivityScope(ScopeKind.LOCAL, LOCAL_SCOPE_KEY, instanceReady = false)
        }
    }
}
