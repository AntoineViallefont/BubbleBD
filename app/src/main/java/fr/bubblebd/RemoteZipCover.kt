package fr.bubblebd

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** Fetch central directory and first naturally sorted image only. Unsupported archives use full staging. */
object RemoteZipCover {
    private const val MAX_IMAGE=80*1024*1024
    suspend fun read(size:Long,range:suspend (Long,Int)->ByteArray):ByteArray {
        require(size in 22..2L*1024*1024*1024)
        val tailSize=minOf(size,65557).toInt();val tail=range(size-tailSize,tailSize)
        fun u16(b:ByteArray,p:Int):Int {require(p>=0 && p+2<=b.size);return (b[p].toInt() and 255) or ((b[p+1].toInt() and 255) shl 8)}
        fun u32(b:ByteArray,p:Int):Long {require(p>=0 && p+4<=b.size);return (0..3).sumOf {((b[p+it].toInt() and 255).toLong()) shl (it*8)}}
        val end=(tail.size-22 downTo 0).firstOrNull {u32(tail,it)==0x06054b50L && it+22+u16(tail,it+20)==tail.size} ?: error("ZIP sans répertoire")
        val count=u16(tail,end+10);val dirSize=u32(tail,end+12);val dirOffset=u32(tail,end+16)
        require(u16(tail,end+4)==0 && u16(tail,end+6)==0 && count==u16(tail,end+8))
        require(count in 1..10000 && dirSize in 1..16L*1024*1024 && dirOffset+dirSize<=size-tailSize+end)
        val directory=range(dirOffset,dirSize.toInt())
        data class Entry(val name:String,val flags:Int,val method:Int,val compressed:Long,val expanded:Long,val crc:Long,val offset:Long)
        val images=mutableListOf<Entry>();var p=0
        repeat(count) {
            require(u32(directory,p)==0x02014b50L)
            val flags=u16(directory,p+8);val method=u16(directory,p+10)
            val nameSize=u16(directory,p+28);val extra=u16(directory,p+30);val comment=u16(directory,p+32)
            require(p+46+nameSize+extra+comment<=directory.size)
            val name=String(directory,p+46,nameSize,if(flags and 2048!=0)Charsets.UTF_8 else charset("CP437"))
            if(!name.startsWith("__MACOSX/") && Regex("(?i)\\.(jpe?g|png|webp|bmp|gif|avif)$").containsMatchIn(name))
                images.add(Entry(name,flags,method,u32(directory,p+20),u32(directory,p+24),u32(directory,p+16),u32(directory,p+42)))
            p+=46+nameSize+extra+comment
        }
        val first=images.minWithOrNull {a,b->BookRules.compareNatural(a.name,b.name)} ?: error("Aucune page image")
        require(first.flags and 1==0 && first.method in listOf(0,8) && first.compressed in 1..MAX_IMAGE.toLong() && first.expanded in 1..MAX_IMAGE.toLong())
        val header=range(first.offset,30)
        require(u32(header,0)==0x04034b50L && u16(header,8)==first.method)
        val start=first.offset+30+u16(header,26)+u16(header,28)
        require(start+first.compressed<=dirOffset)
        val compressed=range(start,first.compressed.toInt())
        val bytes=if(first.method==0)compressed else {
            val inflater=Inflater(true)
            try {InflaterInputStream(compressed.inputStream(),inflater).use {input ->
                val output=ByteArrayOutputStream();val buffer=ByteArray(16384)
                while(true) {val n=input.read(buffer);if(n<0)break;require(output.size()+n<=MAX_IMAGE);output.write(buffer,0,n)}
                output.toByteArray()
            }} finally {inflater.end()}
        }
        require(bytes.size.toLong()==first.expanded && CRC32().apply {update(bytes)}.value==first.crc)
        return bytes
    }
}
