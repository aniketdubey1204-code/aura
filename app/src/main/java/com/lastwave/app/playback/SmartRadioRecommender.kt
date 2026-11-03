package com.lastwave.app.playback

import com.lastwave.app.data.generate.TasteProfileProvider
import com.lastwave.app.data.repository.SongPlayStatsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Intelligent Up-Next radio recommender that preserves YouTube Music's native neural ordering.
 *
 * In YouTube Music, the Up Next queue is pre-sorted on the server by harmonic transition,
 * tempo continuity, and personalized acoustic embeddings. This curator maintains that exact
 * natural sequence while applying essential client-side hygiene:
 *
 * 1. Strict Deduplication: Excludes the seed track, active queue tracks, and recently played tracks.
 * 2. Spam & Junk Elimination: Filters out fan mashups, 1-hour loops, ringtones, and unofficial speed alterations.
 * 3. Personalized Skip Penalty: Filters out tracks that the user has repeatedly skipped (>= 3 skips).
 * 4. Pacing Protection: Prevents single-artist clustering by capping consecutive tracks from the same artist to 2.
 */
@Singleton
class SmartRadioRecommender @Inject constructor(
    private val tasteProfileProvider: dagger.Lazy<TasteProfileProvider>,
    private val songPlayStatsRepository: dagger.Lazy<SongPlayStatsRepository>,
) {

    private val junkTitleKeywords = setOf(
        "mashup", "mash up", "mash-up", "jukebox", "mega mix", "megamix",
        "non stop", "nonstop", "audio jukebox", "full album", "full songs", "compilation",
        "slowed + reverb", "slowed reverb", "slowed & reverb", "bass boosted", "8d audio",
        "karaoke", "ringtone", "1 hour", "10 hour", "clean version", "sped up",
    )

    private fun isJunkTitle(title: String): Boolean {
        val lower = title.lowercase()
        return junkTitleKeywords.any { lower.contains(it) }
    }

    /**
     * Curates incoming YouTube Music radio candidate tracks while preserving their natural order.
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

        val statsRepo = runCatching { songPlayStatsRepository.get() }.getOrNull()

        // 1. Base Deduplication & Hygiene Filter in exact incoming sequence
        val seenVideoIds = mutableSetOf<String>()
        val seenKeys = mutableSetOf<String>()
        val seenSongKeys = mutableSetOf<String>()

        val eligibleCandidates = incomingTracks.filter { track ->
            val vid = track.videoId?.takeIf(String::isNotBlank) ?: return@filter false
            if (vid == seedVideoId) return@filter false

            val key = PureRadioQueue.queueKey(track)
            val songKey = PureRadioQueue.normalizedSongKey(track.title, track.artist)
            if (songKey == seedSongKey) return@filter false

            if (isJunkTitle(track.title)) return@filter false

            // Exclude tracks the user consistently skips
            if (statsRepo != null) {
                val skips = runCatching { statsRepo.skipCountFor(track.title, track.artist) }.getOrDefault(0)
                if (skips >= 3) return@filter false
            }

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

        // 2. Artist Pacing Guard: preserve incoming order, but prevent >2 consecutive tracks from the same artist
        val curatedResult = mutableListOf<PlayableTrack>()
        val deferred = mutableListOf<PlayableTrack>()

        for (track in eligibleCandidates) {
            val trackArtist = track.artist.trim().lowercase()
            val lastOneArtist = curatedResult.getOrNull(curatedResult.size - 1)?.artist?.trim()?.lowercase()
            val lastTwoArtist = curatedResult.getOrNull(curatedResult.size - 2)?.artist?.trim()?.lowercase()

            val isConsecutiveOverflow = trackArtist.isNotBlank() &&
                trackArtist == lastOneArtist &&
                trackArtist == lastTwoArtist

            if (isConsecutiveOverflow) {
                deferred.add(track)
            } else {
                curatedResult.add(track)
                // If a deferred track can now be placed without overflow, place it
                if (deferred.isNotEmpty()) {
                    val deferredTrack = deferred.first()
                    val dArtist = deferredTrack.artist.trim().lowercase()
                    if (dArtist != trackArtist) {
                        curatedResult.add(deferred.removeAt(0))
                    }
                }
            }
        }

        // Append any remaining deferred tracks at the end
        if (deferred.isNotEmpty()) {
            curatedResult.addAll(deferred)
        }

        return curatedResult
    }
}
