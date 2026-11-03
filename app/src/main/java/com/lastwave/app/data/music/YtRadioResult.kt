package com.lastwave.app.data.music

/**
 * Filter chip for YouTube Music radio tuning (e.g., Familiar, Discover, Deep cuts, Chill, Upbeat).
 */
data class RadioFilterChip(
    val title: String,
    val params: String,
    val playlistId: String? = null,
    val isSelected: Boolean = false,
)

/**
 * Result of a YouTube Music native radio request (/next).
 *
 * @property tracks The parsed tracks in exact YouTube Music radio sequence.
 * @property continuationToken Token to fetch the subsequent page of radio tracks, if present.
 * @property automixPlaylistId Chained automix playlist ID (e.g. RDAMVM...) to follow when continuations end.
 * @property filterChips Dynamic radio tuning chips returned by YouTube Music for this station.
 */
data class YtRadioResult(
    val tracks: List<YouTubeMusicTrack>,
    val continuationToken: String? = null,
    val automixPlaylistId: String? = null,
    val filterChips: List<RadioFilterChip> = emptyList(),
)
