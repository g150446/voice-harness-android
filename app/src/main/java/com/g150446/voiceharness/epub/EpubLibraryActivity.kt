package com.g150446.voiceharness.epub

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.g150446.voiceharness.ui.theme.HarnessVoiceTheme
import kotlinx.coroutines.launch

/** Import EPUB files into the app and pick the book to read. */
class EpubLibraryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HarnessVoiceTheme {
                Scaffold { padding -> LibraryScreen(Modifier.padding(padding)) }
            }
        }
    }
}

internal fun epubErrorText(error: Throwable?): String =
    if (error is EpubException) error.message.orEmpty() else "読み込みに失敗しました"

@Composable
private fun LibraryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = remember { EpubReaderHub.library(context) }
    var books by remember { mutableStateOf(library.list()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = "取り込み中..."
        scope.launch {
            val result = library.import(uri)
            busy = false
            books = library.list()
            status = result.fold(
                onSuccess = { "追加しました: ${it.title}" },
                onFailure = { epubErrorText(it) },
            )
        }
    }

    Column(
        modifier = modifier.padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Text("EPUBライブラリ", fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { picker.launch(arrayOf("application/epub+zip", "application/octet-stream")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("EPUBを追加") }
        if (status.isNotEmpty()) {
            Text(status, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(12.dp))
        if (books.isEmpty()) {
            Text(
                "本がありません。「EPUBを追加」でファイルを選んでください。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        books.forEach { book ->
            HorizontalDivider()
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(book.title, fontSize = 15.sp)
                if (book.author.isNotBlank()) {
                    Text(book.author, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            status = "開いています..."
                            scope.launch {
                                EpubReaderHub.get(context).openBook(book.id).fold(
                                    onSuccess = {
                                        status = ""
                                        context.startActivity(Intent(context, EpubReaderActivity::class.java))
                                    },
                                    onFailure = { status = epubErrorText(it) },
                                )
                            }
                        },
                        enabled = !busy,
                    ) { Text("開く") }
                    OutlinedButton(
                        onClick = {
                            library.remove(book.id)
                            books = library.list()
                        },
                        enabled = !busy,
                    ) { Text("削除") }
                }
            }
        }
    }
}
