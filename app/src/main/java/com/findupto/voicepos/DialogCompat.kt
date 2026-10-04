package com.findupto.voicepos

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

@Composable
fun Dialog(onDismissRequest:()->Unit,content:@Composable ()->Unit){
    androidx.compose.ui.window.Dialog(onDismissRequest=onDismissRequest,properties=DialogProperties(usePlatformDefaultWidth=false),content=content)
}
