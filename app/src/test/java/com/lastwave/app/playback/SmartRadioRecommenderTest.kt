package com.lastwave.app.playback

import com.lastwave.app.data.repository.SongPlayStatsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SmartRadioRecommenderTest {

    private lateinit var recommender: SmartRadioRecommender
    private lateinit var statsRepository: SongPlayStatsRepository

    private fun track(
        id: String,
        title: String = "Track $id",
        artist: String = "Artist $id",
    ): PlayableTrack = PlayableTrack(
        title = title,
        artist = artist,
        videoId = id,
    )

    @Before
    fun setUp() {
        statsRepository = mockk(relaxed = true)
        recommender = SmartRadioRecommender(
            tasteProfileProvider = dagger.Lazy { mockk(relaxed = true) },
            songPlayStatsRepository = dagger.Lazy { statsRepository },
        )
    }

    @Test
    fun curateUpNextQueue_preservesExactYtmOrder() = runTest {
        val seed = track("seed_1", "Seed Song", "Seed Artist")
        val ytmBatch = listOf(
            track("ytm_1", "Acoustic Morning", "Artist A"),
            track("ytm_2", "Dancing Slowly", "Artist B"),
            track("ytm_3", "Midnight Calm", "Artist C"),
            track("ytm_4", "Golden Hour", "Artist D"),
        )

        val curated = recommender.curateUpNextQueue(
            seedTrack = seed,
            incomingTracks = ytmBatch,
            existingVideoIds = emptySet(),
            existingKeys = emptySet(),
        )

        assertEquals(listOf("ytm_1", "ytm_2", "ytm_3", "ytm_4"), curated.map { it.videoId })
    }

    @Test
    fun curateUpNextQueue_deduplicatesSeedQueueAndHistory() = runTest {
        val seed = track("seed_1", "Seed Title", "Seed Artist")
        val ytmBatch = listOf(
            track("seed_1", "Seed Title", "Seed Artist"), // Seed duplicate
            track("existing_1", "Queue Song", "Artist Q"), // Active queue duplicate
            track("history_1", "Past Song", "Artist H"), // History duplicate
            track("fresh_1", "Fresh Song 1", "Artist 1"),
            track("fresh_2", "Fresh Song 2", "Artist 2"),
            track("fresh_1", "Fresh Song 1", "Artist 1"), // Intra-batch duplicate
        )

        val curated = recommender.curateUpNextQueue(
            seedTrack = seed,
            incomingTracks = ytmBatch,
            existingVideoIds = setOf("existing_1"),
            existingKeys = setOf("existing_1"),
            recentVideoIds = setOf("history_1"),
            recentKeys = setOf("history_1"),
        )

        assertEquals(listOf("fresh_1", "fresh_2"), curated.map { it.videoId })
    }

    @Test
    fun curateUpNextQueue_filtersJunkUploads() = runTest {
        val seed = track("seed_1", "Seed", "Artist")
        val ytmBatch = listOf(
            track("good_1", "Normal Song", "Artist A"),
            track("junk_1", "Best Hits Mega Mix Non Stop", "DJ Junk"),
            track("junk_2", "Song Name 10 Hour Loop", "Loop Maker"),
            track("junk_3", "Popular Hit Slowed + Reverb", "Reverb Fan"),
            track("junk_4", "Phone Ringtone Official", "Tone Maker"),
            track("good_2", "Another Good Song", "Artist B"),
        )

        val curated = recommender.curateUpNextQueue(
            seedTrack = seed,
            incomingTracks = ytmBatch,
            existingVideoIds = emptySet(),
            existingKeys = emptySet(),
        )

        assertEquals(listOf("good_1", "good_2"), curated.map { it.videoId })
    }

    @Test
    fun curateUpNextQueue_filtersHighSkipTracks() = runTest {
        val seed = track("seed_1", "Seed", "Artist")
        val skippedTrack = track("skip_me", "Skipped Song", "Annoying Artist")
        val goodTrack = track("keep_me", "Loved Song", "Good Artist")

        coEvery { statsRepository.skipCountFor("Skipped Song", "Annoying Artist") } returns 4
        coEvery { statsRepository.skipCountFor("Loved Song", "Good Artist") } returns 0

        val curated = recommender.curateUpNextQueue(
            seedTrack = seed,
            incomingTracks = listOf(skippedTrack, goodTrack),
            existingVideoIds = emptySet(),
            existingKeys = emptySet(),
        )

        assertEquals(listOf("keep_me"), curated.map { it.videoId })
    }

    @Test
    fun curateUpNextQueue_pacesSameArtistConsecutiveTracks() = runTest {
        val seed = track("seed_1", "Seed", "Artist")
        val ytmBatch = listOf(
            track("t1", "Song 1", "Artist X"),
            track("t2", "Song 2", "Artist X"),
            track("t3", "Song 3", "Artist X"), // 3rd consecutive from Artist X
            track("t4", "Song 4", "Artist Y"),
            track("t5", "Song 5", "Artist Z"),
        )

        val curated = recommender.curateUpNextQueue(
            seedTrack = seed,
            incomingTracks = ytmBatch,
            existingVideoIds = emptySet(),
            existingKeys = emptySet(),
        )

        // t1 and t2 stay in place, t3 is deferred until after t4
        assertEquals(listOf("t1", "t2", "t4", "t3", "t5"), curated.map { it.videoId })
    }
}
