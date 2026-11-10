package ai.ciris.mobile.shared.calls.media

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun VideoFrame.toImageBitmap(): ImageBitmap =
    Bitmap.createBitmap(toArgb(), width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
