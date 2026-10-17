package com.maxsyncpro.mobile

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
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
import java.io.FileInputStream
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
    var isDone: Boolean = false,
    // 폴더로 추가한 경우, 출력 파일을 저장할 SAF 트리 URI (같은 폴더의 output 하위폴더에 저장)
    val outputTreeUri: Uri? = null
)

// LUFS 측정 결과 (error가 null이 아니면 측정 실패)
data class LufsResult(val lufs: Double, val peak: Double, val error: String? = null)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContent {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        background = Color(0xFF090D16),
                        surface = Color(0xFF111827),
                        primary = Color(0xFFF59E0B),
                        secondary = Color(0xFF6366F1)
                    )
                ) {
                    MaxSyncProAppView()
                }
            }
        } catch (e: Throwable) {
            Toast.makeText(this, "실행 초기화 오류: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun MaxSyncProAppView() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tracks by remember { mutableStateOf(listOf<AudioTrackItem>()) }
    var thresholdLu by remember { mutableFloatStateOf(1.0f) }
    var selectedBitrate by remember { mutableStateOf("320k") }
    var isProcessing by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("EBU R128 오디오 마스터 엔진 준비됨") }
    var targetLufs by remember { mutableStateOf<Double?>(null) }

    fun recalcTargets(list: List<AudioTrackItem>, threshold: Float): List<AudioTrackItem> {
        val measured = list.filter { it.lufs != null }
        if (measured.isEmpty()) {
            targetLufs = null
            return list
        }
        val maxVal = measured.maxOf { it.lufs!! }
        val maxTrack = measured.first { it.lufs == maxVal }
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
                        item.isDone -> "증폭완료"
                        isRef -> "최고음량 기준곡"
                        need -> "증폭 필요"
                        else -> "통과 (Skip)"
                    }
                )
            }
        }
    }

    fun addItems(newItems: List<AudioTrackItem>) {
        val existing = tracks.map { it.uri.toString() }.toSet()
        val filtered = newItems.filter { it.uri.toString() !in existing }
        if (filtered.isEmpty()) {
            progressText = "추가된 곡이 없습니다 (이미 목록에 있거나 오디오 파일 아님)"
            return
        }
        tracks = recalcTargets(tracks + filtered, thresholdLu)
        progressText = "${tracks.size}곡 목록 등록 완료"
    }

    val filePicker = rememberLauncherForActivityResult(
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
            addItems(newItems)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            progressText = "폴더 스캔 중..."
            val found = scanFolderForAudio(context, treeUri)
            if (found.isEmpty()) {
                progressText = "선택한 폴더에 오디오 파일이 없습니다."
            } else {
                addItems(found)
            }
        }
    }

    val boostCount = tracks.count { it.needsBoost }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090D16))
            .padding(14.dp)
    ) {
        // 헤더
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
                    Text("MaxSync Pro", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text("EBU R128 오디오 마스터", color = Color(0xFF9CA3AF), fontSize = 11.sp)
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
                    text = if (isProcessing) "처리 중..." else "정상 구동됨",
                    color = Color(0xFF6EE7B7),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Text(
            "기기 ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")} · Android ${Build.VERSION.SDK_INT}",
            color = Color(0xFF6B7280),
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 추가 & 초기화
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = { filePicker.launch(arrayOf("audio/*")) },
                enabled = !isProcessing,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
            ) {
                Text("+ 파일 추가", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            Button(
                onClick = { folderPicker.launch(null) },
                enabled = !isProcessing,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0891B2))
            ) {
                Text("+ 폴더 추가", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = {
                    tracks = emptyList()
                    targetLufs = null
                    progressText = "목록이 초기화되었습니다."
                },
                enabled = !isProcessing && tracks.isNotEmpty(),
                modifier = Modifier.weight(0.7f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("비우기", color = Color(0xFFF87171), fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 1단계 / 2단계 액션 버튼
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    scope.launch {
                        isProcessing = true
                        val copyList = tracks.toMutableList()
                        var errCount = 0
                        var firstErr: String? = null
                        for (i in copyList.indices) {
                            progressText = "음량 측정 중 (${i + 1}/${copyList.size}): ${copyList[i].name}"
                            copyList[i] = copyList[i].copy(status = "측정중...")
                            tracks = copyList.toList()

                            val result = measureLufs(context, copyList[i].uri)
                            if (result.error != null) {
                                errCount++
                                if (firstErr == null) firstErr = result.error
                                copyList[i] = copyList[i].copy(status = "오류")
                            } else {
                                copyList[i] = copyList[i].copy(lufs = result.lufs, peak = result.peak)
                            }
                            tracks = recalcTargets(copyList.toList(), thresholdLu)
                        }
                        progressText = if (errCount > 0) {
                            "측정 실패 ${errCount}곡 · 오류: $firstErr"
                        } else {
                            "측정 완료! 최고 음량에 맞춰 대상이 지정되었습니다."
                        }
                        isProcessing = false
                    }
                },
                enabled = !isProcessing && tracks.isNotEmpty(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5), disabledContainerColor = Color(0xFF1F2937))
            ) {
                Text("1단계: LUFS 측정", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            Button(
                onClick = {
                    scope.launch {
                        isProcessing = true
                        val copyList = tracks.toMutableList()
                        var done = 0
                        for (i in copyList.indices) {
                            val itm = copyList[i]
                            if (itm.needsBoost && itm.gainDb > 0) {
                                progressText = "증폭 인코딩 중: ${itm.name} (+${String.format(Locale.US, "%.1f", itm.gainDb)}dB)"
                                val err = boostAndSaveTrack(context, itm.uri, itm.name, itm.gainDb, selectedBitrate, itm.outputTreeUri)
                                if (err == null) {
                                    done++
                                    copyList[i] = itm.copy(
                                        lufs = targetLufs,
                                        gainDb = 0.0,
                                        needsBoost = false,
                                        status = "증폭완료",
                                        isDone = true
                                    )
                                } else {
                                    progressText = "증폭 오류: $err"
                                    copyList[i] = itm.copy(status = "오류")
                                }
                                tracks = copyList.toList()
                            }
                        }
                        progressText = "일괄 증폭 완료! (${done}곡 내장메모리 Music/MaxSync_Output 저장됨)"
                        isProcessing = false
                    }
                },
                enabled = !isProcessing && boostCount > 0,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706), disabledContainerColor = Color(0xFF1F2937))
            ) {
                Text("2단계: 일괄 증폭 ($boostCount)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 옵션 및 타겟 카드
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
                    Text("최고 음량 기준 (Target LUFS)", color = Color(0xFFFBBF24), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (targetLufs != null) String.format(Locale.US, "%.1f LUFS", targetLufs) else "--.- LUFS",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("증폭 대상 / 전체", color = Color(0xFF9CA3AF), fontSize = 11.sp)
                    Text("${boostCount}곡 / ${tracks.size}곡", color = Color(0xFF34D399), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text("판정 임계값: ${String.format(Locale.US, "%.1f", thresholdLu)} LU 이상 차이날 때만 증폭", color = Color(0xFFD1D5DB), fontSize = 12.sp)
            Slider(
                value = thresholdLu,
                onValueChange = {
                    thresholdLu = it
                    tracks = recalcTargets(tracks, thresholdLu)
                },
                valueRange = 0.2f..4.0f,
                colors = SliderDefaults.colors(thumbColor = Color(0xFFF59E0B), activeTrackColor = Color(0xFFF59E0B))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("출력 비트레이트", color = Color(0xFF9CA3AF), fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("320k", "256k", "192k").forEach { br ->
                        val active = (selectedBitrate == br)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) Color(0xFF374151) else Color(0xFF1F2937))
                                .border(1.dp, if (active) Color(0xFFF59E0B) else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { selectedBitrate = br }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(br, color = if (active) Color(0xFFFBBF24) else Color(0xFF9CA3AF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(progressText, color = Color(0xFF93C5FD), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))

        Spacer(modifier = Modifier.height(6.dp))

        // 곡 리스트
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
                    Text("등록된 곡이 없습니다.", color = Color(0xFF9CA3AF), fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("+ 오디오 파일 추가 버튼을 눌러주세요.", color = Color(0xFF6B7280), fontSize = 11.sp)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(tracks) { idx, itm ->
                        val bColor = when {
                            itm.isReference -> Color(0xFFF59E0B)
                            itm.needsBoost -> Color(0xFF6366F1)
                            itm.isDone -> Color(0xFF10B981)
                            else -> Color(0xFF1F2937)
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF1F2937).copy(alpha = 0.6f))
                                .border(1.dp, bColor, RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Text("#${idx + 1}", color = Color(0xFF9CA3AF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(itm.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                val bg = when {
                                    itm.isReference -> Color(0xFF78350F)
                                    itm.needsBoost -> Color(0xFF312E81)
                                    itm.isDone -> Color(0xFF064E3B)
                                    else -> Color(0xFF374151)
                                }
                                val txt = when {
                                    itm.isReference -> "👑 기준곡"
                                    itm.needsBoost -> "+${String.format(Locale.US, "%.1f", itm.gainDb)}dB"
                                    else -> itm.status
                                }
                                Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp)) {
                                    Text(txt, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("크기: ${itm.sizeMb} MB", color = Color(0xFF9CA3AF), fontSize = 11.sp)
                                Text(
                                    if (itm.lufs != null) String.format(Locale.US, "%.1f LUFS", itm.lufs) else "미측정",
                                    color = if (itm.lufs != null) Color(0xFFFCD34D) else Color(0xFF6B7280),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// 예외의 원인 체인을 사람이 읽을 수 있는 문자열로 변환
fun describeError(t: Throwable): String {
    val sb = StringBuilder()
    var cur: Throwable? = t
    var depth = 0
    while (cur != null && depth < 4) {
        if (depth > 0) sb.append(" | ")
        sb.append(cur.javaClass.simpleName).append(": ").append(cur.message)
        cur = cur.cause
        depth++
    }
    return sb.toString()
}

fun getFileMeta(context: Context, uri: Uri): Pair<String, String> {
    var name = "track_${System.currentTimeMillis()}.mp3"
    var sizeStr = "0.0"
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nIdx >= 0) name = cursor.getString(nIdx) ?: name
                if (sIdx >= 0) {
                    val bytes = cursor.getLong(sIdx)
                    sizeStr = String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0))
                }
            }
        }
    } catch (_: Exception) {}
    return Pair(name, sizeStr)
}

suspend fun measureLufs(context: Context, uri: Uri): LufsResult = withContext(Dispatchers.IO) {
    val temp = File(context.cacheDir, "meas_${System.currentTimeMillis()}.mp3")
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).use { output -> input.copyTo(output) }
        }

        val cmd = "-i \"${temp.absolutePath}\" -af ebur128=peak=true -f null -"
        val session = FFmpegKit.execute(cmd)
        val logs = session.allLogsAsString ?: ""

        val iRegex = Regex("""I:\s*(-?\d+(\.\d+)?)\s*LUFS""")
        val tpRegex = Regex("""Peak:\s*(-?\d+(\.\d+)?)\s*dBFS""")

        val lufs = iRegex.findAll(logs).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull()
        val peak = tpRegex.findAll(logs).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull()

        if (lufs == null) {
            val tail = logs.trim().lines().takeLast(3).joinToString(" / ")
            LufsResult(-16.0, peak ?: -1.0, if (tail.isBlank()) "LUFS 값을 읽지 못했습니다 (code=${session.returnCode})" else tail)
        } else {
            LufsResult(lufs, peak ?: -1.0)
        }
    } catch (t: Throwable) {
        // FFmpegKit는 네이티브 로딩 실패 시 java.lang.Error를 던지므로 Throwable로 잡아야 크래시를 막을 수 있음
        LufsResult(-16.0, -1.0, describeError(t))
    } finally {
        temp.delete()
    }
}

suspend fun boostAndSaveTrack(
    context: Context,
    uri: Uri,
    name: String,
    gainDb: Double,
    bitrate: String,
    outputTreeUri: Uri? = null
): String? = withContext(Dispatchers.IO) {
    val tempIn = File(context.cacheDir, "in_${System.currentTimeMillis()}.mp3")
    val tempOut = File(context.cacheDir, "out_${System.currentTimeMillis()}.mp3")
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempIn).use { output -> input.copyTo(output) }
        }

        val filter = "volume=${String.format(Locale.US, "%.2f", gainDb)}dB,alimiter=limit=-1.0dB:level=false"
        val cmd = "-y -i \"${tempIn.absolutePath}\" -af \"$filter\" -c:a libmp3lame -b:a $bitrate \"${tempOut.absolutePath}\""
        val session = FFmpegKit.execute(cmd)

        if (session.returnCode.isValueSuccess && tempOut.exists()) {
            // 원본 파일명을 그대로 유지하며 저장
            val saved = if (outputTreeUri != null) {
                // 폴더로 추가한 경우: 같은 폴더의 output 하위폴더에 저장
                saveToTreeOutput(context, tempOut, name, outputTreeUri)
            } else {
                // 개별 파일로 추가한 경우: 기본 저장소(Music/MaxSync_Output)에 저장
                saveToMusicStorage(context, tempOut, name)
            }
            if (saved) null else "저장 실패 (권한 또는 경로 확인 필요)"
        } else {
            val tail = (session.allLogsAsString ?: "").trim().lines().takeLast(2).joinToString(" / ")
            "인코딩 실패 (code=${session.returnCode}): $tail"
        }
    } catch (t: Throwable) {
        describeError(t)
    } finally {
        tempIn.delete()
        tempOut.delete()
    }
}

// SAF 트리(폴더) 안에 "output" 하위폴더를 찾거나 생성한 뒤, 원본 파일명 그대로 저장
fun saveToTreeOutput(context: Context, sourceFile: File, displayName: String, treeUri: Uri): Boolean {
    return try {
        val resolver = context.contentResolver
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val outputDirId = findOrCreateDir(context, treeUri, rootDocId, "output") ?: return false

        // 같은 이름의 기존 파일이 있으면 먼저 삭제 (파일명이 바뀌지 않도록)
        deleteChildIfExists(context, treeUri, outputDirId, displayName)

        val outputDirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, outputDirId)
        val newUri = DocumentsContract.createDocument(
            resolver, outputDirUri, guessAudioMime(displayName), displayName
        ) ?: return false

        resolver.openOutputStream(newUri)?.use { out ->
            FileInputStream(sourceFile).use { inp -> inp.copyTo(out) }
        }
        true
    } catch (e: Exception) {
        false
    }
}

// SAF 폴더에서 이름이 일치하는 디렉토리의 documentId를 찾고, 없으면 생성
fun findOrCreateDir(context: Context, treeUri: Uri, parentDocId: String, dirName: String): String? {
    try {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)
                val mime = c.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR && name == dirName) {
                    return c.getString(0)
                }
            }
        }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        val created = DocumentsContract.createDocument(
            context.contentResolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, dirName
        ) ?: return null
        return DocumentsContract.getDocumentId(created)
    } catch (e: Exception) {
        return null
    }
}

// SAF 폴더에서 동일한 이름의 파일이 있으면 삭제
fun deleteChildIfExists(context: Context, treeUri: Uri, parentDocId: String, displayName: String) {
    try {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == displayName) {
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                    DocumentsContract.deleteDocument(context.contentResolver, childUri)
                    return
                }
            }
        }
    } catch (_: Exception) {}
}

// 선택한 SAF 폴더 바로 아래의 오디오 파일들을 수집 (하위폴더는 제외)
fun scanFolderForAudio(context: Context, treeUri: Uri): List<AudioTrackItem> {
    val result = mutableListOf<AudioTrackItem>()
    try {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                if (mime.startsWith("audio/") || isAudioName(name)) {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    result.add(
                        AudioTrackItem(
                            id = "f_${docId}",
                            uri = docUri,
                            name = name,
                            sizeMb = String.format(Locale.US, "%.1f", size / (1024.0 * 1024.0)),
                            outputTreeUri = treeUri
                        )
                    )
                }
            }
        }
    } catch (_: Exception) {}
    return result
}

fun isAudioName(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
    return ext in setOf(
        "mp3", "m4a", "m4b", "aac", "wav", "flac", "ogg", "opus",
        "wma", "amr", "aiff", "aif", "ape", "wv", "mka"
    )
}

fun guessAudioMime(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
    return when (ext) {
        "mp3" -> "audio/mpeg"
        "m4a", "m4b", "aac" -> "audio/mp4"
        "wav" -> "audio/x-wav"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "wma" -> "audio/x-ms-wma"
        else -> "audio/mpeg"
    }
}

fun saveToMusicStorage(context: Context, sourceFile: File, displayName: String): Boolean {
    return try {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/MaxSync_Output")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        context.contentResolver.openOutputStream(uri)?.use { out ->
            FileInputStream(sourceFile).use { inp -> inp.copyTo(out) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        }
        true
    } catch (e: Exception) {
        false
    }
}
