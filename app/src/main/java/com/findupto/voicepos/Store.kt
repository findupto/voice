package com.findupto.voicepos

import android.content.Context
import android.util.Base64
import java.io.File

class Store(private val context: Context) {
    private val p = context.getSharedPreferences("voice_pos_v4", Context.MODE_PRIVATE)
    fun cash() = p.getString("cash", "0")!!.toDoubleOrNull() ?: 0.0
    fun setCash(v: Double) = p.edit().putString("cash", v.toString()).apply()
    fun hasOpeningCash() = p.getBoolean("opening", false)
    fun setOpeningCash(v: Double) { setCash(v); p.edit().putBoolean("opening", true).apply() }
    fun profile(): CompanyProfile = CompanyProfile(p.getString("name", "The Slice of Heaven")!!,p.getString("address", "")!!,p.getString("phone", "")!!,p.getString("footer", "Thank you for your visit")!!,p.getString("logo_path", "")!!,p.getBoolean("sale_logo", false),p.getBoolean("kitchen_logo", false))
    fun saveProfile(x: CompanyProfile) = p.edit().putString("name",x.name).putString("address",x.address).putString("phone",x.phone).putString("footer",x.footer).putString("logo_path",x.logoPath).putBoolean("sale_logo",x.saleLogoEnabled).putBoolean("kitchen_logo",x.kitchenLogoEnabled).apply()
    fun theme() = runCatching { ReceiptTheme.valueOf(p.getString("theme", "MODERN")!!) }.getOrDefault(ReceiptTheme.MODERN)
    fun setTheme(t: ReceiptTheme) = p.edit().putString("theme",t.name).apply()
    fun customerPrinterAddress()=p.getString("customer_printer","")!!; fun kitchenPrinterAddress()=p.getString("kitchen_printer","")!!
    fun setCustomerPrinterAddress(v:String)=p.edit().putString("customer_printer",v).apply(); fun setKitchenPrinterAddress(v:String)=p.edit().putString("kitchen_printer",v).apply()
    private fun encodeItems(items:List<SaleItem>)=items.joinToString("|"){ "${it.name.replace("~"," ").replace("|"," ")}^${it.qty}^${it.price}" }
    private fun decodeItems(raw:String)=raw.split("|").filter{it.isNotBlank()}.mapNotNull{runCatching{val b=it.split("^");SaleItem(b[0],b[1].toInt(),b[2].toDouble())}.getOrNull()}
    fun sales()=p.getString("sales","")!!.lines().filter{it.isNotBlank()}.mapNotNull{runCatching{val a=it.split("~");Sale(a[0].toLong(),decodeItems(a[1]),a[2].toDouble(),a[3].toLong(),a.getOrNull(4)?.replace("%7E","~")?:"",a.getOrNull(5)?.replace("%7E","~")?:"")}.getOrNull()}
    fun addSale(s:Sale){p.edit().putString("sales",(sales()+s).joinToString("\n"){ "${it.id}~${encodeItems(it.items)}~${it.total}~${it.time}~${it.customerName.replace("~","%7E")}~${it.customerPhone.replace("~","%7E")}" }).apply()}
    fun pending()=p.getString("pending","")!!.lines().filter{it.isNotBlank()}.mapNotNull{runCatching{val a=it.split("~");PendingSale(a[0].toLong(),decodeItems(a[1]),a[2].toLong(),a.getOrNull(3)?.replace("%7E","~")?:"",a.getOrNull(4)?.replace("%7E","~")?:"")}.getOrNull()}
    private fun savePending(all:List<PendingSale>){p.edit().putString("pending",all.joinToString("\n"){ "${it.id}~${encodeItems(it.items)}~${it.time}~${it.customerName.replace("~","%7E")}~${it.customerPhone.replace("~","%7E")}" }).apply()}
    fun addPending(s:PendingSale)=savePending(pending()+s); fun replacePending(s:PendingSale)=savePending(pending().map{if(it.id==s.id)s else it}); fun removePending(id:Long)=savePending(pending().filterNot{it.id==id})
    fun menu()=p.getString("menu","")!!.lines().filter{it.isNotBlank()}.mapNotNull{runCatching{val a=it.split("^");MenuItem(a[0].toLong(),a[1],a[2].toDouble(),a.getOrNull(3)?:"",a.getOrNull(4)?:"")}.getOrNull()}
    fun saveMenu(items:List<MenuItem>){p.edit().putString("menu",items.joinToString("\n"){ "${it.id}^${it.name.replace("^"," ")}^${it.price}^${it.variant.replace("^"," ")}^${it.size.replace("^"," ")}" }).apply()}
    fun addMenuItem(i:MenuItem)=saveMenu(menu()+i); fun removeMenuItem(id:Long)=saveMenu(menu().filterNot{it.id==id})
    fun expenses()=p.getString("expenses","")!!.lines().filter{it.isNotBlank()}.mapNotNull{runCatching{val a=it.split("~");Expense(a[0].toLong(),a[1],a[2],a[3].toDouble(),a[4].toLong())}.getOrNull()}
    fun addExpense(e:Expense){p.edit().putString("expenses",(expenses()+e).joinToString("\n"){ "${it.id}~${it.title.replace("~"," ")}~${it.category.replace("~"," ")}~${it.amount}~${it.time}" }).apply()}

    fun exportBackup(): String {
        val logo = profile().logoPath.takeIf { it.isNotBlank() }?.let { path -> runCatching { Base64.encodeToString(File(path).readBytes(), Base64.NO_WRAP) }.getOrNull() ?: "" } ?: ""
        fun q(s:String)=org.json.JSONObject.quote(s)
        val o=org.json.JSONObject(); o.put("format","VOICE_POS_BACKUP_V1"); o.put("cash",cash()); o.put("opening",hasOpeningCash()); o.put("theme",theme().name)
        o.put("profile",org.json.JSONObject().apply{put("name",profile().name);put("address",profile().address);put("phone",profile().phone);put("footer",profile().footer);put("saleLogo",profile().saleLogoEnabled);put("kitchenLogo",profile().kitchenLogoEnabled);put("logo",logo)})
        fun items(a:List<MenuItem>)=org.json.JSONArray().apply{a.forEach{put(org.json.JSONObject().apply{put("id",it.id);put("name",it.name);put("price",it.price);put("variant",it.variant);put("size",it.size)})}}
        fun sales(a:List<Sale>)=org.json.JSONArray().apply{a.forEach{s->put(org.json.JSONObject().apply{put("id",s.id);put("time",s.time);put("total",s.total);put("customerName",s.customerName);put("customerPhone",s.customerPhone);put("items",org.json.JSONArray().apply{s.items.forEach{i->put(org.json.JSONObject().apply{put("name",i.name);put("qty",i.qty);put("price",i.price)})}})}}}
        o.put("menu",items(menu())); o.put("sales",sales(sales())); o.put("pending",sales(pending().map{Sale(it.id,it.items,it.total,it.time,it.customerName,it.customerPhone)}));
        o.put("expenses",org.json.JSONArray().apply{expenses().forEach{put(org.json.JSONObject().apply{put("id",it.id);put("title",it.title);put("category",it.category);put("amount",it.amount);put("time",it.time)})}})
        return o.toString()
    }
    fun importBackup(raw:String) {
        val o=org.json.JSONObject(raw); require(o.optString("format")=="VOICE_POS_BACKUP_V1")
        val pr=o.getJSONObject("profile"); val old=profile(); val logoB64=pr.optString("logo",""); var logoPath=old.logoPath
        if(logoB64.isNotBlank()) runCatching{val f=File(context.filesDir,"branding/company_logo.png");f.parentFile?.mkdirs();f.writeBytes(Base64.decode(logoB64,Base64.DEFAULT));logoPath=f.absolutePath}
        saveProfile(CompanyProfile(pr.optString("name",""),pr.optString("address",""),pr.optString("phone",""),pr.optString("footer","Thank you for your visit"),logoPath,pr.optBoolean("saleLogo"),pr.optBoolean("kitchenLogo")))
        setCash(o.optDouble("cash",0.0));p.edit().putBoolean("opening",o.optBoolean("opening",true)).putString("theme",o.optString("theme","MODERN")).apply()
        val ms=o.optJSONArray("menu")?:org.json.JSONArray();saveMenu((0 until ms.length()).map{val x=ms.getJSONObject(it);MenuItem(x.getLong("id"),x.getString("name"),x.getDouble("price"),x.optString("variant"),x.optString("size"))})
        val ss=o.optJSONArray("sales")?:org.json.JSONArray();p.edit().putString("sales",(0 until ss.length()).joinToString("\n"){val x=ss.getJSONObject(it);"${x.getLong("id")}~${encodeItems(jsonItems(x.getJSONArray("items")))}~${x.getDouble("total")}~${x.getLong("time")}~${x.optString("customerName").replace("~","%7E")}~${x.optString("customerPhone").replace("~","%7E")}"}).apply()
        val es=o.optJSONArray("expenses")?:org.json.JSONArray();p.edit().putString("expenses",(0 until es.length()).joinToString("\n"){val x=es.getJSONObject(it);"${x.getLong("id")}~${x.getString("title")}~${x.getString("category")}~${x.getDouble("amount")}~${x.getLong("time")}"}).apply()
    }
    private fun jsonItems(a:org.json.JSONArray)= (0 until a.length()).map{val x=a.getJSONObject(it);SaleItem(x.getString("name"),x.getInt("qty"),x.getDouble("price"))}
}