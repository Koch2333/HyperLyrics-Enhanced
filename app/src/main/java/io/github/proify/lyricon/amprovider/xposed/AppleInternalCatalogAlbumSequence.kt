/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

private const val MAX_ALBUM_SEQUENCE_TRACKS = 300

internal fun AppleInternalCatalogResolver.warmOriginalAlbumSequenceFromCachedSong(mediaId: String, alias: Alias) {
    resolveCatalogIdentity(
        mediaId = mediaId,
        languages = listOf(alias.language),
    ) { identity ->
        val isrc = identity.isrc
        if (isrc == null) {
            warmOriginalAlbumSequence(
                anchorMediaId = mediaId,
                albumIds = identity.albumIds,
                language = alias.language,
                anchorAlias = alias,
            )
            return@resolveCatalogIdentity
        }
        queryByIsrc(isrc, alias.language) { regionalSong ->
            regionalSong?.let { rememberCatalogIdentity(mediaId, it) }
            warmOriginalAlbumSequence(
                anchorMediaId = mediaId,
                albumIds = (identity.albumIds + regionalSong?.albumIds.orEmpty()).distinct(),
                language = alias.language,
                anchorAlias = alias,
            )
        }
    }
}

internal fun AppleInternalCatalogResolver.warmOriginalAlbumSequence(
    anchorMediaId: String,
    albumIds: List<String>,
    language: String,
    anchorAlias: Alias,
) {
    val normalizedAlbumIds = albumIds.asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && it.all(Char::isDigit) }
        .distinct()
        .toList()
    val targetLanguage = canonicalOriginalLanguage(language)
    if (normalizedAlbumIds.isEmpty() || targetLanguage.isEmpty()) return
    val requestKey = "${normalizedAlbumIds.sorted().joinToString(",")}:$targetLanguage"
    if (!caches.beginOriginalAlbumSequenceWarm(requestKey)) return

    fun queryOriginalAlbum(
        accountAlbumId: String,
        accountTracks: List<CatalogSong>,
        originalIndex: Int,
    ) {
        if (originalIndex >= normalizedAlbumIds.size) {
            ProviderLogger.info(
                "Apple 原地区专辑序列替换跳过: accountAlbumId=$accountAlbumId, " +
                    "originalAlbumIds=$normalizedAlbumIds, language=$targetLanguage, " +
                    "account=${accountTracks.size}, anchor=$anchorMediaId"
            )
            caches.finishOriginalAlbumSequenceWarm(requestKey, completed = false)
            return
        }
        val originalAlbumId = normalizedAlbumIds[originalIndex]
        queryAlbumTracks(originalAlbumId, language = targetLanguage) { originalTracks ->
            val mappings = planOriginalAlbumSequence(
                accountTracks = accountTracks.map { it.toSequenceTrack() },
                originalTracks = originalTracks.map { it.toSequenceTrack() },
                anchorMediaId = anchorMediaId,
                anchorAlias = anchorAlias,
            )
            if (mappings == null) {
                queryOriginalAlbum(accountAlbumId, accountTracks, originalIndex + 1)
                return@queryAlbumTracks
            }

            mappings.forEach { mapping ->
                caches.putOriginalSongAlias(mapping.mediaId, mapping.alias)
                persistentOriginalCache.put(
                    originalSongCacheKey(mapping.mediaId),
                    mapping.alias,
                )
                persistentOriginalCache.put(
                    originalDirectEntityCacheKey(
                        LocalizedEntityType.SONG,
                        mapping.mediaId,
                    ),
                    mapping.alias,
                )
                MediaMetadataCache.updateOriginalMetadata(
                    mediaId = mapping.mediaId,
                    title = mapping.alias.title,
                    artist = mapping.alias.artist,
                    album = mapping.alias.album,
                    resolved = true,
                )
            }
            ProviderLogger.info(
                "Apple 原地区专辑序列替换已缓存: accountAlbumId=$accountAlbumId, " +
                    "originalAlbumId=$originalAlbumId, " +
                    "language=$targetLanguage, tracks=${mappings.size}, anchor=$anchorMediaId"
            )
            caches.finishOriginalAlbumSequenceWarm(requestKey, completed = true)
        }
    }

    fun queryAccountAlbum(accountIndex: Int) {
        if (accountIndex >= normalizedAlbumIds.size) {
            caches.finishOriginalAlbumSequenceWarm(requestKey, completed = false)
            return
        }
        val accountAlbumId = normalizedAlbumIds[accountIndex]
        queryAlbumTracks(
            albumId = accountAlbumId,
            language = targetLanguage,
            storefront = accountStorefront,
        ) { accountTracks ->
            if (accountTracks.any { it.id == anchorMediaId }) {
                queryOriginalAlbum(accountAlbumId, accountTracks, originalIndex = 0)
            } else {
                queryAccountAlbum(accountIndex + 1)
            }
        }
    }
    queryAccountAlbum(accountIndex = 0)
}

private fun AppleInternalCatalogResolver.queryAlbumTracks(
    albumId: String,
    language: String?,
    storefront: String? = language?.let(::storefrontForLanguage),
    onResult: (List<CatalogSong>) -> Unit,
) {
    val queryParams = linkedMapOf(
        "platform" to "android",
        "include[songs]" to "artists,albums",
        "limit" to MAX_ALBUM_SEQUENCE_TRACKS.toString(),
    )
    language?.let { queryParams["l"] = it }
    queryResponse(
        storefront = storefront,
        language = language,
        description = "album-sequence=$albumId",
        path = "albums/$albumId/tracks",
        queryParams = queryParams,
    ) { response ->
        val tracks = runCatching {
            response?.let {
                parseCatalogEntities(
                    response = it,
                    language = language ?: CURRENT_LANGUAGE,
                    entityType = LocalizedEntityType.SONG,
                )
            }.orEmpty().let(::completeCatalogAlbumTotals)
        }.onFailure { error ->
            ProviderLogger.error(
                "Apple 专辑轨道序列解析失败: albumId=$albumId, language=$language",
                error,
            )
        }.getOrDefault(emptyList())
        tracks.forEach { track ->
            ProviderLogger.debug(
                "Apple 专辑轨道位置: albumId=$albumId, id=${track.id}, " +
                    "分段/位置=${track.discNumber}, 分段/总数=${track.discCount}, " +
                    "轨道名=${track.alias.title}, 轨道名/位置=${track.trackNumber}, " +
                    "轨道名/总数=${track.trackCount}"
            )
        }
        if (tracks.any { it.trackNumber == null }) {
            ProviderLogger.info(
                "Apple 原地区专辑位置匹配不可用: albumId=$albumId, " +
                    "reason=track_position_missing"
            )
        }
        onResult(tracks)
    }
}

private fun completeCatalogAlbumTotals(tracks: List<CatalogSong>): List<CatalogSong> {
    if (tracks.isEmpty()) return tracks
    val discTotal = tracks.maxOf { it.discNumber ?: 1 }
    val trackTotal = tracks.size
    return tracks.map { track ->
        track.copy(
            discNumber = track.discNumber ?: 1,
            discCount = track.discCount ?: discTotal,
            trackCount = track.trackCount ?: trackTotal,
        )
    }
}

private fun CatalogSong.toSequenceTrack() = AlbumSequenceTrack(
    mediaId = id.orEmpty(),
    alias = alias,
    isrc = isrc,
    position = AlbumTrackPosition(
        discPosition = discNumber ?: 1,
        discTotal = discCount,
        trackPosition = trackNumber,
        trackTotal = trackCount,
    ),
)

internal data class AlbumSequenceTrack(
    val mediaId: String,
    val alias: Alias,
    val isrc: String?,
    val position: AlbumTrackPosition? = null,
)

internal data class AlbumTrackPosition(
    val discPosition: Int,
    val discTotal: Int?,
    val trackPosition: Int?,
    val trackTotal: Int?,
)

internal data class AlbumSequenceMapping(
    val mediaId: String,
    val alias: Alias,
)

internal fun planOriginalAlbumSequence(
    accountTracks: List<AlbumSequenceTrack>,
    originalTracks: List<AlbumSequenceTrack>,
    anchorMediaId: String,
    anchorAlias: Alias,
): List<AlbumSequenceMapping>? {
    if (
        accountTracks.size !in 2..MAX_ALBUM_SEQUENCE_TRACKS ||
        accountTracks.size != originalTracks.size ||
        accountTracks.any { it.mediaId.isBlank() } ||
        accountTracks.map(AlbumSequenceTrack::mediaId).distinct().size != accountTracks.size
    ) return null
    val accountByPosition = completeAlbumPositions(accountTracks) ?: return null
    val originalByPosition = completeAlbumPositions(originalTracks) ?: return null
    if (accountByPosition.keys != originalByPosition.keys) return null
    val anchorPosition = accountByPosition.entries
        .firstOrNull { it.value.mediaId == anchorMediaId }
        ?.key ?: return null
    val originalAnchor = originalByPosition[anchorPosition] ?: return null
    if (normalize(originalAnchor.alias.title) != normalize(anchorAlias.title)) return null
    if (
        anchorAlias.album.isNotBlank() &&
        originalAnchor.alias.album.isNotBlank() &&
        normalize(originalAnchor.alias.album) != normalize(anchorAlias.album)
    ) return null

    val pairedTracks = accountByPosition.keys.sortedWith(
        compareBy<AlbumPositionKey> { it.disc }.thenBy { it.track }
    ).map { key ->
        accountByPosition.getValue(key) to originalByPosition.getValue(key)
    }
    if (originalTracks.any { it.alias.title.isBlank() }) return null

    return pairedTracks.map { (account, original) ->
        AlbumSequenceMapping(
            mediaId = account.mediaId,
            alias = original.alias,
        )
    }
}

private fun completeAlbumPositions(
    tracks: List<AlbumSequenceTrack>,
): Map<AlbumPositionKey, AlbumSequenceTrack>? {
    val raw = tracks.map { track ->
        val position = track.position ?: return null
        val trackPosition = position.trackPosition?.takeIf { it > 0 } ?: return null
        if (position.discPosition <= 0) return null
        Triple(
            AlbumPositionKey(position.discPosition, trackPosition),
            position,
            track,
        )
    }
    if (raw.map { it.first }.distinct().size != tracks.size) return null
    val derivedDiscTotal = raw.maxOf { it.second.discPosition }
    val declaredDiscTotals = raw.mapNotNull { it.second.discTotal }.distinct()
    if (
        declaredDiscTotals.size > 1 ||
        declaredDiscTotals.singleOrNull()?.let { it != derivedDiscTotal } == true
    ) return null
    val declaredTrackTotals = raw.mapNotNull { it.second.trackTotal }.distinct()
    if (
        declaredTrackTotals.size > 1 ||
        declaredTrackTotals.singleOrNull()?.let { it != tracks.size } == true
    ) return null
    return raw.associate { (key, _, track) -> key to track }
}

private data class AlbumPositionKey(val disc: Int, val track: Int)
