package top.cxmeow.risingstones.feature.personaldata.ui.compose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.ZoneId
import top.cxmeow.risingstones.feature.personaldata.domain.*
import kotlin.math.cos
import kotlin.math.sin

/** Canvas/PNG and QR rendering run off the main thread; no files or business requests are created. */
class AndroidPersonalDataShareRenderer(
    context: Context,
    private val resources: PersonalDataShareResourceService,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : PersonalDataShareRenderer {
    private val context = context.applicationContext.createConfigurationContext(context.resources.configuration)
    override suspend fun render(document: PersonalDataShareDocument): PersonalDataShareArtifact = withContext(Dispatchers.Default) {
        val content = ShareCardContentBuilder(context, zone).build(document.content)
        val urls = buildList {
            content.cover?.let { resources.shareImageUrl(it)?.let(::add) }
            resources.shareImageUrl(PersonalDataShareImage.Logo)?.let(::add)
            resources.shareImageUrl(PersonalDataShareImage.GameLogo)?.let(::add)
            document.identity.avatarUrl?.let(::add)
            content.sections.flatMap { it.rows }.forEach { row -> row.image?.let { resources.shareImageUrl(it)?.let(::add) } }
        }.distinct()
        val loader = ImageLoader.Builder(context).build()
        try {
            val gate = Semaphore(6)
            val images = coroutineScope {
                urls.map { url -> async { url to gate.withPermit {
                    if (!allowedShareImageUrl(url)) null else withTimeoutOrNull(8_000) {
                        val result = loader.execute(ImageRequest.Builder(context).data(url).size(750, 400)
                            .allowHardware(false).diskCachePolicy(CachePolicy.DISABLED).memoryCachePolicy(CachePolicy.DISABLED).build())
                        (result as? SuccessResult)?.image?.toBitmap()
                    }
                } } }.awaitAll().toMap()
            }
            ensureActive()
            renderShareCard(context, document, content, resources, images)
        } finally { loader.shutdown() }
    }
}

internal fun allowedShareImageUrl(value: String): Boolean = try {
    val uri = URI(value)
    uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
        uri.host in setOf("ff14risingstones.web.sdo.com", "static.web.sdo.com", "ff14-eo.web.sdo.com", "ff14risingstones.gcloud.com.cn")
} catch (_: Exception) { false }

internal fun renderShareCard(context: Context, document: PersonalDataShareDocument, content: ShareCardContent,
    resources: PersonalDataShareResourceService, images: Map<String, Bitmap?>): PersonalDataShareArtifact {
    val drawing = CardDrawing()
    fun bitmap(image: PersonalDataShareImage?): Bitmap? = image?.let(resources::shareImageUrl)?.let(images::get)
    drawing.header(bitmap(content.cover), content.title)
    drawing.identity(document.identity, document.identity.avatarUrl?.let(images::get))
    drawing.metrics(content.metrics)
    content.radar?.let { drawing.radar(context, it) }
    content.sections.forEach { section ->
        drawing.heading(section.title)
        if (section.columns > 1) drawing.grid(section.rows, section.columns) { bitmap(it) }
        else section.rows.forEach { drawing.row(it, bitmap(it.image)) }
    }
    val pageUrl = resources.sharePageUrl(document.content.kind)
    drawing.footer(context.getString(R.string.pds_qr), context.getString(R.string.pds_footer), pageUrl,
        bitmap(PersonalDataShareImage.GameLogo), bitmap(PersonalDataShareImage.Logo))
    return drawing.finish(images.values.count { it == null })
}

private class CardDrawing {
    private val width = 750
    private val gold = Color.rgb(155, 124, 61)
    private val ink = Color.rgb(44, 41, 36)
    private val muted = Color.rgb(100, 96, 87)
    private val wash = Color.rgb(248, 245, 237)
    private val operations = mutableListOf<(Canvas) -> Unit>()
    private val text = mutableListOf<String>()
    private var y = 0f
    private fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private fun layout(value: String, size: Float, bold: Boolean, w: Int, color: Int = ink): StaticLayout {
        require(value.length <= 32_000)
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size; typeface = if(bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
        return StaticLayout.Builder.obtain(value, 0, value.length, p, w).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false).setLineSpacing(5f, 1f).build()
    }
    private fun put(value: String, x: Float, top: Float, size: Float = 24f, bold: Boolean = false, w: Int = 630, color: Int = ink): Int {
        val block = layout(value, size, bold, w, color)
        operations += { c -> c.save(); c.translate(x, top); block.draw(c); c.restore() }
        if (value.isNotBlank()) text += value
        return block.height
    }
    private fun rect(left: Float, top: Float, right: Float, bottom: Float, color: Int, radius: Float = 16f) {
        operations += { it.drawRoundRect(RectF(left,top,right,bottom),radius,radius,paint(color)) }
    }
    private fun image(bitmap: Bitmap?, left: Float, top: Float, right: Float, bottom: Float, crop: Boolean = false) {
        if (bitmap == null) return
        operations += { c ->
            val target = RectF(left,top,right,bottom)
            val scale = if(crop) maxOf(target.width()/bitmap.width,target.height()/bitmap.height) else minOf(target.width()/bitmap.width,target.height()/bitmap.height)
            val w = bitmap.width*scale; val h = bitmap.height*scale
            c.save(); c.clipRect(target)
            c.drawBitmap(bitmap, null, RectF(target.centerX()-w/2,target.centerY()-h/2,target.centerX()+w/2,target.centerY()+h/2),paint(Color.WHITE))
            c.restore()
        }
    }
    fun header(cover: Bitmap?, title: String) {
        if (cover != null) {
            rect(0f,0f,750f,234f,wash,0f); image(cover,0f,0f,750f,234f,true)
            rect(0f,188f,750f,236f,Color.WHITE,28f)
            y=228f
        } else { rect(0f,0f,750f,8f,gold,0f); y=36f }
        y+=put(title,60f,y,30f,true,color=gold)+24
    }
    fun identity(identity: PersonalDataIdentity, avatar: Bitmap?) {
        rect(60f,y,140f,y+80,wash,40f); image(avatar,60f,y,140f,y+80,true)
        val nameHeight=put(identity.characterName,160f,y,32f,true,530)
        val placeHeight=put(identity.location,160f,y+nameHeight+8,23f,w=530,color=muted)
        y+=maxOf(80,nameHeight+8+placeHeight)+28
    }
    fun metrics(values: List<Pair<String,String>>) {
        values.chunked(2).forEach { row ->
            val h=row.maxOf { (label,value) -> layout(value,32f,true,275,gold).height + layout(label,22f,false,275).height + 44 }.toFloat()
            row.forEachIndexed { i,(label,value) -> val x=60f+i*322
                rect(x,y,x+308,y+h,wash)
                val valueH=put(value,x+16,y+16,32f,true,275,gold)
                put(label,x+16,y+valueH+24,22f,w=275)
            }; y+=h+12
        }
    }
    fun heading(title:String) { y+=18; y+=put(title,60f,y,27f,true)+14 }
    fun row(row:ShareCardRow, icon:Bitmap?) {
        val imageSpace=if(row.image!=null) 78 else 0
        val left=76f+imageSpace; val w=598-imageSpace
        val title=layout(row.title,24f,true,w)
        val body=layout(row.body,21f,false,w,muted)
        val h=maxOf(if(row.image!=null) 70 else 0, title.height+if(row.body.isBlank()) 0 else body.height+8)+28f
        rect(60f,y,690f,y+h,Color.rgb(245,245,244),12f)
        if(row.image!=null) { rect(72f,y+14,136f,y+78,Color.WHITE,8f); image(icon,72f,y+14,136f,y+78) }
        put(row.title,left,y+14,24f,true,w)
        if(row.body.isNotBlank()) put(row.body,left,y+title.height+22,21f,w=w,color=muted)
        y+=h+10
    }
    fun grid(rows: List<ShareCardRow>, columns: Int, resolve: (PersonalDataShareImage?) -> Bitmap?) {
        val cellWidth = (630 - (columns - 1) * 10) / columns
        rows.chunked(columns).forEach { group ->
            val h = group.maxOf { layout(it.title,18f,true,cellWidth-16).height + layout(it.body,18f,false,cellWidth-16).height + 96 }.toFloat()
            group.forEachIndexed { index, row ->
                val x = 60f + index * (cellWidth + 10)
                rect(x,y,x+cellWidth,y+h,Color.rgb(245,245,244),12f)
                val iconLeft=x+(cellWidth-52)/2f
                image(resolve(row.image),iconLeft,y+12,iconLeft+52,y+64)
                val titleHeight=put(row.title,x+8,y+74,18f,true,cellWidth-16)
                put(row.body,x+8,y+80+titleHeight,18f,w=cellWidth-16,color=gold)
            }
            y+=h+10
        }
    }
    fun radar(context: Context, ranks: FrontlineRanks) {
        heading(context.getString(R.string.pds_radar))
        val values=listOf(ranks.kills,ranks.healing,ranks.damageTaken,ranks.damage,ranks.survival,ranks.assists)
        val labels=listOf(R.string.personal_data_metric_kills,R.string.pfl_healing,R.string.pfl_damage_taken,
            R.string.pfl_damage,R.string.pfl_survival,R.string.personal_data_metric_assists).map(context::getString)
        val top=y; val cx=375f; val cy=top+195; val radius=132f
        fun point(i:Int,scale:Float):Pair<Float,Float> { val angle=-Math.PI/2+i*Math.PI/3;return cx+(cos(angle)*radius*scale).toFloat() to cy+(sin(angle)*radius*scale).toFloat() }
        operations += { c ->
            for(level in 1..4) { val path=Path();for(i in 0..5) {val p=point(i,level/4f);if(i==0)path.moveTo(p.first,p.second)else path.lineTo(p.first,p.second)};path.close()
                c.drawPath(path,paint(Color.LTGRAY).apply{style=Paint.Style.STROKE;strokeWidth=1f}) }
            if(values.all { it!=null }) { val path=Path();values.forEachIndexed { i,v -> val p=point(i,(v!!/100).toFloat().coerceIn(0f,1f));if(i==0)path.moveTo(p.first,p.second)else path.lineTo(p.first,p.second) };path.close()
                c.drawPath(path,paint(Color.argb(90,155,124,61)));c.drawPath(path,paint(gold).apply{style=Paint.Style.STROKE;strokeWidth=3f}) }
        }
        labels.forEachIndexed { i,label ->
            val p=point(i,1.38f); val v=values[i]?.let { java.text.NumberFormat.getNumberInstance(context.resources.configuration.locales[0]).format(it) } ?: context.getString(R.string.pdr_unknown)
            val grade=values[i]?.let { if(it>=80) "S" else if(it>=50) "A" else null }
            put(listOfNotNull(label,v,grade).joinToString(" "),p.first-85,p.second-16,19f,w=170)
        }
        y=top+412

    }
    fun footer(hint:String,note:String,url:String,game:Bitmap?,logo:Bitmap?) {
        y+=28
        val top=y
        val qr=QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,180,180,mapOf(EncodeHintType.MARGIN to 4))
        operations += { c -> for(x in 0 until qr.width) for(z in 0 until qr.height) if(qr[x,z]) c.drawRect(510f+x,top+z,511f+x,top+z+1,paint(Color.BLACK)) }
        image(game,60f,top+12,230f,top+64);image(logo,260f,top+20,420f,top+60)
        put(hint,60f,top+88,23f,w=405,color=gold)
        y=top+196; y+=put(note,60f,y,19f,w=630,color=muted)+34
    }
    fun finish(missing:Int):PersonalDataShareArtifact {
        require(y.isFinite() && y in 1f..16_000f)
        val bitmap=Bitmap.createBitmap(width,y.toInt()+2,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(bitmap); canvas.drawColor(Color.WHITE)
            operations.forEach { it(canvas) }
            canvas.drawRect(1f,1f,width-1f,y,paint(gold).apply {style=Paint.Style.STROKE;strokeWidth=2f})
            val bytes=ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));out.toByteArray() }
            require(bytes.size<=10*1024*1024)
            return PersonalDataShareArtifact(bytes,width,bitmap.height,text.joinToString("\n"),missing)
        } finally { bitmap.recycle() }
    }
}
