package com.maxsync.pro

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arthenica.ffmpegkit.FFmpegKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

data class AudioTrackItem(
    val id: String,
    val uri: Uri,
    val name: String,
    val sizeMb: String,
    var lufs: Double? = null,
    var peak: Double? = null,
    var gainDb: Double = 0.0,
    var needsBoost: Boolean = false,
    var isReference: Boolean = false,
    var status: String = "대기중",
    var outputPath: String? = null
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF090D16),
                    surface = Color(0xFF111827),
                    primary = Color(0xFFF59E0B),
                    secondary = Color(0xFF6366F1)
                )
            ) {
                MaxSyncProStudioApp()
            }
        }
    }
}

@Composable
fun MaxSyncProStudioApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tracks by remember { mutableStateOf(listOf<AudioTrackItem>()) }
    var thresholdLu by remember { mutableFloatStateOf(1.0f) }
    var selectedBitrate by remember { mutableStateOf("320k") }
    var useLimiter by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("EBU R128 정식 엔진 준비됨") }
    var targetLufs by remember { mutableStateOf<Double?>(null) }

    // 최고 음량 곡 기준으로 증폭 대상 재계산
    fun recalculateTargets(list: List<AudioTrackItem>, threshold: Float): List<AudioTrackItem> {
        val measured = list.filter { it.lufs != null }
        if (measured.isEmpty()) {
            targetLufs = null
            return list
        }
        val maxTrack = measured.maxByOrNull { it.lufs!! }!!
        val maxVal = maxTrack.lufs!!
        targetLufs = maxVal

        return list.map { item ->
            if (item.lufs == null) {
                item
            } else {
                val diff = maxVal - item.lufs!!
                val isRef = (item.id == maxTrack.id)
                val need = !isRef && diff >= threshold
                item.copy(
                    isReference = isRef,
                    gainDb = if (need) diff else 0.0,
                    needsBoost = need,
                    status = when {
                        item.status == "증폭완료" -> "증폭완료"
                        isRef -> "최고음량 기준곡"
                        need -> "증폭 필요"
                        else -> "통과 (Skip)"
                    }
                )
            }
        }
    }

    // 안드로이드 다중 오디오 파일 선택기
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val newItems = uris.mapIndexed { idx, uri ->
                val meta = getFileMeta(context, uri)
                AudioTrackItem(
                    id = "${System.currentTimeMillis()}_$idx",
                    uri = uri,
                    name = meta.first,
                    sizeMb = meta.second
                )
            }
            tracks = recalculateTargets(tracks + newItems, thresholdLu)
            progressText = "${tracks.size}곡이 목록에 등록되었습니다."
        }
    }

    val boostCount = tracks.count { it.needsBoost }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090D16))
            .padding(14.dp)
    ) {
        // 1. 상단 스튜디오 헤더
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF111827))
                .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFFF59E0B), Color(0xFFEC4899), Color(0xFF6366F1))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("M", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "MaxSync Pro",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "EBU R128 마스터 워크스페이스",
                        color = Color(0xFF9CA3AF),
                        fontSize = 11.sp
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFF064E3B))
                    .border(1.dp, Color(0xFF10B981), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isProcessing) "작업 실행중..." else "엔진 준비됨",
                    color = Color(0xFF6EE7B7),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 2. 파일 추가 & 전체 비우기 버튼
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { filePickerLauncher.launch(arrayOf("audio/*")) },
                enabled = !isProcessing,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
            ) {
                Text("+ MP3 파일 선택 추가", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            OutlinedButton(
                onClick = {
                    tracks = emptyList()
                    targetLufs = null
                    progressText = "목록이 초기화되었습니다."
                },
                enabled = !isProcessing && tracks.isNotEmpty(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("전체 비우기", color = Color(0xFFF87171), fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 3. 1단계(음량 측정) & 2단계(일괄 증폭) 실행 버튼
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    scope.launch {
                        isProcessing = true
                        val updated = tracks.toMutableList()
                        for (i in updated.indices) {
                            progressText = "[1단계 측정중] ${i + 1}/${updated.size}: ${updated[i].name}"
                            updated[i] = updated[i].copy(status = "측정중...")
                            tracks = updated.toList()

                            val result = measureLufsWithFFmpeg(context, updated[i].uri)
                            updated[i] = updated[i].copy(
                                lufs = result.first,
                                peak = result.second
                            )
                            tracks = recalculateTargets(updated.toList(), thresholdLu)
                        }
                        progressText = "EBU R128 측정 완료! 최고 음량 기준곡이 지정되었습니다."
                        isProcessing = false
                    }
                },
                enabled = !isProcessing && tracks.isNotEmpty(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4F46E5),
                    disabledContainerColor = Color(0xFF1F2937)
                )
            ) {
                Text("1단계: LUFS 음량 측정", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            Button(
                onClick = {
                    scope.launch {
                        isProcessing = true
                        val updated = tracks.toMutableList()
                        var doneCount = 0
                        for (i in updated.indices) {
                            val item = updated[i]
                            if (item.needsBoost && item.gainDb > 0) {
                                progressText = "[2단계 증폭중] ${item.name} (+${String.format(Locale.US, "%.1f", item.gainDb)} dB)"
                                val outPath = boostTrackWithFFmpeg(
                                    context = context,
                                    uri = item.uri,
                                    fileName = item.name,
                                    gainDb = item.gainDb,
                                    bitrate = selectedBitrate,
                                    limiter = useLimiter
                                )
                                if (outPath != null) {
                                    doneCount++
                                    updated[i] = item.copy(
                                        lufs = targetLufs,
                                        gainDb = 0.0,
                                        needsBoost = false,
                                        status = "증폭완료",
                                        outputPath = outPath
                                    )
                                } else {
                                    updated[i] = item.copy(status = "오류")
                                }
                                tracks = updated.toList()
                            }
                        }
                        progressText = "일괄 증폭 완료! ($doneCount 곡 저장됨)"
                        isProcessing = false
                    }
                },
                enabled = !isProcessing && boostCount > 0,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD97706),
                    disabledContainerColor = Color(0xFF1F2937)
                )
            ) {
                Text("2단계: 일괄 증폭 ($boostCount 곡)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 4. 최고 음량 기준 대시보드 & 옵션 카드
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF111827))
                .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("최고 음량 기준 (Target LUFS)", color = Color(0xFFFBBF24), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (targetLufs != null) String.format(Locale.US, "%.1f LUFS", targetLufs) else "--.- LUFS",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("증폭 필요 / 전체", color = Color(0xFF9CA3AF), fontSize = 11.sp)
                    Text(
                        text = "${boostCount}곡 / ${tracks.size}곡",
                        color = Color(0xFF34D399),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "판정 임계값: ${String.format(Locale.US, "%.1f", thresholdLu)} LU 이상 차이날 때만 증폭",
                color = Color(0xFFD1D5DB),
                fontSize = 12.sp
            )
            Slider(
                value = thresholdLu,
                onValueChange = {
                    thresholdLu = it
                    tracks = recalculateTargets(tracks, thresholdLu)
                },
                valueRange = 0.2f..5.0f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFF59E0B),
                    activeTrackColor = Color(0xFFF59E0B)
                )
            )

            // 출력 비트레이트 & 클리핑 방지 스위치
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("320k", "256k", "192k").forEach { br ->
                        val active = (selectedBitrate == br)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) Color(0xFF374151) else Color(0xFF1F2937))
                                .border(
                                    1.dp,
                                    if (active) Color(0xFFF59E0B) else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { selectedBitrate = br }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = br,
                                color = if (active) Color(0xFFFBBF24) else Color(0xFF9CA3AF),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("클리핑 방지", color = Color(0xFFD1D5DB), fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Switch(
                        checked = useLimiter,
                        onCheckedChange = { useLimiter = it }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 실시간 상태 메시지 표시줄
        Text(
            text = progressText,
            color = Color(0xFF93C5FD),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )

        Spacer(modifier = Modifier.height(6.dp))

        // 5. 오디오 곡 목록 (Track Queue) 영역
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF111827))
                .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(16.dp))
                .padding(10.dp)
        ) {
            if (tracks.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("오디오 곡 목록 (Track Queue)이 비어 있습니다.", color = Color(0xFF9CA3AF), fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("상단의 [+ MP3 파일 선택 추가] 버튼을 눌러 곡을 불러오세요.", color = Color(0xFF6B7280), fontSize = 11.sp)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(tracks) { index, item ->
                        TrackRowCard(index = index + 1, item = item)
                    }
                }
            }
        }
    }
}

@Composable
fun TrackRowCard(index: Int, item: AudioTrackItem) {
    val borderColor = when {
        item.isReference -> Color(0xFFF59E0B)
        item.needsBoost -> Color(0xFF6366F1)
        item.status == "증폭완료" -> Color(0xFF10B981)
        else -> Color(0xFF1F2937)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1F2937).copy(alpha = 0.6f))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "#$index",
                    color = Color(0xFF9CA3AF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = item.name,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val badgeBg = when {
                item.isReference -> Color(0xFF78350F)
                item.needsBoost -> Color(0xFF312E81)
                item.status == "증폭완료" -> Color(0xFF064E3B)
                else -> Color(0xFF374151)
            }
            val badgeText = when {
                item.isReference -> "👑 최고음량 기준곡"
                item.needsBoost -> "⚡ +${String.format(Locale.US, "%.1f", item.gainDb)} dB 증폭"
                else -> item.status
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(badgeBg)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(text = badgeText, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "용량: ${item.sizeMb} MB",
                color = Color(0xFF9CA3AF),
                fontSize = 11.sp
            )
            Text(
                text = if (item.lufs != null) {
                    String.format(Locale.US, "통합 음량: %.1f LUFS | 피크: %.1f dBTP", item.lufs, item.peak ?: -1.0)
                } else {
                    "측정 전 (--.- LUFS)"
                },
                color = if (item.lufs != null) Color(0xFFFCD34D) else Color(0xFF6B7280),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

fun getFileMeta(context: Context, uri: Uri): Pair<String, String> {
    var name = "audio_track.mp3"
    var sizeStr = "0.0"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (cursor.moveToFirst()) {
            if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
            if (sizeIdx >= 0) {
                val bytes = cursor.getLong(sizeIdx)
                sizeStr = String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0))
            }
        }
    }
    return Pair(name, sizeStr)
}

suspend fun measureLufsWithFFmpeg(context: Context, uri: Uri): Pair<Double, Double> = withContext(Dispatchers.IO) {
    val tempFile = copyUriToTempFile(context, uri, "measure_temp.mp3") ?: return@withContext Pair(-16.0, -1.0)
    val cmd = "-i \"${tempFile.absolutePath}\" -filter_complex ebur128=peak=true -f null -"
    val session = FFmpegKit.execute(cmd)
    val logs = session.allLogsAsString ?: ""
    tempFile.delete()

    val iRegex = Regex("""I:\s*(-?\d+(\.\d+)?)\s*LUFS""")
    val tpRegex = Regex("""Peak:\s*(-?\d+(\.\d+)?)\s*dBFS""")

    val lufs = iRegex.findAll(logs).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull() ?: -18.0
    val peak = tpRegex.findAll(logs).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull() ?: -1.5
    Pair(lufs, peak)
}

suspend fun boostTrackWithFFmpeg(
    context: Context,
    uri: Uri,
    fileName: String,
    gainDb: Double,
    bitrate: String,
    limiter: Boolean
): String? = withContext(Dispatchers.IO) {
    val tempIn = copyUriToTempFile(context, uri, "boost_in.mp3") ?: return@withContext null
    val outDir = File(context.getExternalFilesDir(null), "MaxSync_Output")
    if (!outDir.exists()) outDir.mkdirs()

    val outFile = File(outDir, "MaxSync_$fileName")
    val filter = if (limiter) {
        "volume=${String.format(Locale.US, "%.2f", gainDb)}dB,alimiter=limit=-1.0dB:level=false"
    } else {
        "volume=${String.format(Locale.US, "%.2f", gainDb)}dB"
    }

    val cmd = "-y -i \"${tempIn.absolutePath}\" -af \"$filter\" -c:a libmp3lame -b:a $bitrate \"${outFile.absolutePath}\""
    val session = FFmpegKit.execute(cmd)
    tempIn.delete()

    if (session.returnCode.isValueSuccess && outFile.exists()) {
        outFile.absolutePath
    } else {
        null
    }
}

fun copyUriToTempFile(context: Context, uri: Uri, tempName: String): File? {
    return try {
        val tempFile = File(context.cacheDir, tempName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        tempFile
    } catch (e: Exception) {
        null
    }
}
