package com.musicone

import java.util.Calendar
import kotlin.random.Random

/** 欢迎页一次展示的灵感气泡数量。 */
internal const val AI_WELCOME_BUBBLE_COUNT = 4

/** 灵感气泡大类；同屏气泡互不同类，权重只决定大类被抽中的频率，与句子数量无关。 */
internal enum class AiWelcomeCategory(val weight: Int) {
    TIME(0), SCENE(3), MOOD(3), GENRE(3), ARTIST(3), LISTENING(2), PLAYLIST(2), PLAYBACK(1), KNOWLEDGE(1)
}

/** 按设备本地小时划分的时段。 */
internal enum class AiWelcomeSlot(val hours: IntRange) {
    DAWN(5..8), MORNING(9..11), NOON(12..13), AFTERNOON(14..16), DUSK(17..19), EVENING(20..23), LATE_NIGHT(0..4)
}

/** 按月份划分的季节（以北半球为准）。 */
internal enum class AiWelcomeSeason(val months: Set<Int>) {
    SPRING(setOf(Calendar.MARCH, Calendar.APRIL, Calendar.MAY)),
    SUMMER(setOf(Calendar.JUNE, Calendar.JULY, Calendar.AUGUST)),
    AUTUMN(setOf(Calendar.SEPTEMBER, Calendar.OCTOBER, Calendar.NOVEMBER)),
    WINTER(setOf(Calendar.DECEMBER, Calendar.JANUARY, Calendar.FEBRUARY))
}

/**
 * 单条灵感句。slots／seasons 为空表示不限；weekendOnly 只在周六日出现；
 * needsWeb 只在联网开启时出现；artist 仅相似艺人类登记，用于校验同一艺人不重复。
 */
internal data class AiWelcomeSuggestion(
    val text: String,
    val category: AiWelcomeCategory,
    val slots: Set<AiWelcomeSlot> = emptySet(),
    val seasons: Set<AiWelcomeSeason> = emptySet(),
    val weekendOnly: Boolean = false,
    val needsWeb: Boolean = false,
    val artist: String? = null
)

/** 欢迎页大标题；slots 为空表示任何时段都可出现。 */
internal data class AiWelcomeHeading(val title: String, val subtitle: String, val slots: Set<AiWelcomeSlot> = emptySet())

/** 抽取时刻：时段、季节与是否周末。 */
internal data class AiWelcomeMoment(val slot: AiWelcomeSlot, val season: AiWelcomeSeason, val weekend: Boolean)

internal fun aiWelcomeMoment(now: Calendar): AiWelcomeMoment {
    val hour = now.get(Calendar.HOUR_OF_DAY)
    val month = now.get(Calendar.MONTH)
    val day = now.get(Calendar.DAY_OF_WEEK)
    return AiWelcomeMoment(
        AiWelcomeSlot.entries.first { hour in it.hours },
        AiWelcomeSeason.entries.first { month in it.months },
        day == Calendar.SATURDAY || day == Calendar.SUNDAY
    )
}

internal fun AiWelcomeSuggestion.fits(moment: AiWelcomeMoment, webEnabled: Boolean): Boolean =
    (slots.isEmpty() || moment.slot in slots) && (seasons.isEmpty() || moment.season in seasons) &&
        (!weekendOnly || moment.weekend) && (webEnabled || !needsWeb)

/**
 * 抽取欢迎页气泡：1 条当前时段句，其余按大类权重不放回抽取，保证同屏不同类；最后整体打乱顺序。
 */
internal fun aiWelcomeSuggestions(
    now: Calendar,
    webEnabled: Boolean,
    random: Random = Random.Default,
    pool: List<AiWelcomeSuggestion> = aiWelcomeSuggestionPool
): List<AiWelcomeSuggestion> {
    val moment = aiWelcomeMoment(now)
    val eligible = pool.filter { it.fits(moment, webEnabled) }
    val picks = mutableListOf<AiWelcomeSuggestion>()
    eligible.filter { it.category == AiWelcomeCategory.TIME }.randomOrNull(random)?.let(picks::add)
    val groups = eligible.filter { it.category.weight > 0 }.groupBy { it.category }.toMutableMap()
    while (picks.size < AI_WELCOME_BUBBLE_COUNT && groups.isNotEmpty()) {
        var roll = random.nextInt(groups.keys.sumOf { it.weight })
        val category = groups.keys.first { roll -= it.weight; roll < 0 }
        picks += groups.remove(category)!!.random(random)
    }
    return picks.shuffled(random)
}

/** 联网关闭后，把依赖联网的气泡原位换成同类不需联网的句子，其余气泡不动。 */
internal fun aiWelcomeReplaceWebSuggestions(
    current: List<AiWelcomeSuggestion>,
    now: Calendar,
    random: Random = Random.Default,
    pool: List<AiWelcomeSuggestion> = aiWelcomeSuggestionPool
): List<AiWelcomeSuggestion> {
    if (current.none { it.needsWeb }) return current
    val moment = aiWelcomeMoment(now)
    val used = current.mapTo(mutableSetOf()) { it.text }
    return current.map { item ->
        if (!item.needsWeb) return@map item
        val candidates = pool.filter { it.category == item.category && it.text !in used && it.fits(moment, false) }
            .ifEmpty { pool.filter { it.category.weight > 0 && it.text !in used && it.fits(moment, false) } }
        candidates.random(random).also { used += it.text }
    }
}

internal fun aiWelcomeHeading(now: Calendar, random: Random = Random.Default,
    headings: List<AiWelcomeHeading> = aiWelcomeHeadings): AiWelcomeHeading {
    val slot = aiWelcomeMoment(now).slot
    return headings.filter { it.slots.isEmpty() || slot in it.slots }.random(random)
}

/** 句子池构建器：+ 添加句子，during／inSeason／onWeekend／withWeb 为块内句子加限定。 */
internal class AiWelcomePoolBuilder(private val category: AiWelcomeCategory) {
    val items = ArrayList<AiWelcomeSuggestion>()
    private var slots: Set<AiWelcomeSlot> = emptySet()
    private var seasons: Set<AiWelcomeSeason> = emptySet()
    private var weekendOnly = false
    private var needsWeb = false

    operator fun String.unaryPlus() = add(this, null)

    /** 相似艺人句：显式登记艺人名。 */
    fun artist(name: String, text: String) = add(text, name)

    fun during(vararg value: AiWelcomeSlot, block: AiWelcomePoolBuilder.() -> Unit) {
        val previous = slots; slots = value.toSet(); block(); slots = previous
    }

    fun inSeason(value: AiWelcomeSeason, block: AiWelcomePoolBuilder.() -> Unit) {
        val previous = seasons; seasons = setOf(value); block(); seasons = previous
    }

    fun onWeekend(block: AiWelcomePoolBuilder.() -> Unit) {
        val previous = weekendOnly; weekendOnly = true; block(); weekendOnly = previous
    }

    fun withWeb(block: AiWelcomePoolBuilder.() -> Unit) {
        val previous = needsWeb; needsWeb = true; block(); needsWeb = previous
    }

    private fun add(text: String, artist: String?) {
        items += AiWelcomeSuggestion(text, category, slots, seasons, weekendOnly, needsWeb, artist)
    }
}

/** 非 inline：每个大类的句子编译进各自的方法，避免单个初始化方法超出 JVM 64KB 上限。 */
internal fun aiWelcomePool(category: AiWelcomeCategory, block: AiWelcomePoolBuilder.() -> Unit): List<AiWelcomeSuggestion> =
    AiWelcomePoolBuilder(category).apply(block).items
