package com.opc.baize.paper

import android.content.Context
import android.net.Uri
import android.graphics.*
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

object ArtworkImport {
    data class Image(val content:String,val width:Int,val height:Int)
    fun read(context:Context,uri:Uri):Image {
        val bytes=context.contentResolver.openInputStream(uri)!!.use {input ->
            val out=ByteArrayOutputStream();val buffer=ByteArray(65536)
            while(true) {val count=input.read(buffer);if(count<0) break;require(out.size()+count<=20*1024*1024) {"图片最大 20 MB"};out.write(buffer,0,count)}
            out.toByteArray()
        }
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth in 1..32768 && bounds.outHeight in 1..32768) {"无法读取图片尺寸"}
        val options=BitmapFactory.Options();while(maxOf(bounds.outWidth,bounds.outHeight)/options.inSampleSize.coerceAtLeast(1)>1024) options.inSampleSize=options.inSampleSize.coerceAtLeast(1)*2
        var bitmap=checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)) {"无法读取图片"}
        try {
            val orientation=runCatching {ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix=Matrix()
            when(orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f,1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f,-1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {matrix.setRotate(90f);matrix.postScale(-1f,1f)}
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {matrix.setRotate(-90f);matrix.postScale(-1f,1f)}
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            }
            val oriented=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
            if(oriented!==bitmap) {bitmap.recycle();bitmap=oriented}
            while(true) {
                val output=ByteArrayOutputStream();check(bitmap.compress(Bitmap.CompressFormat.PNG,100,output))
                if(output.size()<=240000) return Image(Base64.encodeToString(output.toByteArray(),Base64.NO_WRAP),bitmap.width,bitmap.height)
                require(maxOf(bitmap.width,bitmap.height)>64) {"图片无法压缩到画布容量"}
                val small=Bitmap.createScaledBitmap(bitmap,(bitmap.width*.75f).toInt().coerceAtLeast(1),(bitmap.height*.75f).toInt().coerceAtLeast(1),true)
                bitmap.recycle();bitmap=small
            }
        } finally {bitmap.recycle()}
    }
}
