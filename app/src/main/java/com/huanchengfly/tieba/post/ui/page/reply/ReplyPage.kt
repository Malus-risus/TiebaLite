package com.huanchengfly.tieba.post.ui.page.reply

import android.util.Log
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.rounded.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.addTextChangedListener
import com.effective.android.panel.utils.PanelUtil
import com.github.panpf.sketch.compose.AsyncImage
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.arch.collectPartialAsState
import com.huanchengfly.tieba.post.arch.onEvent
import com.huanchengfly.tieba.post.arch.pageViewModel
import com.huanchengfly.tieba.post.models.database.Draft
import com.huanchengfly.tieba.post.pxToDpFloat
import com.huanchengfly.tieba.post.toMD5
import com.huanchengfly.tieba.post.toastShort
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.page.reply.ReplyPanelType.EMOJI
import com.huanchengfly.tieba.post.ui.page.reply.ReplyPanelType.NONE
import com.huanchengfly.tieba.post.ui.utils.imeNestedScroll
import com.huanchengfly.tieba.post.ui.widgets.compose.VerticalDivider
import com.huanchengfly.tieba.post.ui.widgets.edittext.widget.UndoableEditText
import com.huanchengfly.tieba.post.utils.AccountUtil
import com.huanchengfly.tieba.post.utils.Emoticon
import com.huanchengfly.tieba.post.utils.EmoticonManager
import com.huanchengfly.tieba.post.utils.StringUtil
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.ramcosta.composedestinations.spec.DestinationStyle
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.litepal.LitePal
import org.litepal.extension.deleteAllAsync
import org.litepal.extension.findFirstAsync
import kotlin.concurrent.thread

@OptIn(
    ExperimentalTextApi::class, ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class,
    FlowPreview::class
)
@Destination(style = DestinationStyle.BottomSheet::class)
@Composable
fun ReplyPage(
    navigator: DestinationsNavigator,
    forumId: Long,
    forumName: String,
    threadId: Long,
    postId: Long? = null,
    subPostId: Long? = null,
    replyUserId: Long? = null,
    replyUserName: String? = null,
    replyUserPortrait: String? = null,
    tbs: String? = null,
    viewModel: ReplyViewModel = pageViewModel()
) {
    val hash = remember(forumId, threadId, postId, subPostId) {
        "${threadId}_${postId}_${subPostId}".toMD5()
    }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val curTbs = remember(tbs) { tbs ?: AccountUtil.getAccountInfo { this.tbs }.orEmpty() }

    val isSending by viewModel.uiState.collectPartialAsState(
        prop1 = ReplyUiState::isSending,
        initial = false
    )
    val replySuccess by viewModel.uiState.collectPartialAsState(
        prop1 = ReplyUiState::replySuccess,
        initial = false
    )
    val curKeyboardType by viewModel.uiState.collectPartialAsState(
        prop1 = ReplyUiState::replyPanelType,
        initial = NONE
    )

    val keyboardController = LocalSoftwareKeyboardController.current
    var initialText by remember { mutableStateOf("") }
    var waitEditTextToSet by remember { mutableStateOf(false) }
    var editTextView by remember { mutableStateOf<UndoableEditText?>(null) }
    fun getText(): String {
        return editTextView?.text?.toString().orEmpty()
    }

    fun setText(text: String) {
        if (editTextView != null) {
            editTextView?.setText(StringUtil.getEmoticonContent(editTextView!!, text))
            editTextView?.setSelection(text.length)
        } else {
            initialText = text
            waitEditTextToSet = true
        }
    }

    fun insertEmoticon(text: String) {
        editTextView?.apply {
            val start = selectionStart
            this.text?.insert(start, text)
            this.setText(StringUtil.getEmoticonContent(this, this.text))
            setSelection(start + text.length)
        }
    }

    val curTextFlow = remember { MutableStateFlow("") }
    val curText by curTextFlow.collectAsState()
    LaunchedEffect(Unit) {
        curTextFlow
            .debounce(500)
            .distinctUntilChanged()
            .collect {
                Log.i("ReplyPage", "collect: $it")
                if (!replySuccess) {
                    thread {
                        Draft(hash, it).saveOrUpdate("hash = ?", hash)
                    }
                }
            }
    }
    LaunchedEffect(Unit) {
        LitePal.where("hash = ?", hash).findFirstAsync<Draft?>()
            .listen {
                if (it != null) {
                    setText(it.content)
                }
            }
    }
    val textLength by remember { derivedStateOf { curText.length } }
    val isTextEmpty by remember { derivedStateOf { curText.isEmpty() } }

    viewModel.onEvent<ReplyUiEvent.ReplySuccess> {
        if (it.expInc.isEmpty()) {
            context.toastShort(R.string.toast_reply_success_default)
        } else {
            context.toastShort(R.string.toast_reply_success, it.expInc)
        }
        LitePal.deleteAllAsync<Draft>("hash = ?", hash).listen { navigator.navigateUp() }
    }
    var closingPanel by remember { mutableStateOf(false) }
    var startClosingAnimation by remember { mutableStateOf(false) }

    fun showKeyboard() {
        editTextView?.apply {
            PanelUtil.showKeyboard(context, this)
            requestFocus()
        }
        keyboardController?.show()
    }

    fun hideKeyboard() {
        editTextView?.apply {
            PanelUtil.hideKeyboard(context, this)
            clearFocus()
        }
        keyboardController?.hide()
    }

    fun switchToPanel(type: ReplyPanelType) {
        if (curKeyboardType == type || type == NONE) {
            if (curKeyboardType != NONE) {
                showKeyboard()
                closingPanel = true
                startClosingAnimation = false
            }
            viewModel.send(ReplyUiIntent.SwitchPanel(NONE))
        } else {
            hideKeyboard()
            viewModel.send(ReplyUiIntent.SwitchPanel(type))
        }
    }

    val density = LocalDensity.current
    val imeInset = WindowInsets.ime
    val imeAnimationTargetInset = WindowInsets.imeAnimationTarget

    val imeCurrentHeight by produceState(initialValue = 0, imeInset, density) {
        snapshotFlow { imeInset.getBottom(density) }
            .distinctUntilChanged()
            .collect { value = it }
    }
    val imeAnimationTargetHeight by produceState(
        initialValue = 0,
        imeAnimationTargetInset,
        density
    ) {
        snapshotFlow { imeAnimationTargetInset.getBottom(density) }
            .distinctUntilChanged()
            .collect { value = it }
    }
    val imeAnimationEnd by remember { derivedStateOf { imeCurrentHeight == imeAnimationTargetHeight } }
    val imeVisibleHeight by produceState(initialValue = 0, imeAnimationTargetInset, density) {
        snapshotFlow { imeAnimationTargetInset.getBottom(density) }
            .filter { it > 0 }
            .distinctUntilChanged()
            .collect { value = it }
    }

    val textMeasurer = rememberTextMeasurer()

    val minResult = textMeasurer.measure(
        AnnotatedString("\n\n"),
        style = LocalTextStyle.current
    ).size.height.pxToDpFloat().dp

    val maxResult = textMeasurer.measure(
        AnnotatedString("\n\n\n\n\n"),
        style = LocalTextStyle.current
    ).size.height.pxToDpFloat().dp

    LaunchedEffect(closingPanel, imeAnimationEnd) {
        if (closingPanel) {
            if (!startClosingAnimation && !imeAnimationEnd) {
                startClosingAnimation = true
            } else if (startClosingAnimation && imeAnimationEnd) {
                closingPanel = false
                startClosingAnimation = false
            }
        }
    }

    val textFieldScrollState = rememberScrollState()

    val parentModifier = if (curKeyboardType == NONE && !closingPanel) {
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    } else {
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .consumeWindowInsets(WindowInsets.ime)
    }

    Column(
        modifier = parentModifier,
        verticalArrangement = Arrangement.Bottom
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(id = R.string.title_reply),
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "$textLength",
                style = MaterialTheme.typography.caption,
                color = ExtendedTheme.colors.textSecondary
            )
        }
        VerticalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        Box(
            modifier = Modifier
                .wrapContentHeight()
                .fillMaxWidth()
                .imeNestedScroll(textFieldScrollState),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .requiredHeightIn(min = minResult, max = maxResult)
                    .verticalScroll(textFieldScrollState)
            ) {
                AndroidView(
                    factory = { ctx ->
                        (View.inflate(
                            ctx,
                            R.layout.layout_reply_edit_text,
                            null
                        ) as UndoableEditText).apply {
                            editTextView = this
                            if (subPostId != null && subPostId != 0L && replyUserName != null) {
                                hint = ctx.getString(R.string.hint_reply, replyUserName)
                            }
                            setOnFocusChangeListener { _, hasFocus ->
                                if (hasFocus) {
                                    switchToPanel(NONE)
                                }
                            }
                            addTextChangedListener(
                                afterTextChanged = {
                                    coroutineScope.launch {
                                        curTextFlow.emit(it?.toString() ?: "")
                                    }
                                }
                            )
                            if (waitEditTextToSet) {
                                waitEditTextToSet = false
                                this.setText(StringUtil.getEmoticonContent(this, initialText))
                                this.setSelection(initialText.length)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(align = Alignment.Top)
                )
            }
//            BaseTextField(
//                value = text,
//                onValueChange = { text = it },
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .padding(16.dp)
//                    .requiredHeightIn(min = minResult, max = maxResult)
//                    .focusRequester(focusRequester)
//                    .verticalScroll(textFieldScrollState)
//                    .onFocusChanged {
//                        Log.i("ReplyPage", "onFocusChanged: $it")
//                        if (it.hasFocus) {
//                            switchToKeyboard(NONE)
//                        }
//                    },
//                placeholder = { Text(text = stringResource(id = R.string.tip_reply)) },
//            )
        }
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            IconButton(
                onClick = { switchToPanel(EMOJI) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.EmojiEmotions,
                    contentDescription = stringResource(id = R.string.insert_emotions),
                    modifier = Modifier.size(24.dp)
                )
            }
//            IconButton(
//                onClick = { switchToPanel(IMAGE) },
//                modifier = Modifier.size(24.dp)
//            ) {
//                Icon(
//                    imageVector = Icons.Outlined.InsertPhoto,
//                    contentDescription = stringResource(id = R.string.insert_photo),
//                    modifier = Modifier.size(24.dp)
//                )
//            }
//            IconButton(
//                onClick = { switchToPanel(VOICE) },
//                modifier = Modifier.size(24.dp)
//            ) {
//                Icon(
//                    imageVector = Icons.Outlined.KeyboardVoice,
//                    contentDescription = stringResource(id = R.string.insert_voice),
//                    modifier = Modifier.size(24.dp)
//                )
//            }
            Spacer(modifier = Modifier.weight(1f))
            if (isSending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = ExtendedTheme.colors.accent
                )
            } else {
                IconButton(
                    onClick = {
                        val replyContent = if (subPostId == null || subPostId == 0L) {
                            getText()
                        } else {
                            "回复 #(reply, ${replyUserPortrait}, ${replyUserName}) :${getText()}"
                        }
                        viewModel.send(
                            ReplyUiIntent.Send(
                                content = replyContent,
                                forumId = forumId,
                                forumName = forumName,
                                threadId = threadId,
                                tbs = curTbs,
                                postId = postId,
                                subPostId = subPostId,
                                replyUserId = replyUserId
                            )
                        )
                    },
                    enabled = !isTextEmpty,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Send,
                        contentDescription = stringResource(id = R.string.send_reply),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsBottomHeight(WindowInsets.ime)
        )
        if (curKeyboardType != NONE) {
            Column(modifier = Modifier.height(imeVisibleHeight.pxToDpFloat().dp)) {
                when (curKeyboardType) {
                    EMOJI -> {
                        EmoticonPanel(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            onEmoticonClick = { emoticon ->
                                insertEmoticon("#(${emoticon.name})")
                            }
                        )
                    }

                    else -> {}
                }
            }
        } else if (closingPanel) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(imeVisibleHeight.pxToDpFloat().dp)
            )
        }
    }

    DisposableEffect(editTextView) {
        showKeyboard()

        onDispose {
            if (editTextView != null) {
                hideKeyboard()
            }
        }
    }
}

@Composable
private fun EmoticonPanel(
    modifier: Modifier = Modifier,
    onEmoticonClick: (Emoticon) -> Unit,
) {
    val emoticons = remember { EmoticonManager.getAllEmoticon() }

    Column(
        modifier = modifier
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(48.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        ) {
            items(emoticons) { emoticon ->
                AsyncImage(
                    imageUri = EmoticonManager.rememberEmoticonUri(id = emoticon.id),
                    contentDescription = stringResource(
                        id = R.string.emoticon,
                        emoticon.name
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .padding(8.dp)
                        .clickable { onEmoticonClick(emoticon) }
                )
            }
        }
    }
}