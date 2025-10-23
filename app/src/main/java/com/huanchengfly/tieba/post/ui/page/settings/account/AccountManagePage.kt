package com.huanchengfly.tieba.post.ui.page.settings.account

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.SupervisedUserCircle
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.dataStore
import com.huanchengfly.tieba.post.ui.common.prefs.PrefsScreen
import com.huanchengfly.tieba.post.ui.common.prefs.widgets.DropDownPref
import com.huanchengfly.tieba.post.ui.common.prefs.widgets.EditTextPref
import com.huanchengfly.tieba.post.ui.common.prefs.widgets.TextPref
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.page.destinations.LoginPageDestination
import com.huanchengfly.tieba.post.ui.page.settings.LeadingIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.AvatarIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.BackNavigationIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.Sizes
import com.huanchengfly.tieba.post.ui.widgets.compose.TitleCentredToolbar
import com.huanchengfly.tieba.post.utils.AccountUtil
import com.huanchengfly.tieba.post.utils.AccountUtil.AllAccounts
import com.huanchengfly.tieba.post.utils.AccountUtil.LocalAccount
import com.huanchengfly.tieba.post.utils.TiebaUtil
import com.huanchengfly.tieba.post.utils.appPreferences
import com.huanchengfly.tieba.post.utils.launchUrl
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterialApi::class, ExperimentalComposeUiApi::class)
@Destination
@Composable
fun AccountManagePage(
    navigator: DestinationsNavigator,
) {
    Scaffold(
        backgroundColor = Color.Transparent,
        topBar = {
            TitleCentredToolbar(
                title = {
                    Text(
                        text = stringResource(id = R.string.title_account_manage),
                        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.h6
                    )
                },
                navigationIcon = {
                    BackNavigationIcon(onBackPressed = { navigator.navigateUp() })
                }
            )
        },
    ) { paddingValues ->
        val account = LocalAccount.current
        val context = LocalContext.current
        val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

        // 将这些状态变量移到外层作用域，以便在未登录状态下也能访问
        var inputBduss by remember { mutableStateOf("") }
        var inputStoken by remember { mutableStateOf("") }
        var isLoading by remember { mutableStateOf(false) }
        var showDialog by remember { mutableStateOf(false) }

        fun copyToClipboard(context: Context, text: String, label: String) {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = ClipData.newPlainText(label, text)
            clipboardManager.setPrimaryClip(clipData)
            Toast.makeText(context, context.getString(R.string.toast_copied_to_clipboard, label), Toast.LENGTH_SHORT).show()
        }
        PrefsScreen(
            dataStore = LocalContext.current.dataStore,
            dividerThickness = 0.dp,
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
        ) {
            prefsItem {
                if (account != null) {
                    DropDownPref(
                        key = "switch_account",
                        title = stringResource(id = R.string.title_switch_account),
                        summary = stringResource(
                            id = R.string.summary_now_account,
                            account.nameShow ?: account.name
                        ),
                        leadingIcon = {
                            LeadingIcon {
                                AvatarIcon(
                                    icon = Icons.Outlined.AccountCircle,
                                    size = Sizes.Small,
                                    contentDescription = null,
                                )
                            }
                        },
                        onValueChange = {
                            coroutineScope.launch {
                                AccountUtil.switchAccount(context, it.toInt())
                            }
                        },
                        enabled = true,
                        defaultValue = account.id.toString(),
                        entries = AllAccounts.current.associate {
                            it.id.toString() to (it.nameShow ?: it.name)
                        }
                    )
                } else {
                    TextPref(
                        title = stringResource(id = R.string.title_switch_account),
                        summary = null,
                        leadingIcon = {
                            LeadingIcon {
                                AvatarIcon(
                                    icon = Icons.Outlined.AccountCircle,
                                    size = Sizes.Small,
                                    contentDescription = null,
                                )
                            }
                        },
                        darkenOnDisable = true,
                        enabled = false,
                    )
                }
            }
            prefsItem {
                TextPref(
                    title = stringResource(id = R.string.title_new_account),
                    onClick = {
                        // 在未登录状态下，也显示手动输入token的对话框
                        // 这样用户可以选择手动输入token而不是去百度登录
                        if (account == null) {
                            // 直接显示手动输入token的对话框
                            showDialog = true
                        } else {
                            // 已登录状态下，还是跳转到登录页面
                            navigator.navigate(LoginPageDestination)
                        }
                    },
                    leadingIcon = {
                        LeadingIcon {
                            AvatarIcon(
                                icon = Icons.Outlined.AddCircleOutline,
                                size = Sizes.Small,
                                contentDescription = null,
                            )
                        }
                    },
                )
            }
            prefsItem {
                Text(
                    text = buildAnnotatedString {
                        withStyle(
                            style = SpanStyle(
                                fontWeight = FontWeight.Bold
                            )
                        ) {
                            append(stringResource(id = R.string.tip_start))
                        }
                        append(stringResource(id = R.string.tip_account_error))
                    },
                    modifier = Modifier
                        .padding(16.dp)
                        .padding(start = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(color = ExtendedTheme.colors.chip)
                        .padding(12.dp),
                    color = ExtendedTheme.colors.onChip,
                    fontSize = 12.sp
                )
            }
            prefsItem {
                TextPref(
                    title = stringResource(id = R.string.title_exit_account),
                    onClick = {
                        coroutineScope.launch {
                            AccountUtil.exit(context)
                        }
                    },
                    leadingIcon = {
                        LeadingIcon {
                            AvatarIcon(
                                icon = ImageVector.vectorResource(id = R.drawable.ic_outlined_close_circle_24),
                                size = Sizes.Small,
                                contentDescription = null,
                            )
                        }
                    },
                )
            }
            // 当前账号Token查看部分
            if (account != null) {
                prefsItem {
                    TextPref(
                        title = stringResource(id = R.string.title_current_bduss),
                        summary = account.bduss,
                        leadingIcon = {
                            LeadingIcon {
                                AvatarIcon(
                                    icon = Icons.Outlined.ContentCopy,
                                    size = Sizes.Small,
                                    contentDescription = null,
                                )
                            }
                        },
                        onClick = {
                            copyToClipboard(context, account.bduss, "BDUSS")
                        }
                    )
                }
                prefsItem {
                    TextPref(
                        title = stringResource(id = R.string.title_current_stoken),
                        summary = account.sToken,
                        leadingIcon = {
                            LeadingIcon {
                                AvatarIcon(
                                    icon = Icons.Outlined.ContentCopy,
                                    size = Sizes.Small,
                                    contentDescription = null,
                                )
                            }
                        },
                        onClick = {
                            copyToClipboard(context, account.sToken, "STOKEN")
                        }
                    )
                }
            }

            // 手动设置Token部分
            prefsItem {
                TextPref(
                    title = stringResource(id = R.string.title_manual_token_login),
                    summary = stringResource(id = R.string.summary_manual_token_login),
                    leadingIcon = {
                        LeadingIcon {
                            AvatarIcon(
                                icon = Icons.Outlined.Edit,
                                size = Sizes.Small,
                                contentDescription = null,
                            )
                        }
                    },
                    onClick = {
                        showDialog = true
                    }
                )
            }

            prefsItem {
                TextPref(
                    title = stringResource(id = R.string.title_modify_username),
                    onClick = {
                        launchUrl(
                            context,
                            navigator,
                            "https://wappass.baidu.com/static/manage-chunk/change-username.html#/showUsername"
                        )
                    },
                    leadingIcon = {
                        LeadingIcon {
                            AvatarIcon(
                                icon = Icons.Outlined.SupervisedUserCircle,
                                size = Sizes.Small,
                                contentDescription = null,
                            )
                        }
                    },
                    enabled = account != null
                )
            }

            prefsItem {
                TextPref(
                    title = stringResource(id = R.string.title_copy_bduss),
                    summary = stringResource(id = R.string.summary_copy_bduss),
                    onClick = { TiebaUtil.copyText(context, account?.bduss, isSensitive = true) },
                    leadingIcon = {
                        LeadingIcon {
                            AvatarIcon(
                                icon = Icons.Outlined.ContentCopy,
                                size = Sizes.Small,
                                contentDescription = null,
                            )
                        }
                    },
                    enabled = account != null
                )
            }

            prefsItem {
                val littleTail = remember { context.appPreferences.littleTail }
                EditTextPref(
                    key = "little_tail",
                    title = stringResource(id = R.string.title_my_tail),
                    summary = if (littleTail.isNullOrEmpty())
                        stringResource(id = R.string.tip_no_little_tail)
                    else
                        littleTail,
                    leadingIcon = {
                        LeadingIcon {
                            AvatarIcon(
                                icon = Icons.Outlined.Edit,
                                size = Sizes.Small,
                                contentDescription = null,
                            )
                        }
                    },
                    enabled = true,
                    dialogTitle = stringResource(id = R.string.title_dialog_modify_little_tail),
                )
            }
        }

        // AlertDialog需要放在PrefsScreen外部，但在Scaffold内部
        if (showDialog) {
            androidx.compose.material.AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text(text = stringResource(id = R.string.title_manual_token_login)) },
                text = {
                    androidx.compose.foundation.layout.Column {
                        androidx.compose.material.TextField(
                            value = inputBduss,
                            onValueChange = { inputBduss = it },
                            label = { Text(text = stringResource(id = R.string.hint_input_bduss)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                            maxLines = 3,
                        )
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                        androidx.compose.material.TextField(
                            value = inputStoken,
                            onValueChange = { inputStoken = it },
                            label = { Text(text = stringResource(id = R.string.hint_input_stoken)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                            maxLines = 3,
                        )
                    }
                },
                confirmButton = {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        androidx.compose.material.TextButton(
                            onClick = {
                                if (inputBduss.isBlank() || inputStoken.isBlank()) {
                                    Toast.makeText(
                                        context,
                                        R.string.toast_input_token_error,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@TextButton
                                }

                                isLoading = true
                                coroutineScope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            AccountUtil.fetchAccountFlow(
                                                inputBduss.trim(),
                                                inputStoken.trim()
                                            ).collect { fetchedAccount ->
                                                AccountUtil.newAccount(
                                                    fetchedAccount.uid,
                                                    fetchedAccount
                                                ) { success ->
                                                    // Launch a new coroutine to handle the callback
                                                    GlobalScope.launch(Dispatchers.Main) {
                                                        if (success) {
                                                            Toast.makeText(
                                                                context,
                                                                R.string.toast_token_login_success,
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                            AccountUtil.switchAccount(context, fetchedAccount.id)
                                                        } else {
                                                            Toast.makeText(
                                                                context,
                                                                R.string.toast_token_login_failed,
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                        isLoading = false
                                                        showDialog = false
                                                    }
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.toast_token_login_failed_with_error, e.message),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            isLoading = false
                                            showDialog = false
                                        }
                                    }
                                }
                            }
                        ) {
                            Text(text = stringResource(id = R.string.button_confirm))
                        }
                    }
                },
                dismissButton = {
                    androidx.compose.material.TextButton(
                        onClick = { showDialog = false }
                    ) {
                        Text(text = stringResource(id = R.string.button_cancel))
                    }
                }
            )
        }
    }
}