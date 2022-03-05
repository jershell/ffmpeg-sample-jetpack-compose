import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skiko.toImage
import org.tmp.FFmpegFrameGrabber
import org.tmp.Frame
import java.awt.image.BufferedImage
import java.awt.image.ColorModel
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import java.util.*
import java.util.concurrent.Executors


@Composable
@Preview
fun App(filename: String) {
    val isPlaying = remember { mutableStateOf(false) }

    val stump = remember {
        useResource("stump.png") { loadImageBitmap(it) }
    }

    val currentBitmap = remember {
        mutableStateOf(stump)
    }

    LaunchedEffect(isPlaying.value) {
        if(isPlaying.value) {
            grapTo(filename, 1000, currentBitmap)
        } else {
            currentBitmap.value = stump
        }
    }

    MaterialTheme {
        Column {
            Row {
                Button(onClick = {
                    isPlaying.value = !isPlaying.value
                }) {
                    Text("play video")
                }
                Spacer(Modifier.width(24.dp))
                Text("video_file: $filename")
            }

            Divider()

            Box(modifier = Modifier.fillMaxSize().border(2.dp, androidx.compose.ui.graphics.Color.Black)) {
                Image(currentBitmap.value, contentDescription = "blablabla", modifier = Modifier.fillMaxSize())
            }
        }
    }
}

suspend fun grapTo(filename: String, count: Int = 1000, currentBitmap: MutableState<ImageBitmap>) {
    val audioDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val videoDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val istream = File(filename).inputStream()
    val grabber = FFmpegFrameGrabber(istream)
    grabber.start()

    var j = 0
    while (j < count) {
        val frame = grabber.grab() ?: break
        if (frame.image != null) { // video
            j++
            // GRAB ONLY VIDEO
            val imageFrame = frame.clone()
            withContext(videoDispatcher) {
                val image = convertImage(imageFrame)
                imageFrame.close()
                currentBitmap.value = image.toComposeImageBitmap()
            }
        } else if (frame.samples != null) { // audio
            val channelSamplesShortBuffer = frame.samples[0] as ShortBuffer
            channelSamplesShortBuffer.rewind()
            val outBuffer = ByteBuffer.allocate(channelSamplesShortBuffer.capacity() * 2)
            for (i in 0 until channelSamplesShortBuffer.capacity()) {
                val `val` = channelSamplesShortBuffer[i]
                outBuffer.putShort(`val`)
            }
            withContext(audioDispatcher) {
                // soundLine.write(outBuffer.array(), 0, outBuffer.capacity())
                outBuffer.clear()
            }
        }
    }

    // TODO mem leak in launch effect
    grabber.close()
    grabber.release()
}

fun main() = application {
//    sample: A save to disk
//    val scope = rememberCoroutineScope()
//    scope.launch {
//        grabToFile("./big_buck_bunny_480p_stereo.ogg", count = 1000)
//    }

    Window(onCloseRequest = ::exitApplication) {
        App("./big_buck_bunny_480p_stereo.ogg")
    }
}

fun getPixels3(
    frame: Frame,
    start_x: Int,
    start_y: Int,
    w: Int,
    h: Int
): BufferedImage {
    val cm = ColorModel.getRGBdefault()
    val raster = cm.createCompatibleWritableRaster(w, h)
    val bb = mutableListOf<Int>()

    println("getPixels3()")
    val fss: Int = frame.imageStride
    if (frame.imageChannels != 3) {
        throw UnsupportedOperationException("We only support frames with imageChannels = 3 (BGR)")
    }
    val b = frame.image.get(0) as ByteBuffer
    for (y in start_y until start_y + h) {
        for (x in start_x until start_x + w) {
            val base = 3 * x
            bb.add(b[fss * y + base].toInt())
            bb.add(b[fss * y + base + 1].toInt())
            bb.add(b[fss * y + base + 2].toInt())
            bb.add(255)
        }
    }
    raster.setPixels(start_x, start_y, w, h, bb.toIntArray())
    return BufferedImage(cm, raster, false, null)
}
fun convertImage(f: Frame): Image {
    println("convertImage()")
    val bufimg = getPixels3(f, 0, 0, f.imageWidth, f.imageHeight)
    return bufimg.toImage()
}

fun writeToFile(filename: String, img: Image) {
    img.encodeToData(EncodedImageFormat.PNG, 90)?.bytes?.let { ba ->
        FileOutputStream(filename).use { outputStream -> outputStream.write(ba) }
    }
}

/**
 * save $count frames to a file
 */
suspend fun grabToFile(s: String, count: Int) {
    val audioDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val videoDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val istream = File(s).inputStream()
    val grabber = FFmpegFrameGrabber(istream)
    grabber.start()

    var j = 0
    while (j < count) {
        val frame = grabber.grab() ?: break
        if (frame.image != null) { // video
            j++
            // GRAB ONLY VIDEO
            val imageFrame = frame.clone()
            withContext(videoDispatcher) {
                val image = convertImage(imageFrame)
                imageFrame.close()
                writeToFile(filename = "./image_${Date().time}", image)
            }
        } else if (frame.samples != null) { // audio
            val channelSamplesShortBuffer = frame.samples[0] as ShortBuffer
            channelSamplesShortBuffer.rewind()
            val outBuffer = ByteBuffer.allocate(channelSamplesShortBuffer.capacity() * 2)
            for (i in 0 until channelSamplesShortBuffer.capacity()) {
                val `val` = channelSamplesShortBuffer[i]
                outBuffer.putShort(`val`)
            }
            withContext(audioDispatcher) {
                // soundLine.write(outBuffer.array(), 0, outBuffer.capacity())
                outBuffer.clear()
            }
        }
    }

    grabber.close()
    grabber.release()
}

