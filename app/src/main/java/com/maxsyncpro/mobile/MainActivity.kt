package com.maxsyncpro.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

data class AudioTrackItem(
    val id: String,
    val name: String,
    val filePath: String,
    val integratedLufs: Double? = null,
    val truePeak: Double? = null,
    val gainDb: Double = 0.0,
    val needsBoost: Boolean = false,
    val isReference: Boolean = false,
    val status: String = "대기"
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MaxSyncProMobileScreen()
            }
        }
    }
}

@Composable
fun MaxSyncProMobileScreen() {
    val scope = rememberCoroutineScope()
    var tracks by remember { mutableStateOf(listOf<AudioTrackItem>()) }
    var thresholdLu by remember { mutableStateOf(1.0f) }
    var truePeakLimit by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("오디오 파일을 선택해 주세요.") }

    val maxRefLufs = tracks.mapNotNull { it.integratedLufs }.maxOrNull()

    // EBU R128 측정 정규식 (I: -14.2 LUFS)
    val lufsRegex = Regex("""I:\s*(-?\d+\.\d+)\s*LUFS""")
    val peakRegex = Regex("""Peak:\s*(-?\d+\.\d+)\s*dBFS""")

    fun recalculateReferenceAndGains(list: List<AudioTrackItem>, threshold: Float): List<AudioTrackItem> {
        val maxLufs = list.mapNotNull { it.integratedLufs }.maxOrNull() ?: return list
        val refTrack = list.maxByOrNull { it.integratedLufs ?: -99.0 }

        return list.map { item ->
            val lufs = item.integratedLufs ?: return@map item
            if (item.id == refTrack?.id) {
                item.copy(gainDb = 0.0, needsBoost = false, isReference = true, status = "최고음량 기준곡")
            } else {
                val diff = maxLufs - lufs
                if (diff >= threshold) {
                    item.copy(gainDb = diff, needsBoost = true, isReference = false, status = String.format(Locale.US, "+%.1f dB 필요", diff))
                } else {
                    item.copy(gainDb = 0.0, needsBoost = false, isReference = false, status = "Skip (충분히 큼)")
                }
            }
        }
    }

    // 1단계: FFmpegKit EBU R128(LUFS) 전체 곡 스캔
    fun runEbuR128Scan() {
        if (tracks.isEmpty() || isProcessing) return
        isProcessing = true
        scope.launch(Dispatchers.IO) {
            val updated = tracks.toMutableList()
            for (i in updated.indices) {
                val t = updated[i]
                withContext(Dispatchers.Main) {
                    statusMessage = "EBU R128 측정 중 (${i + 1}/${updated.size}): ${t.name}"
                }
                val cmd = "-nostats -i \"${t.filePath}\" -filter_complex ebur128=peak=true -f null -"
                val session = FFmpegKit.execute(cmd)
                val logs = session.allLogsAsString ?: ""

                val lufsMatch = lufsRegex.findAll(logs).lastOrNull()
                val tpMatch = peakRegex.findAll(logs).lastOrNull()

                val measuredLufs = lufsMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: -14.0
                val measuredTp = tpMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: -1.0

                updated[i] = t.copy(integratedLufs = measuredLufs, truePeak = measuredTp)
            }
            val finalCalculated = recalculateReferenceAndGains(updated, thresholdLu)
            withContext(Dispatchers.Main) {
                tracks = finalCalculated
                isProcessing = false
                statusMessage = "측정 완료! 최고 음량 기준곡에 맞춰 증폭 대상이 선별되었습니다."
            }
        }
    }

    // 2단계: 증폭 필요 파일만 일괄 증폭 실행
    fun runBatchAmplify() {
        val targets = tracks.filter { it.needsBoost }
        if (targets.isEmpty() || isProcessing) return
        isProcessing = true

        scope.launch(Dispatchers.IO) {
            val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "MaxSync_Normalized")
            if (!outDir.exists()) outDir.mkdirs()

            val currentList = tracks.toMutableList()
            for ((idx, item) in currentList.withIndex()) {
                if (!item.needsBoost) continue
                withContext(Dispatchers.Main) {
                    statusMessage = "증폭 인코딩 중 (+${String.format(Locale.US, "%.1f", item.gainDb)}dB): ${item.name}"
                }

                val outFile = File(outDir, item.name)
                val afFilter = if (truePeakLimit) {
                    String.format(Locale.US, "volume=%.1fdB,alimiter=limit=-0.5dB:level=false", item.gainDb)
                } else {
                    String.format(Locale.US, "volume=%.1fdB", item.gainDb)
                }

                val cmd = "-y -i \"${item.filePath}\" -af \"$afFilter\" -c:a libmp3lame -b:a 320k -map_metadata 0 -id3v2_version 3 \"${outFile.absolutePath}\""
                val session = FFmpegKit.execute(cmd)

                if (ReturnCode.isSuccess(session.returnCode)) {
                    currentList[idx] = item.copy(
                        integratedLufs = maxRefLufs,
                        needsBoost = false,
                        status = "증폭 완료"
                    )
                }
            }
            withContext(Dispatchers.Main) {
                tracks = currentList
                isProcessing = false
                statusMessage = "증폭 완료! /Music/MaxSync_Normalized 폴더에 저장되었습니다."
            }
        }
    }

    Scaffold(
        containerColor = Color(0xFF090B10)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("MaxSync Pro Mobile", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(statusMessage, fontSize = 12.sp, color = Color(0xFF38BDF8))

            // 기준 음량 표시 카드
            Surface(
                color = Color(0xFF11151F),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "최고 음량 기준 (Target LUFS): ${maxRefLufs?.let { String.format(Locale.US, "%.1f LUFS", it) } ?: "--.- LUFS"}",
                        color = Color(0xFFF59E0B),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("판정 임계값: ${String.format(Locale.US, "%.1f LU", thresholdLu)} 이상 차이날 때만 증폭", color = Color.LightGray, fontSize = 12.sp)
                    Slider(
                        value = thresholdLu,
                        onValueChange = {
                            thresholdLu = it
                            tracks = recalculateReferenceAndGains(tracks, thresholdLu)
                        },
                        valueRange = 0.2f..4.0f
                    )
                }
            }

            // 1단계 & 2단계 실행 버튼
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { runEbuR128Scan() },
                    enabled = !isProcessing && tracks.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("1단계: LUFS 측정", fontSize = 12.sp)
                }
                Button(
                    onClick = { runBatchAmplify() },
                    enabled = !isProcessing && tracks.any { it.needsBoost },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("2단계: 일괄 증폭", fontSize = 12.sp)
                }
            }

            // 오디오 트랙 목록
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(tracks) { item ->
                    Surface(
                        color = Color(0xFF11151F),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = item.integratedLufs?.let { String.format(Locale.US, "%.1f LUFS", it) } ?: "미측정",
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                            Text(item.status, color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}