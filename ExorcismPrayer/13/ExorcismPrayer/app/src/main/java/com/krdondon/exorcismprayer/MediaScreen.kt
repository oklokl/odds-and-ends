package com.krdondon.exorcismprayer

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import java.io.ByteArrayOutputStream

private fun Context.findActivity(): Activity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) return currentContext
        currentContext = currentContext.baseContext
    }
    return null
}

private fun loadAppIconBytes(context: Context): ByteArray? {
    return try {
        val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher) ?: return null
        val bitmap = if (drawable is BitmapDrawable) {
            drawable.bitmap
        } else {
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 256
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 256
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp
        }
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        stream.toByteArray()
    } catch (_: Exception) {
        null
    }
}

@Composable
fun MediaScreen(
    mediaController: MediaController,
    currentMediaIndex: Int,
    onMediaIndexChange: (Int) -> Unit,
    isPlaying: Boolean,
    onIsPlayingChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val backgroundColor = Color(ContextCompat.getColor(context, R.color.background_color))

    val sharedPreferences = remember(context) {
        context.getSharedPreferences("exorcism_prayer_prefs", Context.MODE_PRIVATE)
    }
    var isKeepScreenOn by remember {
        mutableStateOf(sharedPreferences.getBoolean("key_keep_screen_on", false))
    }

    val activity = remember(context) { context.findActivity() }
    DisposableEffect(isKeepScreenOn, activity) {
        val window = activity?.window
        if (isKeepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val appName = context.getString(R.string.app_name)
    val artworkBytes = remember(context) { loadAppIconBytes(context) }
    val artworkUri = remember(context) {
        Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(context.packageName)
            .appendPath(R.mipmap.ic_launcher.toString())
            .build()
    }

    LaunchedEffect(mediaController) {
        if (mediaController.mediaItemCount == 0) {
            val items = mediaList.map { item ->
                val mediaUri = Uri.Builder()
                    .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                    .authority(context.packageName)
                    .appendPath(item.resourceId.toString())
                    .build()

                MediaItem.Builder()
                    .setMediaId(item.resourceId.toString())
                    .setUri(mediaUri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(item.title)
                            .setArtist(appName)
                            .setDisplayTitle(item.title)
                            .setSubtitle(appName)
                            .setDescription(item.description)
                            .setArtworkUri(artworkUri)
                            .apply {
                                if (artworkBytes != null) {
                                    setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                }
                            }
                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                            .setIsPlayable(true)
                            .build()
                    )
                    .build()
            }
            mediaController.setMediaItems(items)
            mediaController.prepare()
        }
    }

    val scrollState = rememberScrollState()
    LaunchedEffect(currentMediaIndex) {
        scrollState.scrollTo(0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(color = backgroundColor),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {

        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .weight(1f, fill = false),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp)
                    .background(Color.White, shape = RoundedCornerShape(12.dp))
                    .height(56.dp)
                    .wrapContentSize(Alignment.Center)
            ) {
                Text(
                    text = mediaList.getOrNull(currentMediaIndex)?.title ?: "",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.White, shape = RoundedCornerShape(12.dp))
                    .padding(16.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = mediaList.getOrNull(currentMediaIndex)?.description ?: "",
                    color = Color.Black,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Start
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 50.dp)
        ) {
            // "다음" 버튼 바로 위 빈 공간에 배치되는 "유지 / 해제" 버튼
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(16.dp))
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    onClick = {
                        val newState = !isKeepScreenOn
                        isKeepScreenOn = newState
                        sharedPreferences.edit {
                            putBoolean("key_keep_screen_on", newState)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                ) {
                    Text(
                        text = if (isKeepScreenOn) "해제" else "유지",
                        color = Color.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (currentMediaIndex > 0) {
                            onMediaIndexChange(currentMediaIndex - 1)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                ) { Text("이전", color = Color.Black) }

                Spacer(modifier = Modifier.width(16.dp))

                Button(
                    onClick = {
                        onIsPlayingChange(!isPlaying)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                ) { Text(if (isPlaying) "정지" else "재생", color = Color.Black) }

                Spacer(modifier = Modifier.width(16.dp))

                Button(
                    onClick = {
                        if (currentMediaIndex < mediaList.size - 1) {
                            onMediaIndexChange(currentMediaIndex + 1)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                ) { Text("다음", color = Color.Black) }
            }
        }
    }
}