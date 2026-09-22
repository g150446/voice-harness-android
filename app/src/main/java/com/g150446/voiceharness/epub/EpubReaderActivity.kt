package com.g150446.voiceharness.epub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.g150446.voiceharness.BleConnectionService
import com.g150446.voiceharness.InteractionMode
import com.g150446.voiceharness.ui.theme.HarnessVoiceTheme
import kotlinx.coroutines.launch

/** Reads the open book on the phone; the Even G2 follows the same position while EPUB mode is on. */
class EpubReaderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HarnessVoiceTheme {
                Scaffold { padding -> ReaderScreen(Modifier.padding(padding)) }
            }
        }
    }
}

@Composable
private fun ReaderScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { EpubReaderHub.get(context) }
    val state by controller.state.collectAsState()
    val mode by BleConnectionService.interactionMode.collectAsState()

    LaunchedEffect(Unit) {
        if (state.bookId == null) runCatching { controller.enter() }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (state.bookId == null) {
            Text("開いている本がありません。EPUBライブラリから本を開いてください。", fontSize = 14.sp)
            return@Column
        }
        Text(state.title, fontSize = 17.sp)
        if (state.author.isNotBlank()) {
            Text(state.author, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        val small = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = { scope.launch { runCatching { controller.openToc() } } },
                contentPadding = small,
                modifier = Modifier.weight(1f),
            ) { Text("目次", fontSize = 12.sp, maxLines = 1) }
            OutlinedButton(
                onClick = { scope.launch { runCatching { controller.previousChapter() } } },
                contentPadding = small,
                modifier = Modifier.weight(1f),
            ) { Text("前の章", fontSize = 12.sp, maxLines = 1) }
            OutlinedButton(
                onClick = { scope.launch { runCatching { controller.previous() } } },
                contentPadding = small,
                modifier = Modifier.weight(1f),
            ) { Text("前へ", fontSize = 12.sp, maxLines = 1) }
            OutlinedButton(
                onClick = { scope.launch { runCatching { controller.next() } } },
                contentPadding = small,
                modifier = Modifier.weight(1f),
            ) { Text("次へ", fontSize = 12.sp, maxLines = 1) }
            OutlinedButton(
                onClick = { scope.launch { runCatching { controller.nextChapter() } } },
                contentPadding = small,
                modifier = Modifier.weight(1f),
            ) { Text("次の章", fontSize = 12.sp, maxLines = 1) }
        }
        Spacer(Modifier.height(6.dp))
        if (mode == InteractionMode.EPUB) {
            Text(
                "EPUBモード中: G2にも表示されます。目次で『3番』『第二章』のように話すと開きます。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Button(
                onClick = { BleConnectionService.setInteractionMode(context, InteractionMode.EPUB) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("EPUBモードにする（G2に表示）") }
        }
        state.message?.let {
            Text(it, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        when (state.view) {
            EpubView.TEXT -> key(state.chapter, state.start) {
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(state.text, fontSize = 16.sp, lineHeight = 26.sp)
                }
            }
            EpubView.TOC, EpubView.CANDIDATES -> {
                val indexes = if (state.view == EpubView.CANDIDATES) state.candidates else state.toc.indices.toList()
                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(indexes) { position, index ->
                        val entry = state.toc[index]
                        val number = if (state.view == EpubView.CANDIDATES) position + 1 else index + 1
                        Text(
                            text = "$number  " + "　".repeat(entry.depth.coerceAtMost(3)) + entry.title,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { scope.launch { runCatching { controller.openEntry(index) } } }
                                .padding(vertical = 10.dp),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
