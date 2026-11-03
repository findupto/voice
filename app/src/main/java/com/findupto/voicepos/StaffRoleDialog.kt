package com.findupto.voicepos

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StaffRoleDialog(store:Store,onClose:()->Unit,onSaved:()->Unit){
 val context=LocalContext.current
 val prefs=context.getSharedPreferences("voice_local_network",0)
 val scope=rememberCoroutineScope()
 var ip by remember{mutableStateOf(prefs.getString("owner_ip","")?: "")}
 var ownerCode by remember{mutableStateOf(prefs.getString("owner_token","")?: "")}
 var name by remember{mutableStateOf(store.staffName())}
 var pin by remember{mutableStateOf("")}
 var role by remember{mutableStateOf("WAITER")}
 var message by remember{mutableStateOf("Sign in using the staff PIN created by the owner. First setup creates the owner account.")}
 var busy by remember{mutableStateOf(false)}
 val session=prefs.getString("staff_session","")?: ""
 val currentRole=store.staffRole()
 fun runTask(task:()->String,onSuccess:()->Unit={}){
  if(busy)return
  busy=true
  scope.launch{
   val result=withContext(Dispatchers.IO){runCatching(task)}
   busy=false
   result.onSuccess{message=it;onSuccess()}.onFailure{message=it.message?: "Authentication failed"}
  }
 }
 AlertDialog(onDismissRequest=onClose,title={Text("STAFF PIN SECURITY",fontWeight=FontWeight.Bold)},
  text={Column(verticalArrangement=Arrangement.spacedBy(9.dp)){
   OutlinedTextField(ip,{ip=it.trim();prefs.edit().putString("owner_ip",it.trim()).apply()},label={Text("Owner phone IP")},singleLine=true,modifier=Modifier.fillMaxWidth())
   OutlinedTextField(ownerCode,{ownerCode=it;prefs.edit().putString("owner_token",it).apply()},label={Text("Owner setup/access code")},singleLine=true,modifier=Modifier.fillMaxWidth())
   OutlinedTextField(name,{name=it},label={Text("Staff name")},singleLine=true,modifier=Modifier.fillMaxWidth())
   OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(8)},label={Text("4–8 digit PIN")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
   Text("Assigned role",style=MaterialTheme.typography.labelMedium)
   Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("OWNER","COUNTER","WAITER","CHEF").forEach{r->FilterChip(selected=role==r,onClick={role=r},label={Text(r)})}}
   if(session.isNotBlank())Text("This device: ${store.staffName()} • ${store.staffRole()}",fontWeight=FontWeight.Bold)
   Text(message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)
  }},
  confirmButton={Column(horizontalAlignment=androidx.compose.ui.Alignment.End){
   Button(enabled=!busy&&ip.isNotBlank()&&ownerCode.isNotBlank()&&name.isNotBlank()&&pin.length in 4..8,onClick={
    runTask({
     val result=LocalNetworkSync.loginStaff(ip,name,pin)
     prefs.edit().putString("staff_session",result.getString("session")).apply()
     store.setStaffSession(result.getString("name"),result.getString("role"))
     "Signed in as ${result.getString("name")} • ${result.getString("role")}"
    }){onSaved()}
   }){Text(if(busy)"PLEASE WAIT…" else "SIGN IN")}
   TextButton(enabled=!busy&&ip.isNotBlank()&&ownerCode.isNotBlank()&&name.isNotBlank()&&pin.length in 4..8,onClick={
    runTask({LocalNetworkSync.setupOwner(ip,ownerCode,name,pin);"Owner account created. Sign in with this PIN."})
   }){Text("FIRST-TIME OWNER SETUP")}
   if(currentRole=="OWNER"&&session.isNotBlank())TextButton(enabled=!busy&&name.isNotBlank()&&pin.length in 4..8,onClick={
    runTask({LocalNetworkSync.enrollStaff(ip,session,name,pin,role);"Staff account created for $name ($role)."})
   }){Text("CREATE STAFF ACCOUNT")}
  }},
  dismissButton={TextButton(onClick=onClose){Text("CLOSE")}}
 )
}
