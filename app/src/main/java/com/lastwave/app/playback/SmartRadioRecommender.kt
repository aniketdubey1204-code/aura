package com.lastwave.app.playback

import com.lastwave.app.data.generate.TasteProfile
import com.lastwave.app.data.generate.TasteProfileProvider
import com.lastwave.app.data.repository.SongPlayStatsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Intelligent Up-Next radio recommender modelled after YouTube Music's autoplay algorithm.
 *
 * It blends two critical dimensions:
 * 1. Current Seed Mood & Vibe: Preserves emotional resonance (e.g. sad, chill, energetic, romantic)
 *    and prevents acoustic whiplash.
 * 2. User's Scrobbling Profile (Last.fm & Local Play History): Integrates top artists, high-affinity
 *    tags, deep cuts the user hasn't overplayed, and eliminates tracks with high skip history.
 *
 * Implements YouTube Music's signature 2:1 discovery-to-familiarity interleaving pattern.
 */
@Singleton
class SmartRadioRecommender @Inject constructor(
    private val tasteProfileProvider: dagger.Lazy<TasteProfileProvider>,
    private val songPlayStatsRepository: dagger.Lazy<SongPlayStatsRepository>,
) {

    private enum class TrackMood {
        SAD_MELANCHOLIC,
        CHILL_AMBIENT,
        UPBEAT_ENERGETIC,
        ROMANTIC_LOVE,
        DARK_INTENSE,
        NEUTRAL,
    }

    private val sadKeywords = setOf(
        "sad", "hurt", "cry", "crying", "lonely", "tears", "heartbreak", "broken",
        "sorrow", "pain", "alone", "ballad", "goodbye", "acoustic", "melanchol",
        "emo", "dark", "blue", "die", "death", "empty", "lost", "miss you",
        "grief", "suffering", "regret", "depress", "unhappy",
    )

    private val chillKeywords = setOf(
        "chill", "lofi", "lo-fi", "rain", "night", "sleep", "dream", "soft",
        "slow", "calm", "breeze", "cloud", "stars", "midnight", "ambient",
        "peaceful", "cozy", "lazy", "dusk", "dawn", "quiet", "relax", "waves",
    )

    private val upbeatKeywords = setOf(
        "dance", "party", "club", "energy", "beat", "fast", "hype", "jump",
        "fire", "shake", "rhythm", "electro", "rock", "workout", "bass",
        "banger", "speed", "wild", "rave", "power", "summer", "groove",
    )

    private val romanticKeywords = setOf(
        "love", "baby", "kiss", "forever", "hold", "mine", "romance", "darling",
        "sweet", "romantic", "marry", "beautiful", "honey", "heart", "beloved",
    )

    private val darkKeywords = setOf(
        "rage", "trap", "drill", "metal", "scream", "savage", "danger", "toxic",
        "psycho", "ghost", "sinister", "shadow", "demon", "wicked", "devil",
    )

    private val junkTitleKeywords = setOf(
        "mashup", "mash up", "mash-up", "jukebox", "mega mix", "megamix",
        "non stop", "nonstop", "audio jukebox", "full album", "full songs", "compilation",
        "slowed + reverb", "slowed reverb", "slowed & reverb", "bass boosted", "8d audio",
        "karaoke", "ringtone", "instrumental", "1 hour", "10 hour", "clean version", "sped up",
    )

    private fun detectMood(text: String): TrackMood {
        val lower = text.lowercase()
        val tokens = lower.split(Regex("[\\s,._\\-]+")).filter { it.isNotBlank() }

        var sadHits = 0
        var chillHits = 0
        var upbeatHits = 0
        var romanticHits = 0
        var darkHits = 0

        for (token in tokens) {
            if (sadKeywords.any { token.contains(it) }) sadHits++
            if (chillKeywords.any { token.contains(it) }) chillHits++
            if (upbeatKeywords.any { token.contains(it) }) upbeatHits++
            if (romanticKeywords.any { token.contains(it) }) romanticHits++
            if (darkKeywords.any { token.contains(it) }) darkHits++
        }

        val max = maxOf(sadHits, chillHits, upbeatHits, romanticHits, darkHits)
        if (max == 0) return TrackMood.NEUTRAL

        return when (max) {
            sadHits -> TrackMood.SAD_MELANCHOLIC
            chillHits -> TrackMood.CHILL_AMBIENT
            upbeatHits -> TrackMood.UPBEAT_ENERGETIC
            romanticHits -> TrackMood.ROMANTIC_LOVE
            darkHits -> TrackMood.DARK_INTENSE
            else -> TrackMood.NEUTRAL
        }
    }

    private fun isJunkTitle(title: String): Boolean {
        val lower = title.lowercase()
        return junkTitleKeywords.any { lower.contains(it) }
    }

    /**
     * Curates and re-ranks incoming YouTube Music radio candidate tracks.
     */
    suspend fun curateUpNextQueue(
        seedTrack: PlayableTrack,
        incomingTracks: List<PlayableTrack>,
        existingVideoIds: Set<String>,
        existingKeys: Set<String>,
        existingSongKeys: Set<String> = emptySet(),
        recentVideoIds: Set<String> = emptySet(),
        recentKeys: Set<String> = emptySet(),
        recentSongKeys: Set<String> = emptySet(),
    ): List<PlayableTrack> {
        if (incomingTracks.isEmpty()) return emptyList()

        val seedVideoId = seedTrack.videoId?.takeIf(String::isNotBlank)
        val seedSongKey = PureRadioQueue.normalizedSongKey(seedTrack.title, seedTrack.artist)
        val seedMood = detectMood("${seedTrack.title} ${seedTrack.artist}")

        // 1. Fetch user's taste profile (Last.fm scrobbles, top artists, tags, deep cuts)
        val tasteProfile: TasteProfile? = runCatching {
            tasteProfileProvider.get().get()
        }.getOrNull()

        val topArtists = tasteProfile?.topArtistNames.orEmpty()
        val artistAffinity = tasteProfile?.artistAffinity.orEmpty()
        val topTrackKeys = tasteProfile?.topTrackKeys.orEmpty()
        val topTags = tasteProfile?.topTags.orEmpty()

        // 2. Base Deduplication & Hygiene Filter
        val seenVideoIds = mutableSetOf<String>()
        val seenKeys = mutableSetOf<String>()
        val seenSongKeys = mutableSetOf<String>()

        val eligibleCandidates = incomingTracks.filterIndexed { index, track ->
            val vid = track.videoId?.takeIf(String::isNotBlank) ?: return@filterIndexed false
            if (vid == seedVideoId) return@filterIndexed false

            val key = PureRadioQueue.queueKey(track)
            val songKey = PureRadioQueue.normalizedSongKey(track.title, track.artist)
            if (songKey == seedSongKey) return@filterIndexed false

            if (isJunkTitle(track.title)) return@filterIndexed false

            val isDuplicate = vid in existingVideoIds ||
                vid in recentVideoIds ||
                key in existingKeys ||
                key in recentKeys ||
                songKey in existingSongKeys ||
                songKey in recentSongKeys ||
                !seenVideoIds.add(vid) ||
                !seenKeys.add(key) ||
                !seenSongKeys.add(songKey)

            !isDuplicate
        }

        if (eligibleCandidates.isEmpty()) return emptyList()

        // 3. Multi-dimensional Scoring
        data class ScoredTrack(val track: PlayableTrack, val score: Float, val isFamiliarAnchor: Boolean)

        val scoredTracks = eligibleCandidates.mapIndexed { index, track ->
            var score = 50.0f
            val cleanArtist = track.artist.trim().lowercase()
            val candidateMood = detectMood("${track.title} ${track.artist}")

            // A. Mood Alignment
            if (seedMood != TrackMood.NEUTRAL) {
                if (candidateMood == seedMood) {
                    score += 35.0f // Strong resonance with seed track's emotional space
                } else if (
                    (seedMood == TrackMood.SAD_MELANCHOLIC && candidateMood == TrackMood.UPBEAT_ENERGETIC) ||
                    (seedMood == TrackMood.UPBEAT_ENERGETIC && candidateMood == TrackMood.SAD_MELANCHOLIC)
                ) {
                    score -= 30.0f // Prevent mood jarring clashes
                }
            }

            // B. Last.fm Scrobble Profile & Artist Affinity
            val isFavoriteArtist = topArtists.any { it.equals(cleanArtist, ignoreCase = true) }
            val affinityWeight = artistAffinity[cleanArtist]
                ?: artistAffinity.entries.firstOrNull { it.key.equals(cleanArtist, ignoreCase = true) }?.value
                ?: 0.0

            var isFamiliar = false
            if (isFavoriteArtist || affinityWeight > 0.25) {
                val affinityBonus = (affinityWeight.toFloat().coerceIn(0.2f, 1.0f)) * 30.0f
                score += affinityBonus

                val isScrobbledTrack = topTrackKeys.any { it.contains(track.title, ignoreCase = true) }
                if (!isScrobbledTrack) {
                    // Deep Cut Discovery: Track by an artist user loves, but hasn't over-listened to
                    score += 25.0f
                } else {
                    isFamiliar = true
                    score += 15.0f
                }
            }

            // C. Tag Match
            if (topTags.isNotEmpty()) {
                val matchesTag = topTags.any { tag ->
                    track.title.contains(tag, ignoreCase = true) || track.artist.contains(tag, ignoreCase = true)
                }
                if (matchesTag) score += 15.0f
            }

            // D. Local Play History & Skip Penalty
            val statsRepo = songPlayStatsRepository.get()
            val skips = runCatching { statsRepo.skipCountFor(track.title, track.artist) }.getOrDefault(0)
            if (skips >= 3) {
                score -= 60.0f // User consistently skips this track
            } else if (skips > 0) {
                score -= (skips * 20.0f)
            }

            // E. Seed Continuity
            if (track.artist.equals(seedTrack.artist, ignoreCase = true)) {
                score += 12.0f
            }

            // F. Original YTM Position Decay (preserve YTM's underlying acoustic similarity)
            val ytmPosRatio = 1.0f - (index.toFloat() / eligibleCandidates.size.toFloat())
            score += (ytmPosRatio * 10.0f)

            ScoredTrack(track = track, score = score, isFamiliarAnchor = isFamiliar || (isFavoriteArtist && score >= 75.0f))
        }

        // 4. Interleaving Engine (YouTube Music's 2 Discovery : 1 Familiarity Pattern)
        val validScored = scoredTracks.filter { it.score > 20.0f }
            .sortedByDescending { it.score }

        val discoveries = validScored.filter { !it.isFamiliarAnchor }.map { it.track }.toMutableList()
        val familiarAnchors = validScored.filter { it.isFamiliarAnchor }.map { it.track }.toMutableList()

        val curatedResult = mutableListOf<PlayableTrack>()
        val maxResultSize = minOf(validScored.size, 35)

        // Cap consecutive songs by same artist to prevent single-artist takeover
        val artistCounts = mutableMapOf<String, Int>()
        fun canAddTrack(t: PlayableTrack): Boolean {
            val count = artistCounts.getOrDefault(t.artist.lowercase(), 0)
            return count < 3
        }

        var discoveryCursor = 0
        var familiarCursor = 0

        while (curatedResult.size < maxResultSize && (discoveryCursor < discoveries.size || familiarCursor < familiarAnchors.size)) {
            // Pick 2 Discoveries
            repeat(2) {
                if (discoveryCursor < discoveries.size && curatedResult.size < maxResultSize) {
                    val candidate = discoveries[discoveryCursor++]
                    if (canAddTrack(candidate)) {
                        curatedResult.add(candidate)
                        artistCounts[candidate.artist.lowercase()] = artistCounts.getOrDefault(candidate.artist.lowercase(), 0) + 1
                    }
                }
            }

            // Pick 1 Familiar Anchor (User's scrobble taste)
            if (familiarCursor < familiarAnchors.size && curatedResult.size < maxResultSize) {
                val candidate = familiarAnchors[familiarCursor++]
                if (canAddTrack(candidate)) {
                    curatedResult.add(candidate)
                    artistCounts[candidate.artist.lowercase()] = artistCounts.getOrDefault(candidate.artist.lowercase(), 0) + 1
                }
            } else if (discoveryCursor < discoveries.size && curatedResult.size < maxResultSize) {
                // If familiar ran out, continue with top discoveries
                val candidate = discoveries[discoveryCursor++]
                if (canAddTrack(candidate)) {
                    curatedResult.add(candidate)
                    artistCounts[candidate.artist.lowercase()] = artistCounts.getOrDefault(candidate.artist.lowercase(), 0) + 1
                }
            }
        }

        return if (curatedResult.isNotEmpty()) curatedResult else eligibleCandidates.take(25)
    }
}
