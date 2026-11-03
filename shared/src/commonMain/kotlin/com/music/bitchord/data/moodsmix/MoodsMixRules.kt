package com.music.bitchord.data.moodsmix

import com.music.bitchord.data.model.TopPickMood

enum class FlowMood(
    val title: String,
    val params: String?,
    val shelfWords: List<String>,
) {
    CHILL(
        "Chill",
        TopPickMood.CHILL.params,
        listOf("chill", "relax", "laidback", "lo-fi", "lofi", "ambient", "calm", "slow", "acoustic", "soft", "peaceful", "sleep"),
    ),
    WORKOUT(
        "Workout",
        TopPickMood.FITNESS.params,
        listOf("workout", "gym", "cardio", "power", "energy", "fitness", "hype", "pump", "training", "heavy", "run", "motivation"),
    ),
    FOCUS(
        "Focus",
        TopPickMood.FOCUS.params,
        listOf("focus", "study", "work", "instrumental", "calm", "reading", "piano", "coding", "concentration", "ambient"),
    ),
    PARTY(
        "Party",
        TopPickMood.PARTY.params,
        listOf("party", "dance", "club", "upbeat", "banger", "night", "disco", "rave", "house", "edm", "festival", "summer"),
    ),
    LOVE(
        "Love",
        TopPickMood.LOVE.params,
        listOf("love", "romance", "romantic", "sweet", "heart", "forever", "kiss", "together", "crush", "darling"),
    ),
    SAD(
        "Sad",
        TopPickMood.FEELING_BLUE.params,
        listOf("sad", "heartbreak", "melancholy", "lonely", "tears", "sorrow", "crying", "alone", "miss", "pain", "broken", "blue"),
    ),
}

object MoodsMixRules {
    const val MOODS_MIX_NAME = "Moods"
    const val MOODS_MIX_BROWSE_ID = "bitchord:moods_mix"
    const val INITIAL_TRACKS = 50
    const val TOPUP_TRACKS = 10
}
