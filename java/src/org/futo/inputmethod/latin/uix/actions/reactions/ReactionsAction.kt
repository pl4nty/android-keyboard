package org.futo.inputmethod.latin.uix.actions.reactions

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionHeaderSearch
import org.futo.inputmethod.latin.uix.ActionTextEditor
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.DialogRequestItem
import org.futo.inputmethod.latin.uix.LocalKeyboardScheme
import org.futo.inputmethod.latin.uix.actions.clipboard.CLIPBOARD_AUTHORITY
import org.futo.inputmethod.latin.uix.actions.clipboard.ClipboardPasteRequest
import org.futo.inputmethod.latin.uix.actions.clipboard.ClipboardProviderState
import org.futo.inputmethod.latin.uix.actions.clipboard.ClipboardThumbCache
import org.futo.inputmethod.latin.uix.actions.clipboard.ClipboardUtil
import org.futo.inputmethod.latin.uix.actions.throwIfDebug
import org.futo.inputmethod.latin.uix.settings.NavigationItem
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.theme.Typography
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

@Serializable
data class Reaction(
    val file: String,
    val mimeType: String,
    val label: String = "",
    val added: Long,
    val lastUsed: Long = 0L,
)

val Context.reactionsDir get() = File(filesDir, "reactions")
private val Context.reactionsFile get() = File(filesDir, "reactions.json")

private val SupportedMimeTypes = mapOf(
    "image/png" to "png",
    "image/jpeg" to "jpg",
    "image/jpg" to "jpg",
    "image/webp" to "webp",
    "image/gif" to "gif",
)

private const val MaxReactionBytes = 20L * 1024L * 1024L

object ReactionStore {
    val reactions = mutableStateListOf<Reaction>()
    val loaded = mutableStateOf(false)
    val pendingEdit = mutableStateOf<String?>(null)

    private val ioLock = Mutex()

    suspend fun load(context: Context) = ioLock.withLock {
        if(loaded.value) return@withLock
        val data = withContext(Dispatchers.IO) {
            try {
                val file = context.reactionsFile
                if(file.exists()) {
                    Json.decodeFromString<List<Reaction>>(AtomicFile(file).readFully().decodeToString())
                } else {
                    emptyList()
                }
            } catch(e: Exception) {
                throwIfDebug(e)
                null
            }
        } ?: return@withLock
        reactions.clear()
        reactions.addAll(data.filter { File(context.reactionsDir, it.file).isFile })
        loaded.value = true
    }

    private suspend fun save(context: Context) {
        val list = reactions.toList()
        ioLock.withLock {
            withContext(Dispatchers.IO) {
                val file = AtomicFile(context.reactionsFile)
                val stream = file.startWrite()
                try {
                    stream.write(Json.encodeToString(list).toByteArray())
                    file.finishWrite(stream)
                } catch(e: Exception) {
                    file.failWrite(stream)
                    throwIfDebug(e)
                }
            }
        }
    }

    suspend fun add(context: Context, mimeType: String, open: () -> InputStream?): Reaction? {
        load(context)
        val extension = SupportedMimeTypes[mimeType] ?: return null
        val name = withContext(Dispatchers.IO) {
            try {
                val dir = context.reactionsDir.also { it.mkdirs() }
                val temp = File(dir, "import.tmp")
                val md = MessageDigest.getInstance("MD5")
                var total = 0L
                (open() ?: return@withContext null).use { input ->
                    temp.outputStream().use { out ->
                        val buffer = ByteArray(8 * 1024)
                        while(true) {
                            val read = input.read(buffer)
                            if(read == -1) break
                            total += read
                            if(total > MaxReactionBytes) {
                                temp.delete()
                                return@withContext null
                            }
                            md.update(buffer, 0, read)
                            out.write(buffer, 0, read)
                        }
                    }
                }
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                val target = File(dir, "$hex.$extension")
                if(target.exists()) temp.delete() else temp.renameTo(target)
                ClipboardUtil.generateThumbnail(target)
                target.name
            } catch(e: Exception) {
                throwIfDebug(e)
                null
            }
        } ?: return null

        reactions.firstOrNull { it.file == name }?.let { return it }
        val reaction = Reaction(file = name, mimeType = mimeType, added = System.currentTimeMillis())
        reactions.add(reaction)
        save(context)
        return reaction
    }

    suspend fun addFile(context: Context, file: File, mimeType: String): Reaction? =
        add(context, mimeType) { file.inputStream() }

    suspend fun addUri(context: Context, uri: Uri): Reaction? {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(uri.toString()))
            ?: return null
        return add(context, mimeType) { resolver.openInputStream(uri) }
    }

    suspend fun setLabel(context: Context, file: String, label: String) {
        val i = reactions.indexOfFirst { it.file == file }
        if(i == -1) return
        reactions[i] = reactions[i].copy(label = label.trim())
        save(context)
    }

    suspend fun markUsed(context: Context, file: String) {
        val i = reactions.indexOfFirst { it.file == file }
        if(i == -1) return
        reactions[i] = reactions[i].copy(lastUsed = System.currentTimeMillis())
        save(context)
    }

    suspend fun remove(context: Context, file: String) {
        reactions.removeAll { it.file == file }
        save(context)
        withContext(Dispatchers.IO) {
            val image = File(context.reactionsDir, file)
            ClipboardUtil.thumbnailFor(image).delete()
            image.delete()
        }
    }

    fun search(query: String): List<Reaction> {
        val terms = query.lowercase().split(" ").filter { it.isNotBlank() }
        return reactions
            .filter { r -> terms.all { r.label.lowercase().contains(it) } }
            .sortedWith(compareByDescending<Reaction> { it.lastUsed }.thenByDescending { it.added })
    }
}

@Composable
private fun ReactionView(reaction: Reaction, onSend: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(reaction.file) {
        val thumbnail = ClipboardUtil.thumbnailFor(File(context.reactionsDir, reaction.file))
        ClipboardThumbCache.getOrPut(thumbnail.name) {
            BitmapFactory.decodeFile(thumbnail.absolutePath)?.asImageBitmap()
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .padding(2.dp)
            .aspectRatio(1.0f)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onSend, onLongClick = onEdit)
    ) {
        Box {
            if(bitmap != null) {
                Image(bitmap, contentDescription = reaction.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if(reaction.label.isNotEmpty()) {
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                ) {
                    Text(
                        reaction.label,
                        style = Typography.SmallMl,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(4.dp, 2.dp)
                    )
                }
            }
        }
    }
}

val ReactionsAction = Action(
    icon = R.drawable.sticker,
    name = R.string.action_reactions_title,
    simplePressImpl = null,
    canShowKeyboard = true,
    windowImpl = { manager, _ ->
        val searching = mutableStateOf(false)
        val searchText = mutableStateOf("")
        val editing = mutableStateOf(ReactionStore.pendingEdit.value)
        val labelText = mutableStateOf(
            ReactionStore.reactions.firstOrNull { it.file == editing.value }?.label ?: ""
        )
        ReactionStore.pendingEdit.value = null
        val unlocked = !manager.isDeviceLocked()
        val context = manager.getContext()

        fun startEditing(reaction: Reaction) {
            labelText.value = reaction.label
            editing.value = reaction.file
        }

        fun finishEditing() {
            val file = editing.value ?: return
            val label = labelText.value
            editing.value = null
            manager.getLifecycleScope().launch { ReactionStore.setLabel(context, file, label) }
        }

        object : ActionWindow() {
            @Composable
            override fun windowName(): String = stringResource(R.string.action_reactions_title)

            @Composable
            override fun WindowTitleBar(rowScope: RowScope) = with(rowScope) {
                val file = editing.value
                if(file != null) {
                    val resources = LocalResources.current
                    Surface(
                        color = LocalKeyboardScheme.current.keyboardContainer,
                        contentColor = LocalKeyboardScheme.current.onKeyboardContainer,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.weight(1.0f).padding(2.dp)
                    ) {
                        Box(modifier = Modifier.padding(8.dp), contentAlignment = Alignment.CenterStart) {
                            ActionTextEditor(
                                text = labelText,
                                placeholder = stringResource(R.string.action_reactions_label_placeholder),
                                onEnter = { finishEditing() }
                            )
                        }
                    }
                    IconButton(onClick = {
                        manager.requestDialog(
                            resources.getString(R.string.action_reactions_remove_confirm),
                            listOf(
                                DialogRequestItem(resources.getString(R.string.action_reactions_cancel)) {},
                                DialogRequestItem(resources.getString(R.string.action_reactions_remove)) {
                                    editing.value = null
                                    manager.getLifecycleScope().launch { ReactionStore.remove(context, file) }
                                }
                            ),
                            {}
                        )
                    }) {
                        Icon(painterResource(R.drawable.delete), contentDescription = stringResource(R.string.action_reactions_remove))
                    }
                    TextButton(onClick = { finishEditing() }) {
                        Text(stringResource(R.string.action_reactions_done))
                    }
                    return
                }

                if(searching.value) {
                    ActionHeaderSearch(searchText, Modifier.weight(1.0f),
                        placeholder = stringResource(R.string.action_reactions_enter_your_search))
                    return
                }

                Text(windowName(), modifier = Modifier.align(Alignment.CenterVertically))
                Spacer(modifier = Modifier.weight(1.0f))
                if(unlocked) {
                    IconButton(onClick = { searching.value = true }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_reactions_search))
                    }
                }
            }

            @Composable
            override fun WindowContents(keyboardShown: Boolean) {
                val view = LocalView.current

                if(!unlocked) {
                    Tip(stringResource(R.string.action_reactions_device_locked))
                    return
                }

                LaunchedEffect(Unit) { ReactionStore.load(context) }
                if(!ReactionStore.loaded.value) return

                if(ReactionStore.reactions.isEmpty()) {
                    Tip(stringResource(R.string.action_reactions_empty))
                    return
                }

                val results = ReactionStore.search(if(searching.value) searchText.value else "")
                if(results.isEmpty()) {
                    Text(
                        stringResource(R.string.action_reactions_no_results),
                        style = Typography.Body.Medium.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp).fillMaxWidth()
                    )
                    return
                }

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(88.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(results, key = { it.file }) { reaction ->
                        ReactionView(
                            reaction = reaction,
                            onSend = {
                                val request = ClipboardProviderState.addRequest(
                                    ClipboardPasteRequest(
                                        file = File(context.reactionsDir, reaction.file),
                                        mimeType = reaction.mimeType,
                                        expiration = System.currentTimeMillis() + 5L * 60L * 1000L
                                    )
                                )
                                val uri = "content://${CLIPBOARD_AUTHORITY}/clip/$request".toUri()
                                manager.typeUri(uri, listOf(reaction.mimeType), true)
                                manager.performHapticAndAudioFeedback(Constants.CODE_OUTPUT_TEXT, view)
                                manager.getLifecycleScope().launch { ReactionStore.markUsed(context, reaction.file) }
                            },
                            onEdit = {
                                startEditing(reaction)
                                manager.performHapticAndAudioFeedback(Constants.CODE_TAB, view)
                            }
                        )
                    }
                }
            }

            override fun close() = super.close().also { finishEditing() }
        }
    },

    settingsMenu = UserSettingsMenu(
        title = R.string.action_reactions_settings_title,
        navPath = "actions/reactions",
        registerNavPath = true,
        settings = listOf(
            UserSetting(
                name = R.string.action_reactions_settings_import,
                subtitle = R.string.action_reactions_settings_import_subtitle,
                component = {
                    val context = LocalContext.current
                    val scope = rememberCoroutineScope()
                    LaunchedEffect(Unit) { ReactionStore.load(context) }
                    val launcher = rememberLauncherForActivityResult(
                        ActivityResultContracts.PickMultipleVisualMedia()
                    ) { uris ->
                        scope.launch { uris.forEach { ReactionStore.addUri(context, it) } }
                    }
                    NavigationItem(
                        title = stringResource(R.string.action_reactions_settings_import),
                        subtitle = stringResource(R.string.action_reactions_settings_count, ReactionStore.reactions.size),
                        style = NavigationItemStyle.MiscNoArrow,
                        icon = painterResource(R.drawable.image),
                        navigate = {
                            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    )
                }
            ),
        )
    )
)
