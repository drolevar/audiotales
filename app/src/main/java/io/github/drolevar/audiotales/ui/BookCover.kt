package io.github.drolevar.audiotales.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import io.github.drolevar.audiotales.R

/** Dynamic provider artwork keeps its original aspect ratio in the caller's bounded slot. */
@Composable
internal fun BookCover(
    url: String?,
    modifier: Modifier = Modifier,
    referer: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    placeholderSize: Dp = 24.dp,
) {
    val context = LocalContext.current
    val request = remember(context, url, referer) {
        val headers = NetworkHeaders.Builder().set("User-Agent", "AudioTales").apply {
            referer?.let { set("Referer", it) }
        }.build()
        ImageRequest.Builder(context).data(url).httpHeaders(headers).build()
    }
    var artworkLoaded by remember(url, referer) { mutableStateOf(false) }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = if (artworkLoaded) Color.Transparent else containerColor,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                onLoading = { artworkLoaded = false },
                onSuccess = { artworkLoaded = true },
                onError = { artworkLoaded = false },
            )
            if (!artworkLoaded) {
                Icon(painterResource(R.drawable.ic_book), null, Modifier.size(placeholderSize))
            }
        }
    }
}
