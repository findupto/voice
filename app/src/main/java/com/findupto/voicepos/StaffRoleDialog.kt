package com.findupto.voicepos

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun StaffRoleDialog(store:Store,onClose:()->Unit,onSaved:()->Unit){
 var name by remember{mutableStateOf(store.staffName())}
 var role by remember{mutableStateOf(store.staffRole())}
 val roles=listOf("OWNER","COUNTER","WAITER","CHEF")
 AlertDialog(onDismissRequest=onClose,title={Text("STAFF SIGN-IN",fontWeight=FontWeight.Bold)},
  text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
   Text("Select the staff identity for this device. The owner phone should host the local server.")
   OutlinedTextField(value=name,onValueChange={name=it},label={Text("Staff name")},singleLine=true,modifier=Modifier.fillMaxWidth())
   roles.forEach{r->FilterChip(selected=role==r,onClick={role=r},label={Text(r)})}
  }},
  confirmButton={Button(onClick={ {if(name.isNotBlank()){store.setStaffSession(name,role);onSaved()}} },enabled=name.isNotBlank()){Text("SAVE ROLE")}},
  dismissButton={TextButton(onClick=onClose){Text("CANCEL")}}
 )
}
