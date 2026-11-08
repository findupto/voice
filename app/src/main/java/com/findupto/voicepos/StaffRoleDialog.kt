package com.findupto.voicepos

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

@OptIn(androidx.camera.core.ExperimentalGetImage::class)
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
 var showConnectionSettings by remember{mutableStateOf(prefs.getString("owner_ip","").isNullOrBlank())}
 var busy by remember{mutableStateOf(false)}
 val session=prefs.getString("staff_session","")?: ""
 val currentRole=store.staffRole()
 val pairScanner=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->if(result.resultCode==android.app.Activity.RESULT_OK){val raw=result.data?.getStringExtra(LiveQrScannerActivity.EXTRA_QR_CODE).orEmpty();val parts=raw.split("|");if(parts.size==2&&parts[0]=="VOICEPOS-PAIR"&&parts[1].isNotBlank()){ip=parts[1];prefs.edit().putString("owner_ip",ip).apply();showConnectionSettings=false;message="Paired with main POS device. You can now sign in."; }else message="Invalid pairing QR. Scan the QR shown on the main POS device."}}
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
   if(showConnectionSettings){
    OutlinedButton(enabled=!busy,onClick={pairScanner.launch(Intent(context,LiveQrScannerActivity::class.java))},modifier=Modifier.fillMaxWidth()){Text("SCAN MAIN POS QR — PAIR AUTOMATICALLY")}
    OutlinedTextField(ip,{ip=it.trim();prefs.edit().putString("owner_ip",it.trim()).apply()},label={Text("Owner phone address (optional)")},placeholder={Text(if(LocalNetworkSync.isRunning())"127.0.0.1 (this phone)" else "192.168.1.10:8765")},singleLine=true,modifier=Modifier.fillMaxWidth())
    OutlinedButton(enabled=!busy&&ip.isNotBlank(),onClick={runTask({LocalNetworkSync.testConnection(ip);"Owner phone reached successfully. Server is responding."})},modifier=Modifier.fillMaxWidth()){Text("TEST CONNECTION")}
   }else{
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Owner device paired ✓",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary);TextButton(onClick={showConnectionSettings=true}){Text("CHANGE")}}
    OutlinedButton(enabled=!busy,onClick={pairScanner.launch(Intent(context,LiveQrScannerActivity::class.java))},modifier=Modifier.fillMaxWidth()){Text("SCAN A DIFFERENT MAIN DEVICE")}
   }
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
    TextButton(enabled=!busy,onClick={mode="OWNER_SETUP";message="Use the access code shown on the device hosting the first-time server. This recovery setup is only for an empty staff database."}){Text("FIRST-TIME OWNER SETUP")}
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
     (if(currentRole=="OWNER") listOf("WAITER","CHEF","COUNTER","RIDER","DISHWASHER","ADMIN") else listOf("WAITER","CHEF","COUNTER","RIDER","DISHWASHER")).chunked(3).forEach{group->Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){group.forEach{r->FilterChip(selected=role==r,onClick={role=r},label={Text(r)})}}}
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
