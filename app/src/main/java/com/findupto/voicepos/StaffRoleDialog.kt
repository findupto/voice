package com.findupto.voicepos

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
 var ip by remember{mutableStateOf(if(LocalNetworkSync.isRunning())"127.0.0.1" else prefs.getString("owner_ip","")?.takeIf{it.isNotBlank()}?:"")}
 var ownerCode by remember{mutableStateOf(prefs.getString("owner_pairing_code", null) ?: LocalNetworkSync.token(context))}
 var name by remember{mutableStateOf(store.staffName())}
 var pin by remember{mutableStateOf("")}
 var role by remember{mutableStateOf("WAITER")}
 var mode by remember{mutableStateOf("SIGN_IN")}
 var message by remember{mutableStateOf("Enter your staff name and PIN. Ask the owner to create your account if you do not have one.")}
 var busy by remember{mutableStateOf(false)}
 val session=prefs.getString("staff_session","")?: ""
 val currentRole=store.staffRole()
 fun runTask(task:()->String,onSuccess:()->Unit={}){
  if(busy)return
  busy=true
  scope.launch{
   val result=withContext(Dispatchers.IO){runCatching(task)}
   busy=false
   result.onSuccess{message=it;onSuccess()}.onFailure{message=it.message?: "Please check the details and try again."}
  }
 }
 AlertDialog(onDismissRequest=onClose,title={Text("STAFF ACCESS",fontWeight=FontWeight.Bold)},
  text={Column(verticalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.fillMaxWidth()){
   OutlinedTextField(ip,{ip=it.trim();prefs.edit().putString("owner_ip",it.trim()).apply()},label={Text("Owner phone address")},placeholder={Text(if(LocalNetworkSync.isRunning())"127.0.0.1 (this phone)" else "192.168.1.10:8765")},singleLine=true,modifier=Modifier.fillMaxWidth())
   OutlinedButton(enabled=!busy&&ip.isNotBlank(),onClick={runTask({LocalNetworkSync.testConnection(ip);"Owner phone reached successfully. Server is responding."})},modifier=Modifier.fillMaxWidth()){Text("TEST OWNER CONNECTION")}
   if(mode=="SIGN_IN"){
    OutlinedTextField(name,{name=it},label={Text("Your staff name")},singleLine=true,modifier=Modifier.fillMaxWidth())
    OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(8)},label={Text("Your 4–8 digit PIN")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
    Button(enabled=!busy&&ip.isNotBlank()&&name.isNotBlank()&&pin.length in 4..8,onClick={
     runTask({
      val result=LocalNetworkSync.loginStaff(ip,name,pin)
      prefs.edit().putString("staff_session",result.getString("session")).apply()
      store.setStaffSession(result.getString("name"),result.getString("role"))
      PosBackgroundSync.enqueueNow(context)
      "Welcome, ${result.getString("name")}."
     }){onSaved()}
    },modifier=Modifier.fillMaxWidth()){Text(if(busy)"SIGNING IN…" else "SIGN IN")}
    if(currentRole in setOf("OWNER","ADMIN")&&session.isNotBlank())TextButton(enabled=!busy,onClick={mode="ADD_STAFF";message="Create a separate PIN for each team member."}){Text("${currentRole}: CREATE STAFF ACCOUNT")}
    TextButton(enabled=!busy,onClick={mode="OWNER_SETUP";message="Use the access code shown on the device hosting the first-time server. This creates the first OWNER account; there are no default Admin credentials."}){Text("FIRST-TIME OWNER SETUP")}
   }else if(mode=="OWNER_SETUP"){
    Text("OWNER SETUP",fontWeight=FontWeight.Bold)
    Text("On the owner's phone: open Network (router icon) → Start Main Server. Copy the Access code shown there.")
    OutlinedTextField(ownerCode,{ownerCode=it},label={Text("Owner access code")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
    OutlinedTextField(name,{name=it},label={Text("Owner name")},singleLine=true,modifier=Modifier.fillMaxWidth())
    OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(8)},label={Text("Create owner PIN (4–8 digits)")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
    Button(enabled=!busy&&ip.isNotBlank()&&ownerCode.isNotBlank()&&name.isNotBlank()&&pin.length in 4..8,onClick={
     runTask({LocalNetworkSync.testConnection(ip);LocalNetworkSync.setupOwner(ip,ownerCode,name,pin);val result=LocalNetworkSync.loginStaff(ip,name,pin);prefs.edit().putString("staff_session",result.getString("session")).putString("owner_ip",ip).apply();store.setStaffSession(result.getString("name"),result.getString("role"));PosBackgroundSync.enqueueNow(context); "Owner account created. Signed in as ${result.getString("name")}."}){onSaved()}
    },modifier=Modifier.fillMaxWidth()){Text(if(busy)"SETTING UP…" else "CREATE OWNER ACCOUNT")}
    TextButton(onClick={mode="SIGN_IN"}){Text("BACK TO SIGN IN")}
   }else{
    Text("CREATE STAFF ACCOUNT",fontWeight=FontWeight.Bold)
    Text("You are signed in as ${store.staffName()} (${store.staffRole()}). Give each staff member their own name and PIN.")
    OutlinedTextField(name,{name=it},label={Text("Staff member name")},singleLine=true,modifier=Modifier.fillMaxWidth())
    OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(8)},label={Text("New 4–8 digit PIN")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
    Text("Choose staff role",style=MaterialTheme.typography.labelMedium)
    Column(verticalArrangement=Arrangement.spacedBy(5.dp)){
     listOf("WAITER","CHEF","COUNTER","RIDER","DISHWASHER","ADMIN").chunked(3).forEach{group->Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){group.forEach{r->FilterChip(selected=role==r,onClick={role=r},label={Text(r)})}}}
    }
    Button(enabled=!busy&&session.isNotBlank()&&ip.isNotBlank()&&name.isNotBlank()&&pin.length in 4..8,onClick={
     runTask({LocalNetworkSync.enrollStaff(ip,session,name,pin,role);"Created ${role} account for $name."}){pin="";name=""}
    },modifier=Modifier.fillMaxWidth()){Text(if(busy)"CREATING…" else "CREATE STAFF ACCOUNT")}
    TextButton(onClick={mode="SIGN_IN"}){Text("BACK")}
   }
   Text(message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)
   if(session.isNotBlank())Text("Signed in: ${store.staffRole()}",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelMedium)
  }},
  confirmButton={TextButton(onClick=onClose){Text("CLOSE")}}
 )
}
